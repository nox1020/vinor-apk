package ir.vinor.app

import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * خواندن OTP از پیامک هنگام ورود در WebView — فقط SMS Retriever (بدون دیالوگ مجوز).
 */
class WebLoginOtpSmsHelper(
    private val activity: AppCompatActivity,
    private val webViewProvider: () -> WebView?,
) : DefaultLifecycleObserver {

    private var listening = false
    private var lastInjectedCode: String? = null
    private var smsRetrieverReceiver: OtpSmsAutoFill.SmsRetrieverReceiver? = null
    private var retrieverRetries = 0

    init {
        AppSignatureHelper.logHashesForSmsTemplate(activity.applicationContext)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopListening()
    }

    fun onUrlChanged(url: String?) {
        if (isLoginFlowUrl(url)) {
            ensureListening()
        } else {
            stopListening()
        }
    }

    private fun isLoginFlowUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = runCatching {
            android.net.Uri.parse(url).path.orEmpty().lowercase()
        }.getOrDefault("")
        return path.contains("/express/partner/login")
    }

    private fun ensureListening() {
        if (listening) {
            OtpSmsAutoFill.startSmsRetriever(activity)
            return
        }
        listening = true
        lastInjectedCode = null
        retrieverRetries = 0
        registerReceiver()
        OtpSmsAutoFill.startSmsRetriever(activity)
    }

    private fun registerReceiver() {
        unregisterReceiver()
        smsRetrieverReceiver = OtpSmsAutoFill.SmsRetrieverReceiver(
            onCode = { code -> activity.runOnUiThread { deliverCode(code) } },
            onTimeout = {
                activity.runOnUiThread {
                    if (!listening) return@runOnUiThread
                    if (retrieverRetries < MAX_RETRIEVER_RETRIES) {
                        retrieverRetries++
                        android.util.Log.d(TAG, "SmsRetriever retry $retrieverRetries")
                        OtpSmsAutoFill.startSmsRetriever(activity)
                    }
                }
            },
        )
        OtpSmsAutoFill.registerSmsRetrieverReceiver(activity, smsRetrieverReceiver!!)
    }

    private fun unregisterReceiver() {
        smsRetrieverReceiver?.let { OtpSmsAutoFill.unregisterReceiver(activity, it) }
        smsRetrieverReceiver = null
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        lastInjectedCode = null
        retrieverRetries = 0
        unregisterReceiver()
    }

    private fun deliverCode(raw: String) {
        val code = OtpSmsAutoFill.normalizeOtpDigits(raw)
        if (code.length != 5) return
        if (code == lastInjectedCode) return
        lastInjectedCode = code
        injectIntoWebView(code, 0)
        Toast.makeText(
            activity,
            activity.getString(R.string.login_otp_auto_filled),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun injectIntoWebView(code: String, attempt: Int) {
        val wv = webViewProvider() ?: return
        val safe = code.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function(){
              var c='$safe';
              if (typeof window.vinorFillOtpFromApp === 'function') {
                window.vinorFillOtpFromApp(c);
                return 'fn';
              }
              var input = document.getElementById('otpInput');
              var form = document.getElementById('otpForm');
              if (input) {
                input.value = c.replace(/\D/g,'').slice(0,5);
                input.dispatchEvent(new Event('input', { bubbles: true }));
                if (input.value.length === 5 && form) form.requestSubmit();
                return 'input';
              }
              var boxes = Array.from(document.querySelectorAll('.otp-box'));
              var hidden = document.getElementById('otpHidden');
              if (!boxes.length || !hidden) return '';
              var digits = c.replace(/\D/g,'').slice(0,5).split('');
              boxes.forEach(function(b,i){ b.value = digits[i] || ''; });
              hidden.value = digits.join('');
              if (hidden.value.length === 5 && form) form.requestSubmit();
              return 'boxes';
            })();
        """.trimIndent()
        wv.post {
            wv.evaluateJavascript(js) { result ->
                val ok = result != null && result != "null" && result.replace("\"", "").isNotEmpty()
                if (!ok && attempt < MAX_INJECT_RETRIES) {
                    wv.postDelayed({ injectIntoWebView(code, attempt + 1) }, INJECT_RETRY_MS)
                }
            }
        }
    }

    companion object {
        private const val TAG = "WebLoginOtpSms"
        private const val MAX_RETRIEVER_RETRIES = 5
        private const val MAX_INJECT_RETRIES = 15
        private const val INJECT_RETRY_MS = 400L
    }
}
