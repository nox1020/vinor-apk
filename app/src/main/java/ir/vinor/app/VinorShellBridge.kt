package ir.vinor.app

import android.webkit.JavascriptInterface

/** پل JS — اعلام آماده‌شدن اسکلت/محتوای وب به MainActivity */
class VinorShellBridge(
    private val onReady: () -> Unit,
    private val onLogout: () -> Unit = {}
) {
    companion object {
        const val JS_INTERFACE_NAME = "VinorNativeShell"
    }

    @JavascriptInterface
    fun onAppReady() {
        onReady()
    }

    @JavascriptInterface
    fun onPartnerLogout() {
        onLogout()
    }
}
