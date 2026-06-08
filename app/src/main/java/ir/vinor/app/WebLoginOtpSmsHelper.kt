package ir.vinor.app

import android.app.Activity
import android.content.Intent
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * خواندن OTP از پیامک هنگام ورود در WebView.
 * SMS Retriever + User Consent (fallback) — بدون مجوز READ_SMS.
 */
class WebLoginOtpSmsHelper(
    private val activity: AppCompatActivity,
    private val webViewProvider: () -> WebView?,
) : DefaultLifecycleObserver {

    private var listening = false
    private var lastInjectedCode: String? = null
    private var smsRetrieverReceiver: OtpSmsAutoFill.SmsRetrieverReceiver? = null
    private var retrieverRetries = 0
    private val consentLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            OtpSmsAutoFill.handleConsentResult(
                OtpSmsAutoFill.SMS_CONSENT_REQUEST_CODE,
                result.resultCode,
                result.data,
            ) { code -> activity.runOnUiThread { deliverCode(code) } }
        }

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

    /** از مرحلهٔ شماره تا OTP — پیامک ممکن است قبل از لود کامل صفحهٔ OTP برسد. */
    private fun isLoginFlowUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = runCatching {
            android.net.Uri.parse(url).path.orEmpty().lowercase()
        }.getOrDefault("")
        return path.contains("/express/partner/login")
    }

    private fun ensureListening() {
        if (listening) {
            restartRetrieverIfNeeded()
            return
        }
        listening = true
        lastInjectedCode = null
        retrieverRetries = 0
        registerReceiver()
        startBothRetrievers()
    }

    private fun registerReceiver() {
        unregisterReceiver()
        smsRetrieverReceiver = OtpSmsAutoFill.SmsRetrieverReceiver(
            onCode = { code -> activity.runOnUiThread { deliverCode(code) } },
            onConsentRequired = { consentIntent ->
                activity.runOnUiThread {
                    try {
                        consentLauncher.launch(consentIntent)
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "SmsUserConsent launch failed", e)
                    }
                }
            },
            onTimeout = {
                activity.runOnUiThread {
                    if (!listening) return@runOnUiThread
                    if (retrieverRetries < MAX_RETRIEVER_RETRIES) {
                        retrieverRetries++
                        android.util.Log.d(TAG, "SmsRetriever retry $retrieverRetries")
                        OtpSmsAutoFill.startSmsRetriever(activity)
                    } else {
                        android.util.Log.d(TAG, "SmsRetriever exhausted — starting User Consent")
                        OtpSmsAutoFill.startSmsUserConsent(activity)
                    }
                }
            },
        )
        OtpSmsAutoFill.registerSmsRetrieverReceiver(activity, smsRetrieverReceiver!!)
    }

    private fun startBothRetrievers() {
        OtpSmsAutoFill.startSmsRetriever(activity)
        OtpSmsAutoFill.startSmsUserConsent(activity)
    }

    private fun restartRetrieverIfNeeded() {
        OtpSmsAutoFill.startSmsRetriever(activity)
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
        private const val MAX_RETRIEVER_RETRIES = 2
        private const val MAX_INJECT_RETRIES = 15
        private const val INJECT_RETRY_MS = 400L
    }
}
