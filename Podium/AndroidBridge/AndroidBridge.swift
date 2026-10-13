#if !canImport(Darwin)
import Foundation
import CPodiumSupport

// The C entry points the Android app's JNI glue calls (android/app/src/main/cpp/jni_bridge.c).
// This file replaces what EmulatorCore + the SwiftUI screens do on iOS: power the
// virtual iPod touch on (preparing the root filesystem the first time), report boot
// progress, hand out frames, take input, power off. It builds only off Apple platforms.

/// Process-wide state behind the `podium_*` functions at the bottom of this file.
final class AndroidHost: @unchecked Sendable {
    static let shared = AndroidHost()

    /// Matches the `STAGE_*` constants in the Kotlin side.
    enum Stage: Int32 {
        case idle = 0
        case preparing = 1      // first launch: building the root filesystem from the IPSW
        case loadingKernel = 2
        case booting = 3
        case running = 4        // lock screen (or anything later) is up
        case stopped = 5
        case error = 6
    }

    /// Guest instructions from power-on to the lock screen, as measured on a Mac by upstream Podium.
    private static let expectedBootInstructions: Double = 5_500_000_000
    private static let logCapacity = 2000

    private let lock = NSLock()
    private var stage: Stage = .idle
    private var fraction = 0.0
    private var secondsRemaining = -1.0
    private var message = ""
    private var logLines: [String] = []
    private var session: EmulationSession?
    private var launch: (ipsw: URL, dataDir: URL, assets: URL)?
    private var bootInFlight = false
    private var rateSamples: [(time: Date, retired: UInt64)] = []
    private var instructionsPerSecond = 0.0
    private var jitAvailable = false

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    // MARK: Log + state (call with the lock held unless noted)

    private func appendLocked(_ line: String) {
        logLines.append("\(Self.timeFormatter.string(from: Date())) \(line)")
        if logLines.count > Self.logCapacity { logLines.removeFirst(logLines.count - Self.logCapacity) }
    }

    private func append(_ line: String) {
        lock.lock(); appendLocked(line); lock.unlock()
    }

    private func set(_ stage: Stage, fraction: Double = 0, message: String) {
        lock.lock()
        self.stage = stage
        self.fraction = fraction
        self.secondsRemaining = -1
        self.message = message
        lock.unlock()
    }

    private func fail(_ message: String, detail: String) {
        lock.lock()
        stage = .error
        self.message = message
        bootInFlight = false
        appendLocked("Power-on failed: \(detail)")
        lock.unlock()
    }

    private static func describe(_ error: Error) -> (message: String, detail: String) {
        if let friendly = error as? FriendlyError {
            let detail = friendly.developerDetail
            return (friendly.userMessage, detail.isEmpty ? "\(error)" : detail)
        }
        return ("Podium couldn't start this firmware.", "\(error)")
    }

    // MARK: Inspect

    /// "device|iOS version|build|compatibility" and whether Podium can boot it.
    func inspect(ipsw: URL) -> (summary: String, compatible: Bool)? {
        guard let parsed = try? IPSWParser.parse(fileURL: ipsw) else { return nil }
        let metadata = parsed.metadata
        let device = metadata.primaryDevice?.shortName ?? metadata.primaryDeviceIdentifier ?? "Unknown device"
        let summary = "\(device)|\(metadata.productVersion)|\(metadata.buildVersion)|\(parsed.compatibility.summary)"
        return (summary, parsed.compatibility.isCompatible)
    }

    // MARK: IPA install

    /// Copies .ipa app bundles into /Applications on the guest disk. The iPod must be off.
    /// Returns the number of apps installed (>= 0) or -1 with an explanation.
    func installIPAs(ipsw: URL, dataDir: URL, ipas: [URL]) -> (code: Int32, message: String) {
        lock.lock()
        let busy = session != nil || bootInFlight
        lock.unlock()
        do {
            let storage = PersistentGuestStorage(appSupportURL: dataDir)
            let apps = try storage.installIPAs(ipas, forFirmwareAt: ipsw, emulatorIsBusy: busy)
            let names = apps.map { $0.name }.joined(separator: ", ")
            append("Installed \(apps.count) IPA app(s): \(names)")
            return (Int32(apps.count), names)
        } catch {
            let text = (error as? LocalizedError)?.errorDescription ?? "\(error)"
            append("IPA install failed: \(text)")
            return (-1, text)
        }
    }

    /// Installs script-free .deb packages (tweaks, Cydia payloads) into the guest's root. The iPod must be off.
    func installPackages(ipsw: URL, dataDir: URL, debs: [URL]) -> (code: Int32, message: String) {
        lock.lock()
        let busy = session != nil || bootInFlight
        lock.unlock()
        do {
            let storage = PersistentGuestStorage(appSupportURL: dataDir)
            let installed = try storage.installDebianPackages(debs, forFirmwareAt: ipsw, emulatorIsBusy: busy)
            let names = installed.map { "\($0.name) \($0.version)" }.joined(separator: ", ")
            append("Installed \(installed.count) package(s): \(names)")
            return (Int32(installed.count), names)
        } catch {
            let text = (error as? LocalizedError)?.errorDescription ?? "\(error)"
            append("Package install failed: \(text)")
            return (-1, text)
        }
    }

    /// Copies any files into /private/var/mobile/Media/Podium on the guest disk. The iPod must be off.
    func addFiles(dataDir: URL, files: [URL]) -> (code: Int32, message: String) {
        lock.lock()
        let busy = session != nil || bootInFlight
        lock.unlock()
        do {
            let storage = PersistentGuestStorage(appSupportURL: dataDir)
            try storage.addFiles(files, emulatorIsBusy: busy)
            append("Copied \(files.count) file(s) into the iPod.")
            return (Int32(files.count), "/private/var/mobile/Media/Podium")
        } catch {
            let text = (error as? LocalizedError)?.errorDescription ?? "\(error)"
            append("File import failed: \(text)")
            return (-1, text)
        }
    }

    // MARK: Power on

    func start(ipsw: URL, dataDir: URL, assets: URL) -> Bool {
        lock.lock()
        if bootInFlight || session != nil { lock.unlock(); return false }
        bootInFlight = true
        launch = (ipsw, dataDir, assets)
        stage = .loadingKernel
        fraction = 0
        secondsRemaining = -1
        message = "Reading the firmware…"
        logLines.removeAll()
        appendLocked("Powering on \(ipsw.lastPathComponent).")
        lock.unlock()
        spawnBoot()
        return true
    }

    private func spawnBoot() {
        let thread = Thread { [self] in boot() }
        thread.name = "Podium boot"
        thread.stackSize = 16 << 20
        thread.start()
    }

    private func boot() {
        lock.lock()
        guard let launch else { bootInFlight = false; lock.unlock(); return }
        lock.unlock()
        let (ipsw, dataDir, assets) = launch
        do {
            let parsed = try IPSWParser.parse(fileURL: ipsw)
            guard parsed.compatibility.isCompatible else {
                fail("This firmware isn't supported yet (\(parsed.compatibility.summary)). Podium needs iPod touch 4 with iOS 6.1.6 (10B500).",
                     detail: parsed.compatibility.summary)
                return
            }
            let firmware = ImportedFirmware(id: UUID(), metadata: parsed.metadata, compatibility: parsed.compatibility,
                                            importedAt: Date(), storedFileName: ipsw.lastPathComponent, isActive: true)
            let storage = PersistentGuestStorage(appSupportURL: dataDir)

            if !RootFilesystemPreparer.isPrepared(forFirmwareAt: ipsw) {
                set(.preparing, message: "Preparing iOS (first launch only)…")
                append("Preparing the root filesystem from the IPSW (first launch only)…")
                let keybag = [UInt8](try Data(contentsOf: assets.appendingPathComponent("keybag_bootstrap.bin")))
                let syncDaemon = (try? Data(contentsOf: assets.appendingPathComponent("podium_syncd.bin"))).map { [UInt8]($0) }
                let firstBootState = try? Data(contentsOf: assets.appendingPathComponent("first_boot_state.plist"))
                let bootReadFiles = (try? String(contentsOf: assets.appendingPathComponent("boot_read_files.txt"), encoding: .utf8))
                    .map(RootFilesystemRecipe.fileList) ?? []
                let started = Date()
                try RootFilesystemPreparer.prepare(firmwareAt: ipsw, keybagBootstrap: keybag, syncDaemon: syncDaemon,
                                                   firstBootState: firstBootState, bootReadFiles: bootReadFiles) { progress in
                    let text = progress.phase == .extracting ? "Extracting iOS from the IPSW…" : "Building the system volume…"
                    self.set(.preparing, fraction: progress.fraction, message: text)
                }
                append(String(format: "Root filesystem ready in %.1f s.", Date().timeIntervalSince(started)))
            }

            set(.loadingKernel, message: "Loading the kernel…")
            let userImage = try storage.prepareUserVolume(forFirmwareAt: ipsw)
            if userImage.fromOlderRecipe {
                append("This iOS install was made by an older Podium; erase it to pick up the newer system image.")
            }
            let kernel = try KernelcacheExtractor.extractKernelMachO(from: firmware, storedAt: ipsw)
            let deviceTree = try DeviceTreeExtractor.extractDeviceTree(from: firmware, storedAt: ipsw)
            let session = try EmulationSession(kernel: kernel, deviceTree: deviceTree, rootFilesystem: userImage.url, persistent: true)
            session.onFinish = { [weak self, weak session] state in
                guard let self, let session else { return }
                self.sessionFinished(state, from: session)
            }

            lock.lock()
            self.session = session
            rateSamples = [(Date(), 0)]
            instructionsPerSecond = 0
            stage = .booting
            fraction = 0
            secondsRemaining = -1
            message = "Starting iOS…"
            bootInFlight = false
            appendLocked("Kernel loaded; iOS is starting.")
            lock.unlock()
            session.start()
        } catch {
            let (message, detail) = Self.describe(error)
            fail(message, detail: detail)
        }
    }

    private func sessionFinished(_ state: EmulationSession.State, from finished: EmulationSession) {
        lock.lock()
        guard session === finished else { lock.unlock(); return }
        if finished.hasStorageFlushFailure {
            stage = .error
            message = "The guest stopped but its disk couldn't be saved. Tap Power Off to retry."
            appendLocked("Storage flush failed: \(finished.storageFlushFailureDescription ?? "unknown error")")
            lock.unlock()
            return
        }
        session = nil
        var restart = false
        switch state {
        case .panicked(let text):
            stage = .error; message = "iOS panicked: \(text)"; appendLocked("Kernel panic: \(text)")
        case .halted(let reason):
            stage = .error; message = "Emulation stopped: \(reason)"; appendLocked("Halted: \(reason)")
        case .shutDown:
            stage = .stopped; message = "iOS shut down."; appendLocked("iOS shut down.")
        case .restarting:
            stage = .loadingKernel; message = "Restarting…"; appendLocked("iOS is restarting.")
            bootInFlight = true
            restart = true
        case .stopped, .running:
            stage = .stopped; message = "Stopped."
        }
        lock.unlock()
        if restart { spawnBoot() }
    }

    // MARK: Power off

    /// 0 done, -1 nothing can be cancelled right now (still preparing), -2 disk flush failed.
    func powerOff() -> Int32 {
        lock.lock()
        let current = session
        let busy = bootInFlight
        lock.unlock()
        guard let current else { return busy ? -1 : 0 }
        current.onFinish = nil
        let result = current.stop()
        guard result.storageFlushed else {
            lock.lock()
            stage = .error
            message = "Couldn't safely power off; try again."
            appendLocked("Power-off storage flush failed: \(result.state)")
            lock.unlock()
            return -2
        }
        lock.lock()
        session = nil
        stage = .stopped
        message = "Powered off."
        appendLocked("Powered off.")
        lock.unlock()
        return 0
    }

    // MARK: Progress

    /// Refreshes boot progress from the running session and returns the current state.
    func status() -> (stage: Stage, line: String) {
        lock.lock()
        let activeSession = session
        let currentStage = stage
        lock.unlock()

        var lockScreen = false
        var snapshot: EmulationSession.Snapshot?
        if let activeSession {
            snapshot = activeSession.snapshot()
            if currentStage == .booting { lockScreen = Self.lockScreenIsUp(activeSession.display) }
        }

        lock.lock()
        defer { lock.unlock() }
        if let snapshot, session != nil {
            jitAvailable = snapshot.jitAvailable
            let now = Date()
            rateSamples.append((now, snapshot.retiredInstructions))
            rateSamples.removeAll { now.timeIntervalSince($0.time) > 6 }
            if let first = rateSamples.first, let last = rateSamples.last, last.time > first.time, last.retired >= first.retired {
                instructionsPerSecond = Double(last.retired - first.retired) / last.time.timeIntervalSince(first.time)
            }
            if stage == .booting {
                if lockScreen {
                    stage = .running
                    fraction = 1
                    secondsRemaining = 0
                    message = "Running"
                    appendLocked("Lock screen up after \(snapshot.retiredInstructions) instructions.")
                } else {
                    let done = Double(snapshot.retiredInstructions)
                    fraction = min(done / Self.expectedBootInstructions, 0.99)
                    secondsRemaining = instructionsPerSecond > 0
                        ? max(Self.expectedBootInstructions - done, 0) / instructionsPerSecond : -1
                }
            }
        }
        let line = "\(fraction)|\(secondsRemaining)|\(jitAvailable ? 1 : 0)|\(Int(instructionsPerSecond))|\(message)"
        return (stage, line)
    }

    /// The boot screens are almost all black or a dark glow; the lock screen is a bright wallpaper.
    private static func lockScreenIsUp(_ display: DisplayScanout) -> Bool {
        guard !display.activeLayers.isEmpty else { return false }
        let width = display.pixelWidth, height = display.pixelHeight
        var pixels = [UInt32](repeating: 0, count: width * height)
        pixels.withUnsafeMutableBytes { display.copyCurrentFrame(into: $0) }
        var bright = 0, sampled = 0
        for index in stride(from: 0, to: pixels.count, by: 37) {
            let pixel = pixels[index]
            let sum = (pixel & 0xFF) + (pixel >> 8 & 0xFF) + (pixel >> 16 & 0xFF)
            sampled += 1
            if sum > 3 * 48 { bright += 1 }
        }
        return Double(bright) / Double(max(sampled, 1)) > 0.4
    }

    func logTail() -> String {
        lock.lock()
        defer { lock.unlock() }
        return logLines.joined(separator: "\n")
    }

    // MARK: Frames + input

    /// Tightly packed BGRA8, `frameWidth * frameHeight * 4` bytes. 0 if nothing is running.
    func copyFrame(into buffer: UnsafeMutableRawBufferPointer) -> Int {
        lock.lock()
        let display = session?.display
        lock.unlock()
        guard let display else { return 0 }
        let needed = display.pixelWidth * display.pixelHeight * 4
        guard buffer.count >= needed else { return 0 }
        display.copyCurrentFrame(into: buffer)
        return needed
    }

    func send(_ event: InputEvent) {
        lock.lock()
        let current = session
        lock.unlock()
        current?.send(event)
    }
}

// MARK: - C ABI

/// Copies as many whole characters of `string` as fit (with a NUL) and returns the byte count.
private func writeCString(_ string: String, to out: UnsafeMutablePointer<CChar>?, capacity: Int32) -> Int32 {
    guard let out, capacity > 0 else { return 0 }
    let limit = Int(capacity) - 1
    var bytes: [UInt8] = []
    for character in string {
        let utf8 = Array(character.utf8)
        if bytes.count + utf8.count > limit { break }
        bytes.append(contentsOf: utf8)
    }
    for (index, byte) in bytes.enumerated() { out[index] = CChar(bitPattern: byte) }
    out[bytes.count] = 0
    return Int32(bytes.count)
}

private func url(_ path: UnsafePointer<CChar>?) -> URL? {
    guard let path else { return nil }
    return URL(fileURLWithPath: String(cString: path))
}

@_cdecl("podium_bridge_version")
public func podium_bridge_version() -> Int32 { 1 }

/// Reads the IPSW's manifest. Returns 1 if Podium can boot it, 0 if it parses but isn't
/// supported, -1 if it isn't a readable IPSW. `out` gets "device|version|build|verdict".
@_cdecl("podium_inspect_ipsw")
public func podium_inspect_ipsw(_ path: UnsafePointer<CChar>?, _ out: UnsafeMutablePointer<CChar>?, _ capacity: Int32) -> Int32 {
    guard let ipsw = url(path), let result = AndroidHost.shared.inspect(ipsw: ipsw) else {
        _ = writeCString("Not a readable IPSW", to: out, capacity: capacity)
        return -1
    }
    _ = writeCString(result.summary, to: out, capacity: capacity)
    return result.compatible ? 1 : 0
}

/// Starts powering on in the background. 0 started, -1 bad arguments, -2 already running.
@_cdecl("podium_start")
public func podium_start(_ ipsw: UnsafePointer<CChar>?, _ dataDir: UnsafePointer<CChar>?, _ assetsDir: UnsafePointer<CChar>?) -> Int32 {
    guard let ipswURL = url(ipsw), let dataURL = url(dataDir), let assetsURL = url(assetsDir) else { return -1 }
    return AndroidHost.shared.start(ipsw: ipswURL, dataDir: dataURL, assets: assetsURL) ? 0 : -2
}

/// Blocks until the machine is off and its disk is saved. 0 done, -1 still preparing, -2 flush failed.
@_cdecl("podium_power_off")
public func podium_power_off() -> Int32 { AndroidHost.shared.powerOff() }

/// Returns the stage (0 idle, 1 preparing, 2 loading kernel, 3 booting, 4 running, 5 stopped, 6 error)
/// and fills `out` with "fraction|secondsRemaining|jit|instructionsPerSecond|message".
@_cdecl("podium_status")
public func podium_status(_ out: UnsafeMutablePointer<CChar>?, _ capacity: Int32) -> Int32 {
    let result = AndroidHost.shared.status()
    _ = writeCString(result.line, to: out, capacity: capacity)
    return result.stage.rawValue
}

@_cdecl("podium_log_tail")
public func podium_log_tail(_ out: UnsafeMutablePointer<CChar>?, _ capacity: Int32) -> Int32 {
    writeCString(AndroidHost.shared.logTail(), to: out, capacity: capacity)
}

/// Installs IPAs (paths separated by newlines). Returns the app count (>= 0), or -1 with the reason in `out`.
/// On success `out` holds the installed app names.
@_cdecl("podium_install_ipas")
public func podium_install_ipas(_ ipsw: UnsafePointer<CChar>?, _ dataDir: UnsafePointer<CChar>?, _ ipaPaths: UnsafePointer<CChar>?,
                                _ out: UnsafeMutablePointer<CChar>?, _ capacity: Int32) -> Int32 {
    guard let ipswURL = url(ipsw), let dataURL = url(dataDir), let ipaPaths else {
        _ = writeCString("Missing arguments.", to: out, capacity: capacity)
        return -1
    }
    let ipas = String(cString: ipaPaths).split(separator: "\n").map { URL(fileURLWithPath: String($0)) }
    let result = AndroidHost.shared.installIPAs(ipsw: ipswURL, dataDir: dataURL, ipas: ipas)
    _ = writeCString(result.message, to: out, capacity: capacity)
    return result.code
}

/// Installs .deb packages (paths separated by newlines). Returns the package count (>= 0), or -1 with the reason in `out`.
@_cdecl("podium_install_debs")
public func podium_install_debs(_ ipsw: UnsafePointer<CChar>?, _ dataDir: UnsafePointer<CChar>?, _ debPaths: UnsafePointer<CChar>?,
                                _ out: UnsafeMutablePointer<CChar>?, _ capacity: Int32) -> Int32 {
    guard let ipswURL = url(ipsw), let dataURL = url(dataDir), let debPaths else {
        _ = writeCString("Missing arguments.", to: out, capacity: capacity)
        return -1
    }
    let debs = String(cString: debPaths).split(separator: "\n").map { URL(fileURLWithPath: String($0)) }
    let result = AndroidHost.shared.installPackages(ipsw: ipswURL, dataDir: dataURL, debs: debs)
    _ = writeCString(result.message, to: out, capacity: capacity)
    return result.code
}

/// Copies any files (paths separated by newlines) into the iPod's Media/Podium folder.
@_cdecl("podium_add_files")
public func podium_add_files(_ dataDir: UnsafePointer<CChar>?, _ filePaths: UnsafePointer<CChar>?,
                             _ out: UnsafeMutablePointer<CChar>?, _ capacity: Int32) -> Int32 {
    guard let dataURL = url(dataDir), let filePaths else {
        _ = writeCString("Missing arguments.", to: out, capacity: capacity)
        return -1
    }
    let files = String(cString: filePaths).split(separator: "\n").map { URL(fileURLWithPath: String($0)) }
    let result = AndroidHost.shared.addFiles(dataDir: dataURL, files: files)
    _ = writeCString(result.message, to: out, capacity: capacity)
    return result.code
}

/// 0 if `directory` can hold the live guest disk (shared writable mmap works there), else an errno.
@_cdecl("podium_probe_storage")
public func podium_probe_storage(_ directory: UnsafePointer<CChar>?) -> Int32 {
    guard let directory else { return 22 }
    return podium_probe_shared_mmap(directory)
}

/// Whether this device lets the DBT have executable memory: 0 no, 1 RWX mapping, 2 dual mapping.
@_cdecl("podium_probe_jit")
public func podium_probe_jit() -> Int32 { podium_jit_probe() }

@_cdecl("podium_frame_width")
public func podium_frame_width() -> Int32 { Int32(GuestMemoryLayout.framebufferWidth) }

@_cdecl("podium_frame_height")
public func podium_frame_height() -> Int32 { Int32(GuestMemoryLayout.framebufferHeight) }

/// Copies the current screen as BGRA8 into `buffer`; returns the byte count (0 = no frame).
@_cdecl("podium_copy_frame")
public func podium_copy_frame(_ buffer: UnsafeMutableRawPointer?, _ capacity: Int32) -> Int32 {
    guard let buffer, capacity > 0 else { return 0 }
    return Int32(AndroidHost.shared.copyFrame(into: UnsafeMutableRawBufferPointer(start: buffer, count: Int(capacity))))
}

/// phase: 0 began, 1 moved, 2 ended. x, y in the device's own 640x960 pixels.
@_cdecl("podium_touch")
public func podium_touch(_ phase: Int32, _ touchID: Int32, _ x: Double, _ y: Double) {
    let point = TouchPoint(x: min(max(x, 0), Double(GuestMemoryLayout.framebufferWidth)),
                           y: min(max(y, 0), Double(GuestMemoryLayout.framebufferHeight)),
                           touchID: Int(touchID))
    switch phase {
    case 0: AndroidHost.shared.send(.touchBegan(point))
    case 1: AndroidHost.shared.send(.touchMoved(point))
    default: AndroidHost.shared.send(.touchEnded(point))
    }
}

/// which: 0 home, 1 power, 2 volume up, 3 volume down.
@_cdecl("podium_button")
public func podium_button(_ which: Int32, _ pressed: Int32) {
    let down = pressed != 0
    switch which {
    case 0: AndroidHost.shared.send(.homeButton(pressed: down))
    case 1: AndroidHost.shared.send(.powerButton(pressed: down))
    case 2: AndroidHost.shared.send(.volumeUp(pressed: down))
    default: AndroidHost.shared.send(.volumeDown(pressed: down))
    }
}
#endif
