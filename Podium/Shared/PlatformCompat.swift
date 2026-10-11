import Foundation

// Everything in here exists so the emulator core builds off Apple platforms
// (the Android port builds it with the Swift SDK for Android). On iOS none
// of the non-Darwin code is compiled and the call sites use the Apple
// frameworks exactly as before.

#if canImport(Darwin)
import Darwin
import libkern.OSCacheControl
#elseif canImport(Android)
import Android
import CPodiumSupport
#elseif canImport(Glibc)
import Glibc
import CPodiumSupport
#endif

// MARK: - Instruction cache

/// Makes freshly written machine code visible to the instruction fetch.
@inline(__always)
func podiumInvalidateICache(_ pointer: UnsafeMutableRawPointer, _ length: Int) {
    #if canImport(Darwin)
    sys_icache_invalidate(pointer, length)
    #else
    podium_clear_cache(pointer, length)
    #endif
}

// MARK: - POSIX bits that differ

/// `pread(2)`; the caller's own `pread` name would shadow the C one.
@inline(__always)
func podiumPread(_ fd: Int32, _ buffer: UnsafeMutableRawPointer, _ count: Int, _ offset: Int64) -> Int {
    #if canImport(Darwin)
    return Darwin.pread(fd, buffer, count, off_t(offset))
    #elseif canImport(Android)
    return Android.pread(fd, buffer, count, off_t(offset))
    #else
    return Glibc.pread(fd, buffer, count, off_t(offset))
    #endif
}

/// APFS copy-on-write clone. False means "not cloned, copy the bytes".
func podiumCloneFile(_ source: String, _ destination: String) -> Bool {
    #if canImport(Darwin)
    return clonefile(source, destination, 0) == 0
    #else
    return false
    #endif
}

// MARK: - SHA-1

#if canImport(CryptoKit)
import CryptoKit

func podiumSHA1<D: DataProtocol>(_ data: D) -> [UInt8] {
    Array(Insecure.SHA1.hash(data: data))
}
#else
func podiumSHA1<D: DataProtocol>(_ data: D) -> [UInt8] {
    let bytes = [UInt8](data)
    var digest = [UInt8](repeating: 0, count: 20)
    bytes.withUnsafeBufferPointer { input in
        digest.withUnsafeMutableBufferPointer { output in
            podium_sha1(input.baseAddress, input.count, output.baseAddress)
        }
    }
    return digest
}
#endif

// MARK: - os.Logger

#if !canImport(os)
/// Stand-in for `os.Logger`; nothing in the core logs through it.
struct Logger {
    init(subsystem: String, category: String) {}
    func debug(_ message: String) {}
    func info(_ message: String) {}
    func notice(_ message: String) {}
    func error(_ message: String) {}
}
#endif

// MARK: - CommonCrypto

#if !canImport(CommonCrypto)
typealias CCOperation = UInt32
typealias CCAlgorithm = UInt32
typealias CCOptions = UInt32
typealias CCCryptorStatus = Int32
typealias CCHmacAlgorithm = UInt32

let kCCEncrypt = 0
let kCCDecrypt = 1
let kCCAlgorithmAES = 0
let kCCAlgorithmAES128 = 0
let kCCOptionECBMode = 2
let kCCSuccess: Int32 = 0
let kCCParamError: Int32 = -4300
let kCCKeySizeAES256 = 32
let kCCBlockSizeAES128 = 16
let kCCHmacAlgSHA1 = 0

/// AES (any key size), CBC or ECB, never padded: the only way Podium calls it.
@discardableResult
func CCCrypt(_ op: CCOperation, _ alg: CCAlgorithm, _ options: CCOptions,
             _ key: UnsafeRawPointer?, _ keyLength: Int,
             _ iv: UnsafeRawPointer?,
             _ dataIn: UnsafeRawPointer?, _ dataInLength: Int,
             _ dataOut: UnsafeMutableRawPointer?, _ dataOutAvailable: Int,
             _ dataOutMoved: UnsafeMutablePointer<Int>?) -> CCCryptorStatus {
    guard let key, let dataIn, let dataOut, dataInLength % 16 == 0, dataOutAvailable >= dataInLength else {
        return kCCParamError
    }
    let ecb = options & UInt32(kCCOptionECBMode) != 0
    let status = podium_aes_crypt(op == UInt32(kCCDecrypt) ? 1 : 0, ecb ? 0 : 1,
                                  key.assumingMemoryBound(to: UInt8.self), keyLength,
                                  iv?.assumingMemoryBound(to: UInt8.self),
                                  dataIn.assumingMemoryBound(to: UInt8.self),
                                  dataOut.assumingMemoryBound(to: UInt8.self), dataInLength)
    guard status == 0 else { return kCCParamError }
    dataOutMoved?.pointee = dataInLength
    return kCCSuccess
}

func CCHmac(_ algorithm: CCHmacAlgorithm, _ key: UnsafeRawPointer?, _ keyLength: Int,
            _ data: UnsafeRawPointer?, _ dataLength: Int, _ macOut: UnsafeMutableRawPointer?) {
    guard let macOut else { return }
    podium_hmac_sha1(key?.assumingMemoryBound(to: UInt8.self), keyLength,
                     data?.assumingMemoryBound(to: UInt8.self), dataLength,
                     macOut.assumingMemoryBound(to: UInt8.self))
}
#endif

// MARK: - Compression (raw DEFLATE only, which is all Podium uses)

#if !canImport(Compression)
struct compression_stream {
    var dst_ptr: UnsafeMutablePointer<UInt8>?
    var dst_size: Int = 0
    var src_ptr: UnsafePointer<UInt8>?
    var src_size: Int = 0
    var state: UnsafeMutableRawPointer?
}

struct compression_stream_operation: Equatable { let rawValue: Int32 }
struct compression_algorithm: Equatable { let rawValue: UInt32 }
struct compression_stream_flags: Equatable { let rawValue: Int }
struct compression_status: Equatable { let rawValue: Int32 }

let COMPRESSION_STREAM_DECODE = compression_stream_operation(rawValue: 1)
let COMPRESSION_ZLIB = compression_algorithm(rawValue: 0x205)
let COMPRESSION_STREAM_FINALIZE = compression_stream_flags(rawValue: 1)
let COMPRESSION_STATUS_OK = compression_status(rawValue: 0)
let COMPRESSION_STATUS_END = compression_status(rawValue: 1)
let COMPRESSION_STATUS_ERROR = compression_status(rawValue: -1)

func compression_stream_init(_ stream: UnsafeMutablePointer<compression_stream>,
                             _ operation: compression_stream_operation,
                             _ algorithm: compression_algorithm) -> compression_status {
    stream.initialize(to: compression_stream())
    guard operation == COMPRESSION_STREAM_DECODE, algorithm == COMPRESSION_ZLIB,
          let state = podium_inflate_new() else { return COMPRESSION_STATUS_ERROR }
    stream.pointee.state = state
    return COMPRESSION_STATUS_OK
}

@discardableResult
func compression_stream_destroy(_ stream: UnsafeMutablePointer<compression_stream>) -> compression_status {
    if let state = stream.pointee.state { podium_inflate_free(state) }
    stream.pointee.state = nil
    return COMPRESSION_STATUS_OK
}

func compression_stream_process(_ stream: UnsafeMutablePointer<compression_stream>, _ flags: Int32) -> compression_status {
    guard let state = stream.pointee.state else { return COMPRESSION_STATUS_ERROR }
    var source = stream.pointee.src_ptr
    var sourceSize = stream.pointee.src_size
    var destination = stream.pointee.dst_ptr
    var destinationSize = stream.pointee.dst_size
    let result = podium_inflate_run(state, &source, &sourceSize, &destination, &destinationSize)
    stream.pointee.src_ptr = source
    stream.pointee.src_size = sourceSize
    stream.pointee.dst_ptr = destination
    stream.pointee.dst_size = destinationSize
    switch result {
    case 0: return COMPRESSION_STATUS_OK
    case 1: return COMPRESSION_STATUS_END
    default: return COMPRESSION_STATUS_ERROR
    }
}

func compression_decode_buffer(_ destination: UnsafeMutablePointer<UInt8>, _ destinationSize: Int,
                               _ source: UnsafePointer<UInt8>, _ sourceSize: Int,
                               _ scratch: UnsafeMutableRawPointer?,
                               _ algorithm: compression_algorithm) -> Int {
    guard algorithm == COMPRESSION_ZLIB else { return 0 }
    return Int(podium_inflate_buffer(destination, destinationSize, source, sourceSize))
}
#endif
