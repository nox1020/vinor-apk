package ir.vinor.app

import android.webkit.JavascriptInterface
import android.webkit.WebView

/** پل JS — همگام‌سازی وضعیت آنلاین/آفلاین با بنر وب (مثل تلگرام) */
class VinorConnectivityBridge(
    private val isOnlineChecker: () -> Boolean
) {
    companion object {
        const val JS_INTERFACE_NAME = "VinorConnectivity"

        fun syncToWeb(webView: WebView?, online: Boolean) {
            if (webView == null) return
            val flag = if (online) "true" else "false"
            webView.evaluateJavascript(
                """
                (function () {
                  try {
                    if (typeof window.vinorExpressSyncConnectivity === 'function') {
                      window.vinorExpressSyncConnectivity($flag, { force: true, source: 'native' });
                    }
                  } catch (_) {}
                })();
                """.trimIndent(),
                null
            )
        }
    }

    @JavascriptInterface
    fun isOnline(): Boolean = isOnlineChecker()
}
