// swift-tools-version: 6.0
//
// Builds Podium's emulator core as a shared library for Android (and Linux,
// for running the tests). The iOS app is still built from project.yml.
//
//   swift build -c release --swift-sdk aarch64-unknown-linux-android28 --static-swift-stdlib
//   -> .build/aarch64-unknown-linux-android28/release/libPodiumNative.so
import PackageDescription

let package = Package(
    name: "Podium",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        // The JNI glue in android/ links against this.
        .library(name: "PodiumNative", type: .dynamic, targets: ["Podium"]),
    ],
    targets: [
        // JIT memory, AES, SHA-1 and DEFLATE for hosts without CommonCrypto / Compression.
        .target(
            name: "CPodiumSupport",
            path: "Sources/CPodiumSupport",
            linkerSettings: [
                .linkedLibrary("z"),
                .linkedLibrary("log", .when(platforms: [.android])),
            ]
        ),
        // The emulator core + firmware pipeline + the C entry points in AndroidBridge/.
        // The SwiftUI app (App, UI, Rendering, Resources) is not part of this target.
        .target(
            name: "Podium",
            dependencies: ["CPodiumSupport"],
            path: "Podium",
            exclude: [
                "Emulator/EmulatorCore.swift",              // @MainActor/@Observable UI glue; AndroidBridge replaces it
                "Emulator/CPU/DBT/Native",                  // iOS-only PodiumJIT.c (StikDebug)
                "Firmware/Storage/FirmwareLibrary.swift",   // UI-side library
                "Firmware/Download",                        // URLSession downloader; the Android app downloads the IPSW itself
                "Firmware/Models/UTType+IPSW.swift",
                "Shared/AppearanceOption.swift",
            ],
            sources: ["Emulator", "Firmware", "Shared", "AndroidBridge"],
            swiftSettings: [
                // Same as project.yml: the CPU hot loop pays dearly for dynamic exclusivity checks.
                .unsafeFlags(["-enforce-exclusivity=unchecked"], .when(configuration: .release)),
            ]
        ),
        // Pure-logic tests (CPU, memory, boot, platform devices). `swift test` on a Linux x86 host.
        .testTarget(
            name: "PodiumTests",
            dependencies: ["Podium"],
            path: "PodiumTests",
            exclude: [
                "JIT",                                       // needs an AArch64 host
                "Firmware",                                  // zip fixtures use Apple's Compression
                "Boot/_ManualRealFirmwareVerification.swift",
                "Boot/FirmwareDecryptionTests.swift",        // uses CommonCrypto to make its fixtures
            ]
        ),
    ],
    swiftLanguageModes: [.v5]
)
