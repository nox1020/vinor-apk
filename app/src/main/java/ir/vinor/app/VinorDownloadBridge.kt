package ir.vinor.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.Locale

/**
 * دانلود فایل در WebView — جایگزین blob + <a download> که در اندروید کار نمی‌کند.
 */
class VinorDownloadBridge(
    private val activity: ComponentActivity,
    private val webViewProvider: () -> WebView? = { WebViewProvider.peek() },
) {
    @JavascriptInterface
    fun isNativeAvailable(): Boolean = true

    @JavascriptInterface
    fun downloadUrl(url: String, filename: String) {
        activity.runOnUiThread {
            if (!VinorBridgeGate.isTrustedPage(webViewProvider())) {
                Log.w(TAG, "downloadUrl blocked — untrusted page")
                return@runOnUiThread
            }
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

            if (!UrlUtils.isSafePublicDownloadUrl(absUrl)) {
                Log.w(TAG, "Blocked unsafe download: $absUrl")
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
                    // Cookie only for allowlisted public media/APK paths
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
            val absolute = when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) ->
                    "https://" + trimmed.substring(7)
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("/") -> "https://vinor.ir$trimmed"
                else -> "https://vinor.ir/$trimmed"
            }
            return if (UrlUtils.isTrustedVinorHttps(absolute)) absolute else null
        }

        fun sanitizeFilename(name: String): String {
            val cleaned = name
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), "_")
                .trim('.', '_', ' ')
            return cleaned.ifBlank { "vinor_download" }
        }

        /** نام فایل دانلود APK — vinor-1.0.253.apk */
        fun apkDownloadFilename(url: String, version: String? = null): String {
            val ver = version?.trim().orEmpty()
            if (ver.isNotEmpty()) {
                val safe = ver.replace(Regex("[^A-Za-z0-9._\\-]"), "")
                if (safe.isNotEmpty()) return sanitizeFilename("vinor-$safe.apk")
            }
            val last = Uri.parse(url).lastPathSegment?.trim().orEmpty()
            if (last.endsWith(".apk", ignoreCase = true) &&
                last.startsWith("vinor", ignoreCase = true)
            ) {
                return sanitizeFilename(last)
            }
            return "vinor.apk"
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
                lower.endsWith(".apk") || lower.contains("/d") ->
                    "application/vnd.android.package-archive"
                lower.endsWith(".mp4") -> "video/mp4"
                lower.endsWith(".webm") -> "video/webm"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".pdf") -> "application/pdf"
                else -> null
            }
        }
    }
}
