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
            val online = state != VinorNetState.OFFLINE
            val flag = if (online) "true" else "false"
            val onlineToast = lastSynced == VinorNetState.OFFLINE && online
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
                opts.put("message", "اتصال اینترنت قطع است")
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
            VinorSafe.evalJs(webView, js)
        }
    }

    @JavascriptInterface
    fun isOnline(): Boolean = runCatching { stateProvider() != VinorNetState.OFFLINE }.getOrDefault(false)

    @JavascriptInterface
    fun networkState(): String = runCatching { stateProvider().name }.getOrDefault(VinorNetState.OFFLINE.name)
}
