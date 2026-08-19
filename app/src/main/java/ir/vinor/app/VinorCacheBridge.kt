package ir.vinor.app

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** پل JS برای وضعیت شبکه، صف آفلاین و متادیتای کش. */
class VinorCacheBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
) {
    companion object {
        const val JS_INTERFACE_NAME = "VinorNativeCache"

        fun dispatchSync(webView: WebView?, queue: JSONArray, lastCachedAt: Long) {
            if (webView == null) return
            val js = """
                (function(){
                  try {
                    var q = $queue;
                    window.dispatchEvent(new CustomEvent('vinor:offline-sync', {
                      detail: { queue: q, lastCachedAt: $lastCachedAt }
                    }));
                    if (typeof window.vinorReplayOfflineQueue === 'function') {
                      window.vinorReplayOfflineQueue(q);
                    }
                  } catch (_) {}
                })();
            """.trimIndent()
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

    @JavascriptInterface
    fun networkState(): String = VinorNetworkStatus.current(context).name

    @JavascriptInterface
    fun isOnline(): Boolean = VinorNetworkStatus.isOnline(context)

    @JavascriptInterface
    fun lastCachedAt(): Long = VinorWebOfflineSession.lastCachedAt(context)

    @JavascriptInterface
    fun enqueue(type: String?, url: String?, body: String?): String {
        val wv = webViewProvider()
        if (!VinorBridgeGate.isTrustedPage(wv)) {
            return JSONObject().put("ok", false).put("message", "untrusted").toString()
        }
        return VinorOfflineQueue.enqueue(
            context.applicationContext,
            type = type?.trim().orEmpty().ifBlank { "post" },
            url = url?.trim().orEmpty(),
            method = "POST",
            body = body,
        ).toString()
    }

    @JavascriptInterface
    fun pendingQueue(): String = VinorOfflineQueue.snapshotJson(context.applicationContext).toString()

    @JavascriptInterface
    fun ackQueueItem(id: String?) {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        if (!VinorBridgeGate.isTrustedPage(webViewProvider())) return
        VinorOfflineQueue.ack(context.applicationContext, trimmed)
    }

    @JavascriptInterface
    fun failQueueItem(id: String?, error: String?) {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        if (!VinorBridgeGate.isTrustedPage(webViewProvider())) return
        VinorOfflineQueue.fail(context.applicationContext, trimmed, error.orEmpty())
    }
}
