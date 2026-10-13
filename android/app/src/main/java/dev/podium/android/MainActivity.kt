package dev.podium.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two tabs. Player: pick an iPod touch 4 / iOS 6.1.6 (10B500) IPSW, power on, play.
 * Settings: where the iPod lives, theme, JIT status, IPA / .deb / file import, logs.
 * Everything heavy happens in the Swift core (libPodiumNative.so).
 */
class MainActivity : AppCompatActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var settings: AppSettings

    private lateinit var root: View
    private lateinit var playerPage: View
    private lateinit var settingsPage: View
    private lateinit var navigation: BottomNavigationView
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var firmwareText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var importButton: MaterialButton
    private lateinit var powerOnButton: MaterialButton
    private lateinit var powerOffButton: MaterialButton
    private lateinit var jitSwitch: MaterialSwitch
    private lateinit var jitText: TextView
    private lateinit var storagePath: TextView
    private lateinit var storageNote: TextView
    private lateinit var storageAccessButton: MaterialButton
    private lateinit var openFolderButton: MaterialButton
    private lateinit var installIpaButton: MaterialButton
    private lateinit var installDebButton: MaterialButton
    private lateinit var importFilesButton: MaterialButton
    private lateinit var logPreview: TextView

    /** Where the iPod currently lives; starts private and is upgraded by [reloadStorage]. */
    @Volatile private lateinit var layout: DeviceStorage.Layout
    private val toolsDir by lazy { File(filesDir, "guest-tools").apply { mkdirs() } }

    @Volatile private var toolsReady = false
    @Volatile private var importing = false          // copying the IPSW or moving the device
    @Volatile private var busyWithFiles = false      // installing IPA/.deb or importing files
    @Volatile private var ipswBootable = false
    @Volatile private var alive = true
    @Volatile private var onSettingsPage = false
    @Volatile private var lastStage = PodiumNative.STAGE_IDLE
    @Volatile private var lastStatus: PodiumStatus? = null
    @Volatile private var jitProbe = -1              // -1 not probed yet; else 0, 1 or 2
    @Volatile private var lastStorageCheck = 0L
    private var offeredMove = false
    private var firmwareSummary = ""

    private val toolNames = listOf("keybag_bootstrap.bin", "podium_syncd.bin", "first_boot_state.plist", "boot_read_files.txt")

    private val pickIpsw = registerForActivityResult(OpenFilesAtDevice()) { uris ->
        uris.firstOrNull()?.let { importIpsw(it) }
    }
    private val pickIpas = registerForActivityResult(OpenFilesAtDevice()) { uris ->
        if (uris.isNotEmpty()) installIpas(uris)
    }
    private val pickDebs = registerForActivityResult(OpenFilesAtDevice()) { uris ->
        if (uris.isNotEmpty()) installDebs(uris)
    }
    private val pickFiles = registerForActivityResult(OpenFilesAtDevice()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }
    private val saveLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) writeLog(uri)
    }
    private val accessSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        reloadStorage(force = true)
    }
    private val legacyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        reloadStorage(force = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        layout = DeviceStorage.privateLayout(this)   // not in a property initializer: no Context yet there
        AppCompatDelegate.setDefaultNightMode(settings.themeMode)
        if (settings.dynamicColor && DynamicColors.isDynamicColorAvailable()) DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        playerPage = findViewById(R.id.playerPage)
        settingsPage = findViewById(R.id.settingsPage)
        navigation = findViewById(R.id.navigation)
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        firmwareText = findViewById(R.id.firmwareText)
        progress = findViewById(R.id.progress)
        importButton = findViewById(R.id.importButton)
        powerOnButton = findViewById(R.id.powerOnButton)
        powerOffButton = findViewById(R.id.powerOffButton)
        jitSwitch = findViewById(R.id.jitSwitch)
        jitText = findViewById(R.id.jitText)
        storagePath = findViewById(R.id.storagePath)
        storageNote = findViewById(R.id.storageNote)
        storageAccessButton = findViewById(R.id.storageAccessButton)
        openFolderButton = findViewById(R.id.openFolderButton)
        installIpaButton = findViewById(R.id.installIpaButton)
        installDebButton = findViewById(R.id.installDebButton)
        importFilesButton = findViewById(R.id.importFilesButton)
        logPreview = findViewById(R.id.logPreview)
        progress.max = 1000

        importButton.setOnClickListener { pickIpsw.launch(arrayOf("*/*")) }
        powerOnButton.setOnClickListener { powerOn() }
        powerOffButton.setOnClickListener { powerOff() }
        installIpaButton.setOnClickListener { pickIpas.launch(arrayOf("*/*")) }
        installDebButton.setOnClickListener { pickDebs.launch(arrayOf("*/*")) }
        importFilesButton.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        storageAccessButton.setOnClickListener { requestFileAccess() }
        openFolderButton.setOnClickListener { openFolder() }
        findViewById<View>(R.id.btnSleep).holdsButton(1)
        findViewById<View>(R.id.btnVolDown).holdsButton(3)
        findViewById<View>(R.id.btnHome).holdsButton(0)
        findViewById<View>(R.id.btnVolUp).holdsButton(2)

        setUpNavigation(savedInstanceState?.getInt(STATE_PAGE) ?: 0)
        setUpAppearance()
        setUpLogButtons()
        renderStorage()
        findViewById<TextView>(R.id.aboutText).text =
            "Podium for Android ${appVersion()} · runs iPod touch 4, iOS 6.1.6 (10B500)"

        Thread {
            jitProbe = PodiumNative.probeJit()
            prepareGuestTools()
            ui.post { refresh() }
        }.start()
        startPolling()
    }

    override fun onResume() {
        super.onResume()
        reloadStorage(force = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, if (onSettingsPage) 1 else 0)
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    // MARK: Navigation + appearance

    private fun setUpNavigation(initialPage: Int) {
        navigation.setOnItemSelectedListener { item ->
            showPage(item.itemId == R.id.nav_settings)
            true
        }
        navigation.selectedItemId = if (initialPage == 1) R.id.nav_settings else R.id.nav_player
        showPage(initialPage == 1)
    }

    private fun showPage(settingsVisible: Boolean) {
        onSettingsPage = settingsVisible
        playerPage.visibility = if (settingsVisible) View.GONE else View.VISIBLE
        settingsPage.visibility = if (settingsVisible) View.VISIBLE else View.GONE
    }

    private fun setUpAppearance() {
        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.themeToggle)
        toggle.check(
            when (settings.themeMode) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.themeLight
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.themeDark
                else -> R.id.themeSystem
            },
        )
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.themeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (mode != settings.themeMode) {
                settings.themeMode = mode
                AppCompatDelegate.setDefaultNightMode(mode)   // recreates the activity if the look changes
            }
        }

        val dynamicSwitch = findViewById<MaterialSwitch>(R.id.dynamicSwitch)
        val available = DynamicColors.isDynamicColorAvailable()
        dynamicSwitch.isEnabled = available
        dynamicSwitch.isChecked = available && settings.dynamicColor
        if (!available) findViewById<TextView>(R.id.dynamicSupport).text = "Needs Android 12 or newer"
        dynamicSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != settings.dynamicColor) {
                settings.dynamicColor = checked
                recreate()
            }
        }
    }

    // MARK: Storage (Documents/ios)

    private fun deviceIsActive() =
        lastStage == PodiumNative.STAGE_PREPARING || lastStage == PodiumNative.STAGE_LOADING_KERNEL ||
            lastStage == PodiumNative.STAGE_BOOTING || lastStage == PodiumNative.STAGE_RUNNING

    /** Re-checks where the iPod should live (file access may have just been granted) and picks up its IPSW. */
    private fun reloadStorage(force: Boolean) {
        if (deviceIsActive() || importing || busyWithFiles) return
        val now = System.currentTimeMillis()
        if (!force && now - lastStorageCheck < 2000) return
        lastStorageCheck = now
        Thread {
            val resolved = DeviceStorage.resolve(this)
            layout = resolved
            if (resolved.ipsw.exists()) inspectFirmware() else {
                ipswBootable = false
                firmwareSummary = ""
                ui.post { firmwareText.text = "No firmware yet. Pick an iPod touch 4 IPSW for iOS 6.1.6 (10B500)." }
            }
            ui.post {
                renderStorage()
                refresh()
                offerMoveFromPrivate()
            }
        }.start()
    }

    private fun renderStorage() {
        val current = layout
        storagePath.text = current.displayPath
        storageNote.text = when {
            current.shared -> "The whole iPod (IPSW, system image, disk) lives here. Browse it from any file manager."
            current.note.isNotEmpty() -> current.note
            else -> "Stored in the app's private storage."
        }
        storageAccessButton.visibility = if (DeviceStorage.hasFileAccess(this)) View.GONE else View.VISIBLE
        openFolderButton.isEnabled = current.shared
    }

    private fun requestFileAccess() {
        val intent = DeviceStorage.accessSettingsIntent(this)
        if (intent != null) {
            try {
                accessSettings.launch(intent)
            } catch (_: Exception) {
                snack("Open Settings > Apps > Podium > Permissions and allow access to all files.")
            }
        } else {
            legacyPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun openFolder() {
        if (!DeviceStorage.openInFileManager(this)) snack("Open your file manager and go to ${layout.displayPath}")
    }

    /** If the iPod was built in private storage before, offer to carry it over to Documents/ios. */
    private fun offerMoveFromPrivate() {
        val current = layout
        val old = DeviceStorage.privateLayout(this)
        if (!current.shared || offeredMove || current.ipsw.exists() || !old.ipsw.exists() || deviceIsActive()) return
        offeredMove = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Move your iPod to Documents/ios?")
            .setMessage("Your IPSW and iOS disk are in the app's private storage. Moving copies them (several GB) to ${current.displayPath}, then removes the private copy.")
            .setPositiveButton("Move") { _, _ -> moveFromPrivate(old, current) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun moveFromPrivate(old: DeviceStorage.Layout, target: DeviceStorage.Layout) {
        importing = true
        refresh()
        Thread {
            try {
                ui.post { statusText.text = "Moving the iPod to Documents/ios… (keep the app open)" }
                for ((from, to) in listOf(old.firmwareDir to target.firmwareDir, old.deviceDir to target.deviceDir)) {
                    if (!from.exists()) continue
                    to.mkdirs()
                    from.copyRecursively(to, overwrite = true)
                    from.deleteRecursively()
                }
                inspectFirmware()
                ui.post { snack("Moved to ${target.displayPath}") }
            } catch (error: Exception) {
                ui.post { snack("Move failed: ${error.message}. Your files in private storage are untouched.") }
            } finally {
                importing = false
                ui.post { refresh() }
            }
        }.start()
    }

    // MARK: Status polling

    private fun startPolling() {
        Thread {
            while (alive) {
                val status = PodiumStatus.parse(PodiumNative.status())
                val logLines = if (onSettingsPage) PodiumNative.log().lines().filter { it.isNotBlank() }.takeLast(6) else null
                ui.post { render(status, logLines) }
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun render(status: PodiumStatus, logLines: List<String>?) {
        lastStage = status.stage
        lastStatus = status
        if (!importing && !busyWithFiles && toolsReady) {
            statusText.text = when (status.stage) {
                PodiumNative.STAGE_IDLE -> if (ipswBootable) "Ready" else "Pick an IPSW to begin"
                PodiumNative.STAGE_STOPPED, PodiumNative.STAGE_ERROR -> status.message
                else -> status.message.ifEmpty { "Working…" }
            }
        }
        val busy = status.stage == PodiumNative.STAGE_PREPARING || status.stage == PodiumNative.STAGE_BOOTING
        if (!importing) {
            progress.visibility = if (busy) View.VISIBLE else View.GONE
            progress.progress = (status.fraction * 1000).toInt()
        }
        detailText.text = when (status.stage) {
            PodiumNative.STAGE_BOOTING, PodiumNative.STAGE_RUNNING -> String.format(
                Locale.US, "%s%.0f M instr/s",
                if (status.secondsRemaining > 0) "About ${status.secondsRemaining.toInt()} s to the lock screen · " else "",
                status.instructionsPerSecond / 1e6,
            )
            PodiumNative.STAGE_PREPARING -> "First launch only; this takes a few minutes."
            else -> ""
        }
        renderJit(status)
        if (logLines != null) logPreview.text = if (logLines.isEmpty()) "No log yet." else logLines.joinToString("\n")
        refresh()
    }

    /** Shows whether the DBT has executable memory. Read-only: the app turns the JIT on by itself when it can. */
    private fun renderJit(status: PodiumStatus) {
        val running = status.stage == PodiumNative.STAGE_BOOTING || status.stage == PodiumNative.STAGE_RUNNING
        val active = if (running) status.jit else jitProbe > 0
        jitSwitch.isChecked = active
        jitText.text = when {
            !running && jitProbe < 0 -> "Checking…"
            active && running -> "Active · ARM code is translated to native"
            active -> "Available · turns on automatically when iOS starts"
            running -> "Inactive · running on the slow interpreter"
            else -> "Not available on this device · the interpreter will be used"
        }
    }

    private fun refresh() {
        val idle = lastStage == PodiumNative.STAGE_IDLE || lastStage == PodiumNative.STAGE_STOPPED || lastStage == PodiumNative.STAGE_ERROR
        val free = idle && !importing && !busyWithFiles
        importButton.isEnabled = free
        powerOnButton.isEnabled = free && toolsReady && ipswBootable
        powerOffButton.isEnabled = lastStage >= PodiumNative.STAGE_LOADING_KERNEL && lastStage != PodiumNative.STAGE_STOPPED
        installIpaButton.isEnabled = free && ipswBootable
        installDebButton.isEnabled = free && ipswBootable
        importFilesButton.isEnabled = free && ipswBootable
    }

    // MARK: Player actions

    private fun powerOn() {
        val current = layout
        current.deviceDir.mkdirs()
        val result = PodiumNative.start(current.ipsw.absolutePath, current.deviceDir.absolutePath, toolsDir.absolutePath)
        if (result != 0) snack("Couldn't start (code $result).")
    }

    private fun powerOff() {
        powerOffButton.isEnabled = false
        Thread {
            val result = PodiumNative.powerOff()
            ui.post {
                when (result) {
                    -1 -> snack("Still preparing iOS; that can't be cancelled yet.")
                    -2 -> snack("Couldn't save the disk. Try Power off again.")
                }
            }
        }.start()
    }

    /** A hardware button that stays down for as long as the finger does (Sleep and Home are held in iOS). */
    private fun View.holdsButton(which: Int) {
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; PodiumNative.button(which, true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; PodiumNative.button(which, false) }
            }
            true
        }
    }

    private fun snack(text: String) {
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).setAnchorView(navigation).show()
    }

    // MARK: Firmware import

    private fun importIpsw(uri: Uri) {
        importing = true
        refresh()
        val current = layout
        Thread {
            try {
                var total = -1L
                contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) total = cursor.getLong(0)
                }
                // The IPSW, a decrypted copy of its root filesystem, and the guest disks all live next to each other.
                current.firmwareDir.mkdirs()
                if (current.firmwareDir.usableSpace < 5L shl 30) ui.post { snack("Podium needs roughly 5 GB free; low storage may make the first launch fail.") }

                val temporary = File(current.firmwareDir, "incoming.ipsw")
                var copied = 0L
                var lastShown = 0L
                contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(1 shl 20)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            val now = System.currentTimeMillis()
                            if (now - lastShown > 300) {
                                lastShown = now
                                val shown = copied
                                ui.post {
                                    statusText.text = if (total > 0) "Copying IPSW… ${shown * 100 / total}%" else "Copying IPSW… ${shown shr 20} MB"
                                    progress.visibility = View.VISIBLE
                                    progress.progress = if (total > 0) (shown * 1000 / total).toInt() else 0
                                }
                            }
                        }
                    }
                }
                current.ipsw.delete()
                if (!temporary.renameTo(current.ipsw)) throw IllegalStateException("couldn't move the copied IPSW into place")
                inspectFirmware()
            } catch (error: Exception) {
                ui.post { snack("Import failed: ${error.message}") }
            } finally {
                importing = false
                ui.post { progress.visibility = View.GONE; refresh() }
            }
        }.start()
    }

    /** Asks the core what this IPSW is and whether Podium can boot it. */
    private fun inspectFirmware() {
        val parts = PodiumNative.inspect(layout.ipsw.absolutePath).split("|")
        val code = parts.getOrNull(0)?.toIntOrNull() ?: -1
        ipswBootable = code == 1
        firmwareSummary = when (code) {
            -1 -> "That file isn't a readable IPSW."
            else -> "${parts.getOrNull(1)} · iOS ${parts.getOrNull(2)} (${parts.getOrNull(3)}) · ${parts.getOrNull(4)}"
        }
        ui.post { firmwareText.text = firmwareSummary }
    }

    // MARK: IPA / .deb / file import (all need the iPod powered off)

    private fun installIpas(uris: List<Uri>) = runStaged(
        uris, "Installing apps…", "Couldn't install",
        { count -> "Installed $count app${if (count == 1) "" else "s"}" },
        "Copied into /Applications. Start iOS to use it; apps that aren't signed or registered may not appear on the home screen.",
    ) { paths -> PodiumNative.installIpas(layout.ipsw.absolutePath, layout.deviceDir.absolutePath, paths.joinToString("\n")) }

    private fun installDebs(uris: List<Uri>) = runStaged(
        uris, "Installing packages…", "Couldn't install",
        { count -> "Installed $count package${if (count == 1) "" else "s"}" },
        "Installed offline into the iPod's disk. Packages that run install scripts are rejected.",
    ) { paths -> PodiumNative.installDebs(layout.ipsw.absolutePath, layout.deviceDir.absolutePath, paths.joinToString("\n")) }

    private fun importFiles(uris: List<Uri>) = runStaged(
        uris, "Importing files…", "Couldn't import",
        { count -> "Imported $count file${if (count == 1) "" else "s"}" },
        "Saved on the iPod in /private/var/mobile/Media/Podium.",
    ) { paths -> PodiumNative.addFiles(layout.deviceDir.absolutePath, paths.joinToString("\n")) }

    /**
     * Copies the picked files to a scratch folder (keeping their names), runs [call] with the paths and shows the result.
     * [call] returns "count|message": count >= 0 on success, -1 with the reason otherwise.
     */
    private fun runStaged(
        uris: List<Uri>, busyText: String, failTitle: String, successTitle: (Int) -> String, successNote: String,
        call: (List<String>) -> String,
    ) {
        busyWithFiles = true
        statusText.text = busyText
        refresh()
        Thread {
            val staging = File(cacheDir, "import")
            var title = failTitle
            var message = ""
            try {
                staging.deleteRecursively()
                staging.mkdirs()
                val paths = uris.mapIndexed { index, uri ->
                    val folder = File(staging, index.toString()).apply { mkdirs() }
                    val target = File(folder, displayName(uri) ?: "file$index")
                    contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                    target.absolutePath
                }
                val parts = call(paths).split("|", limit = 2)
                val count = parts.getOrNull(0)?.toIntOrNull() ?: -1
                val text = parts.getOrNull(1).orEmpty()
                if (count >= 0) {
                    title = successTitle(count)
                    message = if (successNote.isEmpty()) text else "$text\n\n$successNote"
                } else {
                    message = text
                }
            } catch (error: Exception) {
                message = error.message ?: error.toString()
            } finally {
                staging.deleteRecursively()
                busyWithFiles = false
            }
            ui.post {
                refresh()
                MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    // MARK: Logs

    private fun setUpLogButtons() {
        findViewById<View>(R.id.copyLogButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Podium log", buildLogReport()))
            snack("Log copied")
        }
        findViewById<View>(R.id.saveLogButton).setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            saveLog.launch("podium-log-$stamp.txt")
        }
    }

    private fun writeLog(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)!!.use { it.write(buildLogReport().toByteArray()) }
            snack("Log saved")
        } catch (error: Exception) {
            snack("Couldn't save the log: ${error.message}")
        }
    }

    /** A header with what's needed to debug (device, JIT, storage, state), then the core's log. */
    private fun buildLogReport(): String {
        val status = lastStatus
        val jit = when {
            status != null && (status.stage == PodiumNative.STAGE_BOOTING || status.stage == PodiumNative.STAGE_RUNNING) ->
                if (status.jit) "active" else "inactive"
            jitProbe > 0 -> "available"
            jitProbe == 0 -> "not available"
            else -> "unknown"
        }
        return buildString {
            appendLine("Podium for Android ${appVersion()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI"}")
            appendLine("Storage: ${layout.displayPath} (${if (layout.shared) "shared" else "private"})")
            appendLine("Firmware: ${firmwareSummary.ifEmpty { "none" }}")
            appendLine("State: ${status?.message?.ifEmpty { "-" } ?: "-"} (stage ${status?.stage ?: -1})")
            appendLine("JIT: $jit")
            if (status != null) appendLine("Speed: ${status.instructionsPerSecond / 1_000_000} M instr/s")
            appendLine()
            appendLine("--- core log ---")
            append(PodiumNative.log())
        }
    }

    private fun appVersion(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // MARK: Guest tools

    /** Copies the four boot resources out of the APK once per app version; the core reads them as plain files. */
    private fun prepareGuestTools() {
        try {
            val version = packageManager.getPackageInfo(packageName, 0).longVersionCode.toString()
            val marker = File(toolsDir, ".version")
            val complete = toolNames.all { File(toolsDir, it).exists() }
            if (!(complete && marker.exists() && marker.readText() == version)) {
                for (name in toolNames) {
                    assets.open(name).use { input -> FileOutputStream(File(toolsDir, name)).use { input.copyTo(it) } }
                }
                marker.writeText(version)
            }
            toolsReady = true
        } catch (error: Exception) {
            ui.post { snack("Couldn't unpack app files: ${error.message}") }
        }
    }

    private companion object {
        const val STATE_PAGE = "page"
    }
}
