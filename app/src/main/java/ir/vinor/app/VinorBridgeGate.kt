package ir.vinor.app

import android.webkit.WebView

/** Gate for @JavascriptInterface / Web permission — only vinor HTTPS main frames. */
object VinorBridgeGate {
    @Volatile
    private var lastKnownUrl: String = ""

    fun rememberUrl(url: String?) {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isNotEmpty()) lastKnownUrl = trimmed
    }

    fun isTrustedPage(webView: WebView?): Boolean {
        val live = if (VinorSafe.isMainThread()) {
            runCatching { webView?.url?.trim().orEmpty() }.getOrDefault("")
        } else {
            ""
        }
        if (isTrustedUrl(live)) return true
        return isTrustedUrl(lastKnownUrl)
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
