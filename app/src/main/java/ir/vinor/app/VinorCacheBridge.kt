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
            VinorSafe.evalJs(webView, js)
        }
    }

    @JavascriptInterface
    fun networkState(): String {
        if (!trusted()) return VinorNetState.OFFLINE.name
        return runCatching { VinorNetworkStatus.current(context).name }.getOrDefault(VinorNetState.OFFLINE.name)
    }

    @JavascriptInterface
    fun isOnline(): Boolean {
        if (!trusted()) return false
        return runCatching { VinorNetworkStatus.isOnline(context) }.getOrDefault(false)
    }

    @JavascriptInterface
    fun lastCachedAt(): Long {
        if (!trusted()) return 0L
        return runCatching { VinorWebOfflineSession.lastCachedAt(context) }.getOrDefault(0L)
    }

    @JavascriptInterface
    fun enqueue(type: String?, url: String?, body: String?): String {
        return runCatching {
            if (!trusted()) {
                return@runCatching JSONObject().put("ok", false).put("message", "untrusted").toString()
            }
            VinorOfflineQueue.enqueue(
                context.applicationContext,
                type = type?.trim().orEmpty().ifBlank { "post" },
                url = url?.trim().orEmpty(),
                method = "POST",
                body = body,
            ).toString()
        }.getOrElse {
            JSONObject().put("ok", false).put("queued", false).put("offline", true).toString()
        }
    }

    @JavascriptInterface
    fun pendingQueue(): String {
        if (!trusted()) return "[]"
        return runCatching {
            VinorOfflineQueue.snapshotJson(context.applicationContext).toString()
        }.getOrDefault("[]")
    }

    @JavascriptInterface
    fun ackQueueItem(id: String?) {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        if (!trusted()) return
        runCatching { VinorOfflineQueue.ack(context.applicationContext, trimmed) }
    }

    @JavascriptInterface
    fun failQueueItem(id: String?, error: String?) {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        if (!trusted()) return
        runCatching { VinorOfflineQueue.fail(context.applicationContext, trimmed, error.orEmpty()) }
    }

    private fun trusted(): Boolean = VinorBridgeGate.isTrustedPage(webViewProvider())
}
