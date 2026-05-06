package uk.nktnet.webviewkiosk.utils.webview.interfaces

import android.app.Activity
import android.content.Context
import android.os.Environment
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import uk.nktnet.webviewkiosk.config.UserSettings
import uk.nktnet.webviewkiosk.managers.CustomNotificationManager
import uk.nktnet.webviewkiosk.managers.ToastManager
import uk.nktnet.webviewkiosk.utils.webview.handlers.uploadModelToBambu
import java.io.File
import java.io.FileOutputStream

// https://proandroiddev.com/blob-downloads-not-working-in-android-web-view-heres-the-real-fix-243144a2a426
class BlobInterface(private val context: Context) {
    companion object {
        const val NAME = "WebviewKioskBlobInterface"

        @JvmStatic
        private var isActive = true

        @Suppress("unused")
        @JvmStatic
        fun setIsActive(value: Boolean) {
            isActive = value
        }

        const val JS_BLOB_HOOK = """
            (function() {
                if (window.__blobHookInstalled) return;
                window.__blobHookInstalled = true;

                const orig = URL.createObjectURL;
                URL.createObjectURL = function(blob) {
                    window._lastBlob = blob;
                    try { ${NAME}.onDownloadPreparing(); } catch(e) {}
                    return orig.call(URL, blob);
                };
            })();
        """

        private const val MODEL_UPLOAD_CONFIRM_WINDOW_MS = 120_000L

        @Volatile
        private var modelUploadAllowedUntilMs = 0L

        @Volatile
        private var modelUploadFilename: String? = null

        @JvmStatic
        fun prepareModelUpload(filename: String) {
            modelUploadFilename = sanitizeDownloadFilename(filename)
            modelUploadAllowedUntilMs = System.currentTimeMillis() + MODEL_UPLOAD_CONFIRM_WINDOW_MS
        }

        private fun consumePreparedModelUpload(filename: String): Boolean {
            val safeName = sanitizeDownloadFilename(filename)
            val allowed = System.currentTimeMillis() <= modelUploadAllowedUntilMs &&
                safeName == modelUploadFilename
            if (allowed) {
                modelUploadAllowedUntilMs = 0L
                modelUploadFilename = null
            }
            return allowed
        }

        private fun sanitizeDownloadFilename(name: String): String {
            val base = File(name.replace("\\", "/")).name
                .replace("\"", "_")
                .replace("\r", "_")
                .replace("\n", "_")
                .trim('.', ' ', '_', '-')
            return base.ifBlank { "download.bin" }
        }
    }

    @Suppress("unused")
    @JavascriptInterface
    fun onDownloadPreparing() {
        (context as? Activity)?.runOnUiThread {
            Toast.makeText(context, "Preparing file…", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun error(message: String?) {
        ToastManager.show(context, message ?: "Unknown error")
    }

    @Suppress("unused")
    @JavascriptInterface
    fun download(base64: String?, mimeType: String?, filename: String) {
        if (!isActive || base64 == null) {
            return
        }

        try {
            val cleanBase64 = base64.substringAfter(',')
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            saveFile(filename, bytes)
        } catch (e: Exception) {
            ToastManager.show(context, "Failed: ${e.message}")
        }
    }

    @Suppress("unused")
    @JavascriptInterface
    fun uploadModel(base64: String?, mimeType: String?, filename: String) {
        if (!isActive || base64 == null) {
            return
        }
        if (!consumePreparedModelUpload(filename)) {
            ToastManager.show(context, "Model upload needs confirmation")
            return
        }

        try {
            val cleanBase64 = base64.substringAfter(',')
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            uploadModelToBambu(context, sanitizeDownloadFilename(filename), bytes, mimeType)
        } catch (e: Exception) {
            ToastManager.show(context, "Send to Bambu failed: ${e.message}")
        }
    }

    private fun saveFile(name: String, bytes: ByteArray) {
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val file = File(downloads, sanitizeDownloadFilename(name))
        FileOutputStream(file).use {
            it.write(bytes)
        }
        ToastManager.show(context, "$name downloaded")

        val userSettings = UserSettings(context)
        if (userSettings.allowNotifications) {
            CustomNotificationManager.sendBlobDownloadNotification(context, file)
        }
    }
}
