package ir.vinor.app

import android.webkit.JavascriptInterface
import android.webkit.WebView

/** پل JS برای همگام‌سازی badge اعلان بین WebView و polling نیتیو */
class VinorNotifyBridge(
    private val helper: VinorNotifyHelper,
    private val webViewProvider: () -> WebView?,
) {
    companion object {
        const val JS_INTERFACE_NAME = "VinorNativeNotify"
    }

    @JavascriptInterface
    fun onUnreadUpdate(count: Int) {
        if (!VinorBridgeGate.isTrustedPage(webViewProvider())) return
        helper.updateBaselineUnread(count.coerceAtLeast(0))
    }
}
