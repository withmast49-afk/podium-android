package dev.podium.android

import android.graphics.Bitmap

/** The C entry points of the Swift emulator core, through android/jni/jni_bridge.c. */
object PodiumNative {
    init {
        System.loadLibrary("podium_jni")
    }

    const val STAGE_IDLE = 0
    const val STAGE_PREPARING = 1
    const val STAGE_LOADING_KERNEL = 2
    const val STAGE_BOOTING = 3
    const val STAGE_RUNNING = 4
    const val STAGE_STOPPED = 5
    const val STAGE_ERROR = 6

    @JvmStatic external fun bridgeVersion(): Int

    /** "code|device|version|build|verdict": code 1 bootable, 0 unsupported, -1 not an IPSW. */
    @JvmStatic external fun inspect(path: String): String

    /** 0 started, -1 bad arguments, -2 already running. Returns at once; poll [status]. */
    @JvmStatic external fun start(ipsw: String, dataDir: String, assetsDir: String): Int

    /** Blocks until off. 0 done, -1 still preparing, -2 disk flush failed. */
    @JvmStatic external fun powerOff(): Int

    /** "stage|fraction|secondsRemaining|jit|instructionsPerSecond|message". */
    @JvmStatic external fun status(): String

    @JvmStatic external fun log(): String

    /** "count|names" (count >= 0) or "-1|reason". [ipaPaths] are newline-separated file paths. */
    @JvmStatic external fun installIpas(ipsw: String, dataDir: String, ipaPaths: String): String

    /** "count|names" (count >= 0) or "-1|reason" for script-free .deb packages. */
    @JvmStatic external fun installDebs(ipsw: String, dataDir: String, debPaths: String): String

    /** "count|where" (count >= 0) or "-1|reason": copies any files into the iPod's Media/Podium folder. */
    @JvmStatic external fun addFiles(dataDir: String, filePaths: String): String

    /** 0 if [directory] can hold the live iPod disk (shared writable mmap works there), else an errno. */
    @JvmStatic external fun probeStorage(directory: String): Int

    /** What the DBT could get from this device: 0 no JIT, 1 RWX memory, 2 dual-mapped memory. */
    @JvmStatic external fun probeJit(): Int
    @JvmStatic external fun frameWidth(): Int
    @JvmStatic external fun frameHeight(): Int

    /** Fills [bitmap] (ARGB_8888, frameWidth x frameHeight) with the screen. False if no frame. */
    @JvmStatic external fun drawFrame(bitmap: Bitmap): Boolean

    /** phase 0 began, 1 moved, 2 ended; x, y in the device's 640x960 pixels. */
    @JvmStatic external fun touch(phase: Int, id: Int, x: Double, y: Double)

    /** which: 0 home, 1 power, 2 volume up, 3 volume down. */
    @JvmStatic external fun button(which: Int, pressed: Boolean)
}

data class PodiumStatus(
    val stage: Int,
    val fraction: Double,
    val secondsRemaining: Double,
    val jit: Boolean,
    val instructionsPerSecond: Long,
    val message: String,
) {
    companion object {
        fun parse(line: String): PodiumStatus {
            val parts = line.split("|", limit = 6)
            fun at(i: Int) = parts.getOrNull(i) ?: ""
            return PodiumStatus(
                stage = at(0).toIntOrNull() ?: PodiumNative.STAGE_IDLE,
                fraction = at(1).toDoubleOrNull() ?: 0.0,
                secondsRemaining = at(2).toDoubleOrNull() ?: -1.0,
                jit = at(3) == "1",
                instructionsPerSecond = at(4).toLongOrNull() ?: 0L,
                message = at(5),
            )
        }
    }
}
