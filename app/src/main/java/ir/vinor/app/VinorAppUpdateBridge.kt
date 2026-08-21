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
        VinorSafe.onMain {
            val webView = webViewProvider()
            if (!VinorBridgeGate.isTrustedPage(webView)) return@onMain
            VinorAppUpdateChecker.check(context.applicationContext, webView, force = true)
        }
    }

    @JavascriptInterface
    fun getCachedState(): String {
        return runCatching {
            val state = VinorAppUpdateChecker.readCached(context.applicationContext)
            org.json.JSONObject()
                .put("show", state.show)
                .put("version", state.version)
                .put("url", state.url)
                .put("sourceUrl", state.sourceUrl)
                .toString()
        }.getOrDefault("{}")
    }

    @JavascriptInterface
    fun getDownloadState(): String =
        runCatching { VinorApkUpdateInstaller.stateJson(context.applicationContext) }
            .getOrDefault("{}")

    @JavascriptInterface
    fun ensureDownload(url: String, version: String) {
        VinorSafe.onMain {
            val webView = webViewProvider()
            if (!VinorBridgeGate.isTrustedPage(webView)) return@onMain
            val cached = VinorAppUpdateChecker.readCached(context.applicationContext)
            VinorApkUpdateInstaller.ensureDownload(
                context.applicationContext,
                url,
                version,
                webView,
                cached.sourceUrl,
            )
        }
    }

    @JavascriptInterface
    fun installUpdate(): Boolean {
        VinorSafe.onMain {
            if (!VinorBridgeGate.isTrustedPage(webViewProvider())) return@onMain
            VinorApkUpdateInstaller.install(context)
        }
        return true
    }
}
