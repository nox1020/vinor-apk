package ir.vinor.app

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView

/** پل JS — درخواست بررسی نسخه از سمت وب */
class VinorAppUpdateBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
) {
    companion object {
        const val JS_INTERFACE_NAME = "VinorNativeAppUpdate"
    }

    @JavascriptInterface
    fun refresh() {
        val webView = webViewProvider()
        VinorAppUpdateChecker.check(context.applicationContext, webView)
    }

    @JavascriptInterface
    fun getCachedState(): String {
        val state = VinorAppUpdateChecker.readCached(context.applicationContext)
        return org.json.JSONObject()
            .put("show", state.show)
            .put("version", state.version)
            .put("url", state.url)
            .toString()
    }
}
