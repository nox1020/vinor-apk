package ir.vinor.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.Locale

/**
 * دانلود فایل در WebView — جایگزین blob + <a download> که در اندروید کار نمی‌کند.
 */
class VinorDownloadBridge(
    private val activity: ComponentActivity,
) {
    @JavascriptInterface
    fun isNativeAvailable(): Boolean = true

    @JavascriptInterface
    fun downloadUrl(url: String, filename: String) {
        activity.runOnUiThread {
            enqueueDownload(activity, url, filename, showToast = false)
        }
    }

    companion object {
        private const val TAG = "VinorDownloadBridge"
        const val JS_INTERFACE_NAME = "VinorNativeDownload"

        fun enqueueDownload(
            context: Context,
            url: String,
            filename: String?,
            showToast: Boolean = false,
        ): Long {
            val absUrl = resolveAbsoluteUrl(url) ?: run {
                Log.w(TAG, "Invalid download url: $url")
                if (showToast && context is ComponentActivity) {
                    context.runOnUiThread {
                        Toast.makeText(context, "آدرس دانلود نامعتبر است", Toast.LENGTH_SHORT).show()
                    }
                }
                return -1L
            }

            if (!UrlUtils.isVinorDomain(absUrl)) {
                Log.w(TAG, "Blocked non-vinor download: $absUrl")
                return -1L
            }

            val safeName = sanitizeFilename(filename?.takeIf { it.isNotBlank() } ?: guessFilename(absUrl))
            return try {
                val request = DownloadManager.Request(Uri.parse(absUrl)).apply {
                    setTitle(safeName)
                    setDescription("وینور — دانلود رسانه")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                    setMimeType(guessMimeType(absUrl, safeName))
                    addRequestHeader("User-Agent", VinorUserAgent.forHttpRequest(context))
                    CookieManager.getInstance().getCookie(absUrl)?.takeIf { it.isNotBlank() }?.let { cookie ->
                        addRequestHeader("Cookie", cookie)
                    }
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
                }
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val id = dm.enqueue(request)
                if (showToast && context is ComponentActivity) {
                    context.runOnUiThread {
                        Toast.makeText(context, "دانلود در پوشه Downloads", Toast.LENGTH_SHORT).show()
                    }
                }
                id
            } catch (e: Exception) {
                Log.e(TAG, "enqueue failed: $absUrl", e)
                if (showToast && context is ComponentActivity) {
                    context.runOnUiThread {
                        Toast.makeText(context, "خطا در شروع دانلود", Toast.LENGTH_SHORT).show()
                    }
                }
                -1L
            }
        }

        fun resolveAbsoluteUrl(url: String): String? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true)
            ) {
                return trimmed
            }
            if (trimmed.startsWith("//")) {
                return "https:$trimmed"
            }
            if (trimmed.startsWith("/")) {
                return "https://vinor.ir$trimmed"
            }
            return "https://vinor.ir/$trimmed"
        }

        fun sanitizeFilename(name: String): String {
            val cleaned = name
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), "_")
                .trim('.', '_', ' ')
            return cleaned.ifBlank { "vinor_download" }
        }

        private fun guessFilename(url: String): String {
            val parsed = Uri.parse(url)
            val last = parsed.lastPathSegment?.takeIf { it.isNotBlank() }
            if (!last.isNullOrBlank() && last.contains('.')) return last
            return URLUtil.guessFileName(url, null, guessMimeType(url, last)) ?: "vinor_download"
        }

        private fun guessMimeType(url: String, filename: String?): String? {
            val lower = (filename ?: url).lowercase(Locale.US)
            return when {
                lower.endsWith(".mp4") -> "video/mp4"
                lower.endsWith(".webm") -> "video/webm"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                else -> null
            }
        }
    }
}
