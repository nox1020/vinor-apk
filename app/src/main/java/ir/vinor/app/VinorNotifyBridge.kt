package ir.vinor.app

import android.webkit.JavascriptInterface

/** پل JS برای همگام‌سازی badge اعلان بین WebView و polling نیتیو */
class VinorNotifyBridge(
    private val helper: VinorNotifyHelper
) {
    companion object {
        const val JS_INTERFACE_NAME = "VinorNativeNotify"
    }

    @JavascriptInterface
    fun onUnreadUpdate(count: Int) {
        helper.updateBaselineUnread(count.coerceAtLeast(0))
    }
}
