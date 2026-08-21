package ir.vinor.app

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView

/**
 * محافظ کرش WebView/Activity: CookieManager و WebView فقط روی ترد UI امن‌اند.
 */
object VinorSafe {
    private val main = Handler(Looper.getMainLooper())

    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    fun isActivityAlive(activity: Activity?): Boolean {
        if (activity == null) return false
        if (activity.isFinishing) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) {
            return false
        }
        return true
    }

    fun onMain(block: () -> Unit) {
        if (isMainThread()) {
            runCatching(block)
        } else {
            main.post { runCatching(block) }
        }
    }

    fun evalJs(
        webView: WebView?,
        script: String,
        callback: ValueCallback<String>? = null,
    ) {
        if (webView == null) return
        try {
            webView.post {
                try {
                    webView.evaluateJavascript(script, callback)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun webUrl(webView: WebView?): String? =
        runCatching { webView?.url }.getOrNull()

    fun isWebViewUsable(webView: WebView?): Boolean {
        if (webView == null) return false
        if (!isMainThread()) return false
        return runCatching {
            webView.url
            true
        }.getOrDefault(false)
    }

    /** فقط از ترد UI. در ترد دیگر رشتهٔ خالی برمی‌گردد — هرگز CookieManager را off-thread صدا نزن. */
    fun cookieOrEmpty(url: String): String {
        if (!isMainThread()) return ""
        return runCatching {
            CookieManager.getInstance().getCookie(url)?.trim().orEmpty()
        }.getOrDefault("")
    }
}
