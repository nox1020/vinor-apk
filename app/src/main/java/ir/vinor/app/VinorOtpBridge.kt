package ir.vinor.app

import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatActivity

/**
 * پل JS ↔ OTP native (soft-nav + restart Retriever هنگام ارسال/دریافت OTP).
 */
class VinorOtpBridge(
    private val activity: AppCompatActivity,
    private val helperProvider: () -> WebLoginOtpSmsHelper?,
) {
    @JavascriptInterface
    fun onUrlChanged(url: String?) {
        activity.runOnUiThread {
            if (!VinorSafe.isActivityAlive(activity)) return@runOnUiThread
            val helper = helperProvider() ?: return@runOnUiThread
            helper.onUrlChanged(url)
            helper.onPageContentReady()
        }
    }

    /** صفحه OTP یا بعد از درخواست کد — Retriever را دوباره فعال کن. */
    @JavascriptInterface
    fun restartRetriever() {
        activity.runOnUiThread {
            if (!VinorSafe.isActivityAlive(activity)) return@runOnUiThread
            helperProvider()?.restartSmsRetriever()
        }
    }

    @JavascriptInterface
    fun onOtpPageReady() {
        activity.runOnUiThread {
            if (!VinorSafe.isActivityAlive(activity)) return@runOnUiThread
            helperProvider()?.onOtpPageReady()
        }
    }
}
