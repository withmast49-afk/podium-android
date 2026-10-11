package dev.podium.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * One screen: pick an iPod touch 4 / iOS 6.1.6 (10B500) IPSW, power on, play.
 * Everything heavy happens in the Swift core (libPodiumNative.so); this only
 * shows its status and forwards touches and buttons.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var firmwareText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logText: TextView
    private lateinit var importButton: Button
    private lateinit var powerOnButton: Button
    private lateinit var powerOffButton: Button

    private val firmwareDir by lazy { File(filesDir, "firmware").apply { mkdirs() } }
    private val ipswFile by lazy { File(firmwareDir, "podium.ipsw") }
    private val supportDir by lazy { File(filesDir, "support").apply { mkdirs() } }
    private val toolsDir by lazy { File(filesDir, "guest-tools").apply { mkdirs() } }

    @Volatile private var toolsReady = false
    @Volatile private var importing = false
    @Volatile private var ipswBootable = false
    @Volatile private var alive = true
    @Volatile private var lastStage = PodiumNative.STAGE_IDLE

    private val toolNames = listOf("keybag_bootstrap.bin", "podium_syncd.bin", "first_boot_state.plist", "boot_read_files.txt")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        Thread {
            prepareGuestTools()
            if (ipswFile.exists()) inspectFirmware()
            ui.post { refreshButtons() }
        }.start()
        startPolling()
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    // MARK: UI

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 20))
            setPadding(dp(10), dp(28), dp(10), dp(10))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        importButton = Button(this).apply { text = "IPSW…"; setOnClickListener { pickIpsw() } }
        powerOnButton = Button(this).apply { text = "Power on"; setOnClickListener { powerOn() } }
        powerOffButton = Button(this).apply { text = "Power off"; setOnClickListener { powerOff() } }
        for (button in listOf(importButton, powerOnButton, powerOffButton)) {
            top.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(top)

        firmwareText = label(12f, Color.LTGRAY).apply { text = "No firmware yet. Pick an iPod touch 4 IPSW for iOS 6.1.6 (10B500)." }
        statusText = label(16f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; text = "Preparing app files…" }
        detailText = label(12f, Color.LTGRAY)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        root.addView(firmwareText)
        root.addView(statusText)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(detailText)

        val screen = ScreenView(this)
        val screenParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
            bottomMargin = dp(6)
        }
        root.addView(screen, screenParams)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(holdButton("Sleep", 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(holdButton("Vol −", 3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(holdButton("Home", 0), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(holdButton("Vol +", 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        logText = label(10f, Color.GRAY).apply { typeface = Typeface.MONOSPACE; maxLines = 5 }
        root.addView(logText)
        return root
    }

    private fun label(size: Float, color: Int) = TextView(this).apply {
        textSize = size
        setTextColor(color)
    }

    /** A hardware button that stays down for as long as the finger does (Sleep and Home are held in iOS). */
    private fun holdButton(title: String, which: Int) = Button(this).apply {
        text = title
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; PodiumNative.button(which, true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; PodiumNative.button(which, false) }
            }
            true
        }
    }

    private fun refreshButtons() {
        val idle = lastStage == PodiumNative.STAGE_IDLE || lastStage == PodiumNative.STAGE_STOPPED || lastStage == PodiumNative.STAGE_ERROR
        importButton.isEnabled = idle && !importing
        powerOnButton.isEnabled = idle && !importing && toolsReady && ipswBootable
        powerOffButton.isEnabled = lastStage >= PodiumNative.STAGE_LOADING_KERNEL && lastStage != PodiumNative.STAGE_STOPPED
    }

    // MARK: Status polling

    private fun startPolling() {
        Thread {
            while (alive) {
                val status = PodiumStatus.parse(PodiumNative.status())
                val log = PodiumNative.log().lines().takeLast(5).joinToString("\n")
                ui.post { render(status, log) }
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun render(status: PodiumStatus, log: String) {
        lastStage = status.stage
        if (!importing && toolsReady) {
            statusText.text = when (status.stage) {
                PodiumNative.STAGE_IDLE -> if (ipswBootable) "Ready" else "Pick an IPSW to begin"
                PodiumNative.STAGE_STOPPED, PodiumNative.STAGE_ERROR -> status.message
                else -> status.message.ifEmpty { "Working…" }
            }
        }
        val busy = status.stage == PodiumNative.STAGE_PREPARING || status.stage == PodiumNative.STAGE_BOOTING
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.progress = (status.fraction * 1000).toInt()
        detailText.text = when (status.stage) {
            PodiumNative.STAGE_BOOTING, PodiumNative.STAGE_RUNNING -> String.format(
                Locale.US, "%s%.0f M instr/s · JIT %s",
                if (status.secondsRemaining > 0) "~${status.secondsRemaining.toInt()} s to the lock screen · " else "",
                status.instructionsPerSecond / 1e6, if (status.jit) "on" else "off (interpreter)",
            )
            PodiumNative.STAGE_PREPARING -> "First launch only; this takes a few minutes."
            else -> ""
        }
        logText.text = log
        refreshButtons()
    }

    // MARK: Actions

    private fun powerOn() {
        val result = PodiumNative.start(ipswFile.absolutePath, supportDir.absolutePath, toolsDir.absolutePath)
        if (result != 0) toast("Couldn't start (code $result).")
    }

    private fun powerOff() {
        powerOffButton.isEnabled = false
        Thread {
            val result = PodiumNative.powerOff()
            ui.post {
                when (result) {
                    -1 -> toast("Still preparing iOS; that can't be cancelled yet.")
                    -2 -> toast("Couldn't save the disk. Try Power off again.")
                }
            }
        }.start()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // MARK: Firmware import

    private fun pickIpsw() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IPSW)
    }

    @Deprecated("Plain Activity; no ActivityResult API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_IPSW && resultCode == RESULT_OK && uri != null) importIpsw(uri)
    }

    private fun importIpsw(uri: Uri) {
        importing = true
        refreshButtons()
        Thread {
            try {
                var total = -1L
                contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) total = cursor.getLong(0)
                }
                // The IPSW, a decrypted copy of its root filesystem, and the guest disks all live in app storage.
                if (filesDir.usableSpace < 5L shl 30) ui.post { toast("Podium needs roughly 5 GB free; low storage may make the first launch fail.") }

                val temporary = File(firmwareDir, "incoming.ipsw")
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
                ipswFile.delete()
                if (!temporary.renameTo(ipswFile)) throw IllegalStateException("couldn't move the copied IPSW into place")
                inspectFirmware()
            } catch (error: Exception) {
                ui.post { toast("Import failed: ${error.message}") }
            } finally {
                importing = false
                ui.post { progress.visibility = View.GONE; refreshButtons() }
            }
        }.start()
    }

    /** Asks the core what this IPSW is and whether Podium can boot it. */
    private fun inspectFirmware() {
        val parts = PodiumNative.inspect(ipswFile.absolutePath).split("|")
        val code = parts.getOrNull(0)?.toIntOrNull() ?: -1
        ipswBootable = code == 1
        val text = when (code) {
            -1 -> "That file isn't a readable IPSW."
            else -> "${parts.getOrNull(1)} · iOS ${parts.getOrNull(2)} (${parts.getOrNull(3)}) · ${parts.getOrNull(4)}"
        }
        ui.post { firmwareText.text = text }
    }

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
            ui.post { toast("Couldn't unpack app files: ${error.message}") }
        }
    }

    private companion object {
        const val REQUEST_IPSW = 1
    }
}
