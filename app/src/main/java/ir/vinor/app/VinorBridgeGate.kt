package ir.vinor.app

import android.webkit.WebView

/** Gate for @JavascriptInterface / Web permission — only vinor HTTPS main frames. */
object VinorBridgeGate {
    fun isTrustedPage(webView: WebView?): Boolean {
        val url = webView?.url?.trim().orEmpty()
        if (url.isEmpty()) return false
        if (url.startsWith("about:", ignoreCase = true) ||
            url.startsWith("data:", ignoreCase = true) ||
            url.startsWith("chrome:", ignoreCase = true)
        ) {
            return false
        }
        return UrlUtils.isTrustedVinorHttps(url)
    }

    fun isTrustedOrigin(origin: String?): Boolean {
        val raw = origin?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return UrlUtils.isTrustedVinorHttps(raw)
    }
}
