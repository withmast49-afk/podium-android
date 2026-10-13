package dev.podium.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.content.ActivityNotFoundException
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * Where the virtual iPod lives. Preferred: Documents/ios on shared storage (visible in every file
 * manager). That needs "all files access" because the emulator core opens plain file paths, and the
 * shared filesystem has to allow the writable shared mapping the guest disk uses. Otherwise the
 * app's private folders are used, exactly as before.
 */
object DeviceStorage {
    const val FOLDER = "ios"
    private const val AUTHORITY = "com.android.externalstorage.documents"

    class Layout(val firmwareDir: File, val deviceDir: File, val shared: Boolean, val note: String) {
        val ipsw: File get() = File(firmwareDir, "podium.ipsw")
        val displayPath: String get() = firmwareDir.parentFile?.absolutePath ?: firmwareDir.absolutePath
    }

    @Suppress("DEPRECATION")
    fun sharedRoot(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)

    /** The original private layout (also the fallback). */
    fun privateLayout(context: Context, note: String = ""): Layout =
        Layout(File(context.filesDir, "firmware"), File(context.filesDir, "support"), false, note)

    fun hasFileAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Settings screen for "all files access" (Android 11+); null on older versions, which use a runtime permission. */
    fun accessSettingsIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        else null

    /** The Documents/ios folder as a document URI, for pickers and the file manager. */
    fun folderUri(): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, "primary:Documents/$FOLDER")

    /** Opens a file manager at Documents/ios; false if the device has none that can. */
    fun openInFileManager(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(folderUri(), "vnd.android.document/directory")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Decides where the iPod goes. Touches the disk, so call it off the main thread. */
    fun resolve(context: Context): Layout {
        if (!hasFileAccess(context)) {
            return privateLayout(context, "Allow file access to keep the iPod in Documents/ios. Until then it lives in the app's private storage.")
        }
        val root = sharedRoot()
        if (!root.isDirectory && !root.mkdirs()) {
            return privateLayout(context, "Couldn't create ${root.absolutePath}, so the iPod stays in private storage.")
        }
        val code = PodiumNative.probeStorage(root.absolutePath)
        if (code != 0) {
            return privateLayout(context, "This phone's shared storage can't host the live iPod disk (error $code), so it stays in private storage.")
        }
        val layout = Layout(File(root, "firmware"), File(root, "device"), true, "")
        layout.firmwareDir.mkdirs()
        layout.deviceDir.mkdirs()
        return layout
    }
}

/** The system file picker for several files of any type, opened at Documents/ios. */
class OpenFilesAtDevice : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DeviceStorage.folderUri())
        return intent
    }
}
