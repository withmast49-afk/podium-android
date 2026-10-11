# Podium trên Android

Bản port này giữ nguyên lõi emulator Swift (CPU ARMv7, DBT → A64, thiết bị S5L8930X, pipeline IPSW → root filesystem)
và thay phần vỏ: UI SwiftUI + Metal bỏ hết, thay bằng app Kotlin nhỏ + cầu nối C/JNI.
App iOS vẫn build được từ `project.yml` như cũ (mọi chỗ sửa đều có `#if canImport(...)`).

## Dùng (làm hết trên điện thoại)

1. Fork repo, push cả thư mục này lên (giải nén đè lên repo).
2. Tab **Actions** → *Android APK* → **Run workflow** (hoặc push lên `main`).
3. Chạy xong tải artifact **Podium-android-apk**, cài APK.
4. Mở app → **IPSW…** chọn IPSW iPod touch 4 **iOS 6.1.6 (10B500)** → **Power on**.
   Lần đầu app giải mã + dựng root filesystem (vài phút, cần ~5 GB trống).

Máy phải là **arm64** (DBT sinh code AArch64). Nếu JIT bị từ chối nó tự rơi về interpreter (rất chậm).

## Cái gì đổi

| Phần | Trước (iOS) | Giờ |
|---|---|---|
| JIT memory | `PodiumJIT.c` (StikDebug, `MAP_JIT`) | `Sources/CPodiumSupport/podium_jit.c`: mmap RWX, fallback memfd 2 view |
| i-cache | `sys_icache_invalidate` | `__builtin___clear_cache` (`podiumInvalidateICache`) |
| AES / HMAC | CommonCrypto | C tự viết, đã so với `cryptography`/`hashlib` (`tools/test_c_support.py`) |
| SHA-1 | CryptoKit | cùng thư viện C |
| DEFLATE | Compression framework | zlib của NDK, bọc lại đúng tên hàm `compression_*` |
| `clonefile`, `Darwin.pread`, `os.Logger` | Apple | shim trong `Podium/Shared/PlatformCompat.swift` |
| Điều khiển emulator | `EmulatorCore` (@MainActor) | `Podium/AndroidBridge/AndroidBridge.swift` (hàm `podium_*` chuẩn C) |
| UI | SwiftUI | `android/` (Kotlin, SurfaceView, nút Home/Sleep/Vol) |

Không build trong Swift package: `App/`, `UI/`, `Rendering/`, `EmulatorCore`, `FirmwareLibrary`, `Firmware/Download`
(app Android tự copy IPSW qua file picker).

## Chưa được kiểm chứng

Máy mình viết code không có Swift toolchain, nên **chưa ai biên dịch phần Swift/Kotlin này**. Phần C (AES, SHA-1, HMAC,
inflate) đã test thật; JNI glue chỉ kiểm cú pháp. Chỗ dễ vỡ ở lần build đầu:

- Swift SDK for Android còn mới: lỗi `Foundation` thiếu API (vd `Thread.qualityOfService`, `String(format:)` với `%@`).
- Link: nếu `--static-swift-stdlib` không đủ, `tools/build-android.sh` tự gom các `.so` Swift còn thiếu từ SDK.
- Android 15 máy page 16 KB: chưa thử.
- Guest dùng 1 GB RAM (mmap lười) — máy dưới ~4 GB RAM có thể bị kill.
- App không có foreground service: thoát app là emulator chết.
- Mới test logic trên giấy cho hướng boot; không đảm bảo iOS 6.1.6 boot lên lock screen ngay.

Gửi log lỗi của Actions (bước nào đỏ) là sửa tiếp được.
