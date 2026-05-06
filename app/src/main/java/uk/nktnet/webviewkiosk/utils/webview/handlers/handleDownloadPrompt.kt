package uk.nktnet.webviewkiosk.utils.webview.handlers

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.graphics.Typeface
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import org.json.JSONObject
import uk.nktnet.webviewkiosk.config.Constants
import uk.nktnet.webviewkiosk.config.UserSettings
import uk.nktnet.webviewkiosk.config.UserSettingsKeys
import uk.nktnet.webviewkiosk.managers.ToastManager
import uk.nktnet.webviewkiosk.states.UserInteractionStateSingleton
import uk.nktnet.webviewkiosk.utils.extractFileNameFromContentDisposition
import uk.nktnet.webviewkiosk.utils.getDownloadLocation
import uk.nktnet.webviewkiosk.utils.handleKeyEvent
import uk.nktnet.webviewkiosk.utils.webview.interfaces.BlobInterface
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

@SuppressLint("SetTextI18n")
fun handleDownloadPrompt(
    context: Context,
    webView: WebView,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?
) {
    val userSettings = UserSettings(context)
    if (!userSettings.allowFileDownload) {
        ToastManager.show(
            context,
            "Download is disabled in settings (${UserSettingsKeys.WebEngine.ALLOW_FILE_DOWNLOAD})"
        )
        return
    }

    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(60, 60, 60, 30)
    }

    val titleView = TextView(context).apply {
        text = "Download File"
        textSize = 25f
        setPadding(0, 0, 0, 20)
    }
    layout.addView(titleView)

    val infoText = TextView(context).apply {
        text = getDownloadLocation()
        textSize = 12f
        setTypeface(typeface, Typeface.ITALIC)
        setPadding(10, 10, 10, 0)
    }
    layout.addView(infoText)

    val uri = url.toUri()

    val suggestedName = when {
        !contentDisposition.isNullOrBlank() -> {
            extractFileNameFromContentDisposition(contentDisposition)
        }
        uri.scheme == "blob" -> {
            generateBlobFilename(webView.url, mimeType)
        }
        else -> {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }
    }

    val editText = EditText(context).apply {
        setText(suggestedName)
        setPadding(10, 10, 10, 35)
    }
    layout.addView(editText)

    val buttonsLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        setPadding(0, 50, 0, 0)
    }

    val dialog = AlertDialog.Builder(context)
        .setView(layout)
        .setOnCancelListener {
            UserInteractionStateSingleton.onUserInteraction()
        }
        .setOnDismissListener {
            UserInteractionStateSingleton.onUserInteraction()
        }
        .show()

    val cancelButton = Button(context).apply { text = "Cancel" }
    cancelButton.setOnClickListener {
        UserInteractionStateSingleton.onUserInteraction()
        dialog.dismiss()
    }

    val downloadButton = Button(context).apply { text = "Download" }
    downloadButton.setOnClickListener {
        try {
            UserInteractionStateSingleton.onUserInteraction()
            val filename = editText.text.toString()

            if (uri.scheme == "blob") {
                fetchBlob(webView, url, mimeType, filename)
            } else {
                downloadNormal(
                    context = context,
                    url = url,
                    userAgent = userAgent,
                    mimeType = mimeType,
                    filename = filename
                )
            }

            dialog.dismiss()
            ToastManager.show(context, "Starting download for $filename")
        } catch (e: Exception) {
            Log.e(Constants.APP_SCHEME, "Download failed", e)
            ToastManager.show(context, "Error: ${e.message}")
        }
    }

    val uploadButton = Button(context).apply { text = "发送到拓竹" }
    uploadButton.setOnClickListener {
        try {
            UserInteractionStateSingleton.onUserInteraction()
            val filename = editText.text.toString()

            if (uri.scheme == "blob") {
                BlobInterface.prepareModelUpload(filename)
                fetchBlob(webView, url, mimeType, filename, uploadToBambu = true)
            } else {
                uploadNormalModelToBambu(
                    context = context,
                    url = url,
                    userAgent = userAgent,
                    mimeType = mimeType,
                    filename = filename
                )
            }

            dialog.dismiss()
            ToastManager.show(context, "Sending $filename to Bambu...")
        } catch (e: Exception) {
            Log.e(Constants.APP_SCHEME, "Model upload failed", e)
            ToastManager.show(context, "Error: ${e.message}")
        }
    }

    buttonsLayout.addView(cancelButton)
    buttonsLayout.addView(downloadButton)
    if (isModelFile(suggestedName, url, mimeType)) {
        buttonsLayout.addView(uploadButton)
    }
    layout.addView(buttonsLayout)

    dialog.setOnKeyListener { _, _, event ->
        handleKeyEvent(context, event)
    }
}

fun downloadNormal(
    context: Context,
    url: String,
    userAgent: String?,
    mimeType: String?,
    filename: String
) {
    val request = DownloadManager.Request(url.toUri()).apply {
        setMimeType(mimeType)
        userAgent?.let { addRequestHeader("User-Agent", it) }
        setDescription("Downloading file...")
        setTitle(filename)
        setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            filename,
        )
    }

    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    dm.enqueue(request)
}

// https://proandroiddev.com/blob-downloads-not-working-in-android-web-view-heres-the-real-fix-243144a2a426
private fun fetchBlob(
    webView: WebView,
    blobUrl: String,
    mimeType: String?,
    filename: String,
    uploadToBambu: Boolean = false
) {
    val callback = if (uploadToBambu) "uploadModel" else "download"
    val quotedMimeType = JSONObject.quote(mimeType)
    val quotedFilename = JSONObject.quote(filename)
    val js = """
        (async function() {
            try {
                const blobUrl = ${JSONObject.quote(blobUrl)};
                const response = await fetch(blobUrl);
                const blob = await response.blob();
                const reader = new FileReader();
                reader.onloadend = function() {
                    ${BlobInterface.NAME}.$callback(reader.result, $quotedMimeType, $quotedFilename);
                };
                reader.readAsDataURL(blob);
                return;
            } catch(e) {}

            if (window._lastBlob) {
                const reader2 = new FileReader();
                reader2.onloadend = function() {
                    ${BlobInterface.NAME}.$callback(reader2.result, $quotedMimeType, $quotedFilename);
                };
                reader2.readAsDataURL(window._lastBlob);
                return;
            }

            ${BlobInterface.NAME}.error('Blob fetch failed');
        })();
    """.trimIndent()

    webView.evaluateJavascript(js, null)
}

private fun generateBlobFilename(pageUrl: String?, mimeType: String?): String {
    if (pageUrl?.contains("tinkercad.com", ignoreCase = true) == true) {
        return "tinkercad-model.stl"
    }

    val extension = MimeTypeMap.getSingleton()
        .getExtensionFromMimeType(mimeType)
        ?: "bin"
    return "download_${System.currentTimeMillis()}.$extension"
}

private const val BAMBU_MODEL_UPLOAD_URL = "http://192.168.100.1:8080/api/printers/model-jobs"

private val MODEL_EXTENSIONS = setOf(
    "stl",
    "obj",
    "3mf",
    "amf",
    "step",
    "stp",
    "glb",
    "gltf",
)

private fun isModelFile(filename: String?, url: String, mimeType: String?): Boolean {
    val extCandidates = listOfNotNull(
        filename?.substringAfterLast('.', missingDelimiterValue = ""),
        url.substringBefore('?').substringBefore('#').substringAfterLast('.', missingDelimiterValue = "")
    )
    if (extCandidates.any { it.lowercase(Locale.ROOT) in MODEL_EXTENSIONS }) {
        return true
    }

    val normalizedMime = mimeType?.lowercase(Locale.ROOT) ?: return false
    return normalizedMime.startsWith("model/") ||
        normalizedMime.contains("gltf") ||
        normalizedMime.contains("3mf") ||
        normalizedMime.contains("stl") ||
        normalizedMime.contains("step")
}

private fun uploadNormalModelToBambu(
    context: Context,
    url: String,
    userAgent: String?,
    mimeType: String?,
    filename: String
) {
    Thread {
        try {
            val bytes = fetchUrlBytes(url, userAgent)
            uploadModelToBambu(context, filename, bytes, mimeType)
        } catch (e: Exception) {
            Log.e(Constants.APP_SCHEME, "Model upload failed", e)
            ToastManager.show(context, "Send to Bambu failed: ${e.message}")
        }
    }.start()
}

private fun fetchUrlBytes(url: String, userAgent: String?): ByteArray {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        userAgent?.let { setRequestProperty("User-Agent", it) }
        CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
    }

    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("download HTTP $code")
        }
        return connection.inputStream.use { input ->
            ByteArrayOutputStream().use { output ->
                input.copyTo(output)
                output.toByteArray()
            }
        }
    } finally {
        connection.disconnect()
    }
}

fun uploadModelToBambu(
    context: Context,
    filename: String,
    bytes: ByteArray,
    mimeType: String?
) {
    Thread {
        val boundary = "----WebviewKiosk${UUID.randomUUID()}"
        val lineEnd = "\r\n"
        val connection = (URL(BAMBU_MODEL_UPLOAD_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            getBambuToken()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

        try {
            connection.outputStream.use { output ->
                output.write("--$boundary$lineEnd".toByteArray())
                output.write(
                    "Content-Disposition: form-data; name=\"model\"; filename=\"${multipartFilename(filename)}\"$lineEnd"
                        .toByteArray()
                )
                output.write(
                    "Content-Type: ${mimeType ?: "application/octet-stream"}$lineEnd$lineEnd"
                        .toByteArray()
                )
                output.write(bytes)
                output.write(lineEnd.toByteArray())
                output.write("--$boundary--$lineEnd".toByteArray())
            }

            val code = connection.responseCode
            if (code in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                ToastManager.show(context, modelUploadToast(filename, response))
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                ToastManager.show(context, "Send to Bambu failed: HTTP $code${errorMessage(error)}")
            }
        } catch (e: Exception) {
            Log.e(Constants.APP_SCHEME, "Model upload failed", e)
            ToastManager.show(context, "Send to Bambu failed: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }.start()
}

private fun getBambuToken(): String? =
    System.getenv("BAMBU_MODEL_API_TOKEN")
        ?: System.getenv("BAMBU_TOKEN")
        ?: System.getenv("MODEL_JOBS_TOKEN")

private fun errorMessage(error: String?): String =
    error?.takeIf { it.isNotBlank() }?.let { ": ${it.take(120)}" } ?: ""

private fun modelUploadToast(filename: String, response: String): String {
    val json = runCatching { JSONObject(response) }.getOrNull()
    val message = json?.optString("message")?.takeIf { it.isNotBlank() }
    val state = json?.optString("state")?.takeIf { it.isNotBlank() }
    return when {
        message != null -> message
        state == "saved" -> "Saved $filename; print bridge not configured"
        state == "queued" || state == "running" -> "Queued $filename for Bambu"
        else -> "Uploaded $filename"
    }
}

private fun multipartFilename(filename: String): String =
    filename.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")
