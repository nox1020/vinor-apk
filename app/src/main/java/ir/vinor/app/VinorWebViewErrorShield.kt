package ir.vinor.app

import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream

/**
 * جلوگیری از نمایش صفحهٔ پیش‌فرض WebView/Chromium با متن انگلیسی «offline» و خطاهای داخلی.
 */
object VinorWebViewErrorShield {

    private const val SILENT_HTML =
        """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;background:#111827;height:100%;overflow:hidden}</style></head><body></body></html>"""

    private val silentHtmlBytes = SILENT_HTML.toByteArray(Charsets.UTF_8)

    fun looksLikeChromeErrorPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        return u.contains("chrome-error://") ||
            u.contains("chromewebdata") ||
            u.contains("about:neterror") ||
            u.contains("edge-error://") ||
            u.contains("webview-error") ||
            (u.contains("neterror") && u.startsWith("about:"))
    }

    /** صفحات خنثی بدون محتوای خطا؛ از به‌هم‌خوردن loader/offline جلوگیری می‌شود */
    fun isNeutralPlaceholderUrl(url: String?): Boolean {
        val u = url?.trim()?.lowercase() ?: return false
        if (u == "about:blank" || u.startsWith("about:blank#")) return true
        // محتوای کوتاه برنامه‌ای که با loadDataWithBaseURL گذاشته می‌شود
        if (u.startsWith("data:text/html") && (url?.length ?: 0) < 1800) return true
        return false
    }

    fun clearToSilentPlaceholder(wv: WebView?) {
        if (wv == null) return
        try {
            wv.stopLoading()
            wv.loadDataWithBaseURL(null, SILENT_HTML, "text/html", "utf-8", null)
        } catch (_: Exception) {
        }
    }

    fun silentHtmlResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/html",
            "UTF-8",
            ByteArrayInputStream(silentHtmlBytes)
        )

    fun shouldInterceptChromeErrorMainFrame(request: WebResourceRequest?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (request?.isForMainFrame != true) return false
        return looksLikeChromeErrorPage(request.url?.toString())
    }
}
