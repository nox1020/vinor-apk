package ir.vinor.app

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView

/** پل JS — فقط نمایش state نیتیو و actionهای مجاز. URL دلخواه از JS پذیرفته نمی‌شود. */
class VinorAppUpdateBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
) {
    companion object {
        const val JS_INTERFACE_NAME = "VinorNativeAppUpdate"
    }

    @JavascriptInterface
    fun refresh() {
        VinorSafe.onMain {
            val webView = webViewProvider()
            if (!VinorBridgeGate.isTrustedPage(webView)) return@onMain
            VinorAppUpdateController.check(context.applicationContext, webView, force = true)
        }
    }

    @JavascriptInterface
    fun getCachedState(): String {
        return runCatching { VinorAppUpdateController.stateJson(context.applicationContext) }
            .getOrDefault("{}")
    }

    @JavascriptInterface
    fun getDownloadState(): String =
        runCatching { VinorAppUpdateController.stateJson(context.applicationContext) }
            .getOrDefault("{}")

    @JavascriptInterface
    fun ensureDownload(url: String, version: String) {
        VinorSafe.onMain {
            val webView = webViewProvider()
            if (!VinorBridgeGate.isTrustedPage(webView)) return@onMain
            // url/version از JS نادیده گرفته می‌شود
            VinorApkUpdateInstaller.ensureDownload(
                context.applicationContext,
                url = "",
                version = "",
                webView = webView,
            )
        }
    }

    @JavascriptInterface
    fun retryDownload() {
        VinorSafe.onMain {
            val webView = webViewProvider()
            if (!VinorBridgeGate.isTrustedPage(webView)) return@onMain
            VinorAppUpdateController.attachWebView(webView)
            VinorAppUpdateController.retryDownload(context.applicationContext)
        }
    }

    @JavascriptInterface
    fun installUpdate(): Boolean {
        val webView = webViewProvider()
        if (!VinorBridgeGate.isTrustedPage(webView)) return false
        val state = VinorAppUpdateController.read(context.applicationContext)
        if (state.status != VinorAppUpdateStatus.READY) return false
        VinorSafe.onMain {
            if (!VinorBridgeGate.isTrustedPage(webViewProvider())) return@onMain
            VinorApkUpdateInstaller.install(context)
        }
        return true
    }
}
