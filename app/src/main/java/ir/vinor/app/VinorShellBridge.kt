package ir.vinor.app

import android.webkit.JavascriptInterface
import android.webkit.WebView

/** پل JS — اعلام آماده‌شدن اسکلت/محتوای وب به MainActivity */
class VinorShellBridge(
    private val webViewProvider: () -> WebView?,
    private val onReady: () -> Unit,
    private val onLogout: () -> Unit = {},
) {
    companion object {
        const val JS_INTERFACE_NAME = "VinorNativeShell"
    }

    @JavascriptInterface
    fun onAppReady() {
        if (!VinorBridgeGate.isTrustedPage(webViewProvider())) return
        onReady()
    }

    @JavascriptInterface
    fun onPartnerLogout() {
        if (!VinorBridgeGate.isTrustedPage(webViewProvider())) return
        onLogout()
    }
}
