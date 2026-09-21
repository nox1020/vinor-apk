package ir.vinor.app

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment

/** نصب پل‌های JS روی WebView — قبلاً کلاس‌ها وجود داشتند ولی هرگز addJavascriptInterface صدا زده نمی‌شد. */
object VinorWebViewBridges {

    @SuppressLint("JavascriptInterface")
    fun attach(fragment: Fragment, webView: WebView) {
        val ctx = fragment.requireContext().applicationContext
        webView.addJavascriptInterface(
            VinorCacheBridge(ctx) { webView },
            VinorCacheBridge.JS_INTERFACE_NAME,
        )
        webView.addJavascriptInterface(
            VinorConnectivityBridge { VinorNetworkStatus.current(ctx) },
            VinorConnectivityBridge.JS_INTERFACE_NAME,
        )
        val act = fragment.activity as? ComponentActivity
        if (act != null) {
            webView.addJavascriptInterface(
                VinorDownloadBridge(act) { webView },
                VinorDownloadBridge.JS_INTERFACE_NAME,
            )
            webView.addJavascriptInterface(
                VinorAppUpdateBridge(ctx) { webView },
                VinorAppUpdateBridge.JS_INTERFACE_NAME,
            )
        }
        val host = fragment.activity as? MainActivity
        host?.notifyHelperOrNull()?.let { helper ->
            webView.addJavascriptInterface(
                VinorNotifyBridge(helper),
                VinorNotifyBridge.JS_INTERFACE_NAME,
            )
        }
        webView.addJavascriptInterface(
            VinorShellBridge(
                onReady = {},
                onLogout = { host?.logoutFromDrawer() },
            ),
            VinorShellBridge.JS_INTERFACE_NAME,
        )
    }
}
