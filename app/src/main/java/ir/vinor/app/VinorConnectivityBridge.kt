package ir.vinor.app

import android.webkit.JavascriptInterface
import android.webkit.WebView

/** پل JS — همگام‌سازی وضعیت آنلاین/آفلاین با بنر وب (مثل تلگرام) */
class VinorConnectivityBridge(
    private val isOnlineChecker: () -> Boolean
) {
    companion object {
        const val JS_INTERFACE_NAME = "VinorConnectivity"
        private var lastSyncedOnline: Boolean? = null

        fun syncToWeb(webView: WebView?, online: Boolean) {
            if (webView == null) return
            val flag = if (online) "true" else "false"
            val onlineToast = lastSyncedOnline == false && online
            lastSyncedOnline = online
            val opts = buildString {
                append("{ force: true, source: 'native'")
                if (onlineToast) append(", onlineToast: true")
                if (!online) append(", message: 'اتصال اینترنت قطع است'")
                append(" }")
            }
            webView.evaluateJavascript(
                """
                (function () {
                  try {
                    if (typeof window.vinorExpressSyncConnectivity === 'function') {
                      window.vinorExpressSyncConnectivity($flag, $opts);
                    } else if ($flag && typeof window.vinorExpressProbeConnectivity === 'function') {
                      window.vinorExpressProbeConnectivity({ onlineToast: ${if (onlineToast) "true" else "false"} });
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
