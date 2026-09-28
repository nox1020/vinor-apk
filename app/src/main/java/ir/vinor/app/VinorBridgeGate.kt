package ir.vinor.app

import android.webkit.WebView
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * Gate for @JavascriptInterface and WebView permissions.
 * Trust is the current main-frame URL of that WebView only — not a process-global last URL.
 * Javascript bridges run off the UI thread, where [WebView.getUrl] is unsafe, so each WebView
 * keeps its own last committed URL.
 */
object VinorBridgeGate {

    sealed class BridgedNav {
        data object Allow : BridgedNav()
        data class Upgrade(val url: String) : BridgedNav()
        data class OpenExternal(val url: String) : BridgedNav()
        data object Block : BridgedNav()
    }

    private val lastUrls: MutableMap<Any, String> =
        Collections.synchronizedMap(WeakHashMap())

    fun rememberUrl(webView: WebView?, url: String?) {
        if (webView == null) return
        remember(webView, url)
    }

    fun forget(webView: WebView?) {
        if (webView == null) return
        lastUrls.remove(webView)
    }

    fun isTrustedPage(webView: WebView?): Boolean {
        if (webView == null) return false
        val onMain = runCatching { VinorSafe.isMainThread() }.getOrDefault(false)
        if (!onMain) return isTrustedFor(webView, liveUrl = null, liveReadable = false)
        val read = runCatching { webView.url }
        if (read.isFailure) return isTrustedFor(webView, liveUrl = null, liveReadable = false)
        return isTrustedFor(webView, read.getOrNull(), liveReadable = true)
    }

    /**
     * Main-frame navigation for a WebView that already has Javascript bridges.
     * Only [UrlUtils.isTrustedVinorHttps] may stay in that WebView. Cleartext Vinor
     * is upgraded to https. Other http(s) URLs leave the WebView. Other schemes are blocked.
     */
    fun navigationForBridgedWebView(url: String?): BridgedNav {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return BridgedNav.Block
        if (isTrustedUrl(raw)) return BridgedNav.Allow
        if (raw.startsWith("http://", ignoreCase = true)) {
            val https = "https://" + raw.substring(7)
            if (isTrustedUrl(https)) return BridgedNav.Upgrade(https)
            return BridgedNav.OpenExternal(raw)
        }
        if (raw.startsWith("https://", ignoreCase = true)) return BridgedNav.OpenExternal(raw)
        return BridgedNav.Block
    }

    fun isTrustedUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return false
        if (raw.startsWith("about:", ignoreCase = true) ||
            raw.startsWith("data:", ignoreCase = true) ||
            raw.startsWith("chrome:", ignoreCase = true) ||
            raw.startsWith("blob:", ignoreCase = true) ||
            raw.startsWith("javascript:", ignoreCase = true) ||
            raw.startsWith("file:", ignoreCase = true)
        ) {
            return false
        }
        return UrlUtils.isTrustedVinorHttps(raw)
    }

    fun isTrustedOrigin(origin: String?): Boolean {
        val raw = origin?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return UrlUtils.isTrustedVinorHttps(raw)
    }

    internal fun remember(key: Any, url: String?) {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty() || isIgnorableLiveUrl(trimmed)) return
        lastUrls[key] = trimmed
    }

    internal fun isTrustedFor(key: Any?, liveUrl: String?, liveReadable: Boolean): Boolean {
        if (key == null) return false
        return decideTrust(liveUrl, lastUrls[key], liveReadable)
    }

    internal fun decideTrust(liveUrl: String?, rememberedUrl: String?, liveReadable: Boolean): Boolean {
        if (liveReadable) {
            val live = liveUrl?.trim().orEmpty()
            // about:blank / chrome error pages are not a new document origin.
            if (live.isNotEmpty() && !isIgnorableLiveUrl(live)) return isTrustedUrl(live)
        }
        return isTrustedUrl(rememberedUrl)
    }

    internal fun resetForTests() {
        lastUrls.clear()
    }

    /** Placeholder documents that must not erase or replace this WebView's last real URL. */
    private fun isIgnorableLiveUrl(url: String): Boolean {
        val u = url.trim().lowercase(Locale.US)
        if (u == "about:blank" || u.startsWith("about:blank#") || u.startsWith("about:blank?")) return true
        if (u.contains("chrome-error://") || u.contains("chromewebdata") || u.contains("edge-error://")) return true
        if (u.startsWith("about:neterror")) return true
        return false
    }
}
