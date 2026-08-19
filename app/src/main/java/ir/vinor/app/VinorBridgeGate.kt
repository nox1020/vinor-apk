package ir.vinor.app

import android.webkit.WebView

/** Gate for @JavascriptInterface / Web permission — only vinor HTTPS main frames. */
object VinorBridgeGate {
    fun isTrustedPage(webView: WebView?): Boolean {
        val url = runCatching { webView?.url?.trim().orEmpty() }.getOrDefault("")
        return isTrustedUrl(url)
    }

    fun isTrustedUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return false
        if (raw.startsWith("about:", ignoreCase = true) ||
            raw.startsWith("data:", ignoreCase = true) ||
            raw.startsWith("chrome:", ignoreCase = true)
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
}
