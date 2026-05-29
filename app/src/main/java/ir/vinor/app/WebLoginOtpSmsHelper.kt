package ir.vinor.app

import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * خواندن OTP از پیامک هنگام ورود در WebView — فقط SMS Retriever (بدون مجوز پیامک).
 */
class WebLoginOtpSmsHelper(
    private val activity: AppCompatActivity,
    private val webViewProvider: () -> WebView?,
) : DefaultLifecycleObserver {

    private var listening = false
    private var lastInjectedCode: String? = null
    private var smsRetrieverReceiver: OtpSmsAutoFill.SmsRetrieverReceiver? = null

    override fun onDestroy(owner: LifecycleOwner) {
        stopListening()
    }

    fun onUrlChanged(url: String?) {
        if (isOtpLoginUrl(url)) {
            ensureListening()
        } else {
            stopListening()
        }
    }

    private fun isOtpLoginUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = runCatching {
            val u = android.net.Uri.parse(url)
            (u.path ?: "").lowercase()
        }.getOrDefault("")
        return path.contains("/express/partner/login/otp")
    }

    private fun ensureListening() {
        if (listening) return
        listening = true
        lastInjectedCode = null
        registerReceiver()
        OtpSmsAutoFill.startSmsRetriever(activity)
    }

    private fun registerReceiver() {
        unregisterReceiver()
        smsRetrieverReceiver = OtpSmsAutoFill.SmsRetrieverReceiver { code ->
            activity.runOnUiThread { deliverCode(code) }
        }
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
        unregisterReceiver()
    }

    private fun deliverCode(raw: String) {
        val code = OtpSmsAutoFill.normalizeOtpDigits(raw)
        if (code.length != 5) return
        if (code == lastInjectedCode) return
        lastInjectedCode = code
        injectIntoWebView(code)
        Toast.makeText(
            activity,
            activity.getString(R.string.login_otp_auto_filled),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun injectIntoWebView(code: String) {
        val wv = webViewProvider() ?: return
        val safe = code.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function(){
              var c='$safe';
              if (typeof window.vinorFillOtpFromApp === 'function') {
                window.vinorFillOtpFromApp(c);
                return true;
              }
              var boxes = Array.from(document.querySelectorAll('.otp-box'));
              var hidden = document.getElementById('otpHidden');
              var form = document.getElementById('otpForm');
              if (!boxes.length || !hidden) return false;
              var digits = c.replace(/\D/g,'').slice(0,5).split('');
              boxes.forEach(function(b,i){ b.value = digits[i] || ''; });
              hidden.value = digits.join('');
              if (hidden.value.length === 5 && form) form.requestSubmit();
              return true;
            })();
        """.trimIndent()
        wv.post { wv.evaluateJavascript(js, null) }
    }
}
