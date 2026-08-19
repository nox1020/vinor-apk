package ir.vinor.app

import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/** پل JS — همگام‌سازی وضعیت آنلاین/آفلاین/محدود با بنر وب */
class VinorConnectivityBridge(
    private val stateProvider: () -> VinorNetState,
) {
    companion object {
        const val JS_INTERFACE_NAME = "VinorConnectivity"
        private var lastSynced: VinorNetState? = null

        fun syncToWeb(
            webView: WebView?,
            state: VinorNetState,
            lastCachedAt: Long = 0L,
        ) {
            if (webView == null) return
            val online = state == VinorNetState.ONLINE
            val flag = if (online) "true" else "false"
            val onlineToast = lastSynced == VinorNetState.OFFLINE && online ||
                lastSynced == VinorNetState.LIMITED && online
            lastSynced = state
            val detail = JSONObject()
                .put("online", online)
                .put("status", state.name)
                .put("lastCachedAt", lastCachedAt)
            val opts = JSONObject()
                .put("force", true)
                .put("source", "native")
                .put("status", state.name)
                .put("lastCachedAt", lastCachedAt)
            if (onlineToast) opts.put("onlineToast", true)
            if (!online) {
                val msg = if (state == VinorNetState.LIMITED) {
                    "ارتباط محدود است؛ آخرین اطلاعات ذخیره‌شده نمایش داده می‌شود"
                } else {
                    "اتصال اینترنت قطع است"
                }
                opts.put("message", msg)
            }
            val js = """
                (function () {
                  try {
                    window.dispatchEvent(new CustomEvent('vinor:network', { detail: $detail }));
                    if (typeof window.vinorExpressSyncConnectivity === 'function') {
                      window.vinorExpressSyncConnectivity($flag, $opts);
                    } else if ($flag && typeof window.vinorExpressProbeConnectivity === 'function') {
                      window.vinorExpressProbeConnectivity({ onlineToast: ${if (onlineToast) "true" else "false"} });
                    }
                  } catch (_) {}
                })();
            """.trimIndent()
            webView.post {
                try {
                    webView.evaluateJavascript(js, null)
                } catch (_: Throwable) {
                }
            }
        }
    }

    @JavascriptInterface
    fun isOnline(): Boolean = runCatching { stateProvider() == VinorNetState.ONLINE }.getOrDefault(false)

    @JavascriptInterface
    fun networkState(): String = runCatching { stateProvider().name }.getOrDefault(VinorNetState.OFFLINE.name)
}
