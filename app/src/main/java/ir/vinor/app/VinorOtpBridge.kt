package ir.vinor.app

import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatActivity

/** پل JS برای soft-nav (pushState) — WebView onPageFinished برای OTP کافی نیست. */
class VinorOtpBridge(
    private val activity: AppCompatActivity,
    private val helperProvider: () -> WebLoginOtpSmsHelper?,
) {
    @JavascriptInterface
    fun onUrlChanged(url: String?) {
        activity.runOnUiThread {
            val helper = helperProvider() ?: return@runOnUiThread
            helper.onUrlChanged(url)
            helper.onPageContentReady()
        }
    }
}
