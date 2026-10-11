#!/usr/bin/env bash
# Builds the two native libraries the APK needs and drops them into
# android/app/src/main/jniLibs/arm64-v8a/:
#   libPodiumNative.so  the Swift emulator core (Package.swift, product PodiumNative)
#   libpodium_jni.so    the C JNI glue (android/jni/jni_bridge.c)
#   + libc++_shared.so and any Swift runtime libraries libPodiumNative.so still needs
#
# Needs: Swift 6.4 toolchain, the Swift SDK for Android, the Android NDK r30.
set -euo pipefail

SDK_NAME="${SWIFT_ANDROID_SDK:-swift-6.4.0-RELEASE_android}"
API="${ANDROID_API:-28}"
TRIPLE="aarch64-unknown-linux-android${API}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK (r30)}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
cd "$ROOT"

echo "== swift build ($TRIPLE)"
swift build -c release --swift-sdk "$SDK_NAME" --triple "$TRIPLE" --static-swift-stdlib --product PodiumNative
BIN="$(swift build -c release --swift-sdk "$SDK_NAME" --triple "$TRIPLE" --static-swift-stdlib --show-bin-path)"
cp "$BIN/libPodiumNative.so" "$OUT/"

NDK_BIN="$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)"
echo "== JNI glue"
"$NDK_BIN/aarch64-linux-android${API}-clang" -shared -fPIC -O2 -Wall \
    "$ROOT/android/jni/jni_bridge.c" -o "$OUT/libpodium_jni.so" \
    -L"$OUT" -lPodiumNative -llog -ljnigraphics

echo "== runtime libraries"
cp "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so "$OUT/"
# With --static-swift-stdlib there should be nothing else; if the linker still left
# Swift/Foundation shared libraries in NEEDED, bundle them from the Swift SDK too.
SYSTEM_LIBS=" libc.so libm.so libdl.so liblog.so libz.so libandroid.so libjnigraphics.so libc++_shared.so libPodiumNative.so "
SDK_DIR="$(ls -d "$HOME"/.swiftpm/swift-sdks/*android*.artifactbundle 2>/dev/null | head -1 || true)"
"$NDK_BIN/llvm-readelf" -d "$OUT/libPodiumNative.so" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | while read -r need; do
    case "$SYSTEM_LIBS" in *" $need "*) continue ;; esac
    found="$(find "$SDK_DIR" -name "$need" -path '*aarch64*' 2>/dev/null | head -1 || true)"
    if [ -n "$found" ]; then
        echo "   bundling $need"
        cp "$found" "$OUT/"
    else
        echo "   WARNING: $need is needed but wasn't found in the Swift SDK"
    fi
done
ls -la "$OUT"
