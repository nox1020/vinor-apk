package ir.vinor.app

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView

/** پل JS — بررسی نسخه، دانلود خودکار و نصب APK */
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
        if (!VinorBridgeGate.isTrustedPage(webView)) return
        VinorAppUpdateChecker.check(context.applicationContext, webView, force = true)
    }

    @JavascriptInterface
    fun getCachedState(): String {
        val state = VinorAppUpdateChecker.readCached(context.applicationContext)
        return org.json.JSONObject()
            .put("show", state.show)
            .put("version", state.version)
            .put("url", state.url)
            .put("sourceUrl", state.sourceUrl)
            .toString()
    }

    @JavascriptInterface
    fun getDownloadState(): String =
        VinorApkUpdateInstaller.stateJson(context.applicationContext)

    @JavascriptInterface
    fun ensureDownload(url: String, version: String) {
        val webView = webViewProvider()
        if (!VinorBridgeGate.isTrustedPage(webView)) return
        val cached = VinorAppUpdateChecker.readCached(context.applicationContext)
        VinorApkUpdateInstaller.ensureDownload(
            context.applicationContext,
            url,
            version,
            webView,
            cached.sourceUrl,
        )
    }

    @JavascriptInterface
    fun installUpdate(): Boolean {
        if (!VinorBridgeGate.isTrustedPage(webViewProvider())) return false
        return VinorApkUpdateInstaller.install(context)
    }
}
