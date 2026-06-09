package ir.vinor.app

import android.content.Intent
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * OTP در WebView — Retriever (خاموش اگر فرمت OK) + User Consent (قالب «کد تایید:»).
 */
class WebLoginOtpSmsHelper(
    private val activity: AppCompatActivity,
    private val webViewProvider: () -> WebView?,
) : DefaultLifecycleObserver {

    private var listening = false
    private var lastInjectedCode: String? = null
    private var pendingCode: String? = null
    private var smsRetrieverReceiver: OtpSmsAutoFill.SmsRetrieverReceiver? = null
    private var retrieverRetries = 0
    private var injectPollCount = 0
    private val injectPollRunnable = Runnable { pollPendingInject() }

    private val consentLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            OtpSmsAutoFill.handleConsentResult(
                OtpSmsAutoFill.SMS_CONSENT_REQUEST_CODE,
                result.resultCode,
                result.data,
            ) { code -> activity.runOnUiThread { deliverCode(code) } }
            if (listening) {
                OtpSmsAutoFill.startSmsUserConsent(activity)
            }
        }

    init {
        AppSignatureHelper.logHashesForSmsTemplate(activity.applicationContext)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (listening) {
            registerReceiverIfNeeded()
            startBothListeners()
            pendingCode?.let { tryInject(it, fromPoll = false) }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopListening()
    }

    fun onUrlChanged(url: String?) {
        if (isLoginFlowUrl(url)) {
            ensureListening()
        } else if (!isLoginFlowUrl(url)) {
            stopListening()
        }
    }

    fun onPageContentReady() {
        if (!listening) return
        pendingCode?.let { tryInject(it, fromPoll = false) }
    }

    fun restartSmsRetriever() {
        ensureListening()
        retrieverRetries = 0
        registerReceiverIfNeeded()
        startBothListeners()
        Log.i(TAG, "OTP SMS listeners restarted")
    }

    fun onOtpPageReady() {
        restartSmsRetriever()
        installUrlChangeHook()
        pendingCode?.let { tryInject(it, fromPoll = false) }
    }

    private fun isLoginFlowUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = runCatching {
            android.net.Uri.parse(url).path.orEmpty().lowercase()
        }.getOrDefault("")
        return path.contains("/express/partner/login")
    }

    private fun ensureListening() {
        listening = true
        registerReceiverIfNeeded()
        installUrlChangeHook()
        if (lastInjectedCode == null && pendingCode == null) {
            retrieverRetries = 0
        }
        startBothListeners()
    }

    private fun startBothListeners() {
        OtpSmsAutoFill.startSmsRetriever(activity)
        OtpSmsAutoFill.startSmsUserConsent(activity)
    }

    private fun registerReceiverIfNeeded() {
        if (smsRetrieverReceiver != null) return
        smsRetrieverReceiver = OtpSmsAutoFill.SmsRetrieverReceiver(
            onCode = { code -> activity.runOnUiThread { deliverCode(code) } },
            onConsentRequired = { consentIntent ->
                activity.runOnUiThread {
                    try {
                        consentLauncher.launch(consentIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Consent launch failed", e)
                    }
                }
            },
            onTimeout = {
                activity.runOnUiThread {
                    if (!listening) return@runOnUiThread
                    if (retrieverRetries < MAX_RETRIEVER_RETRIES) {
                        retrieverRetries++
                        startBothListeners()
                    }
                }
            },
        )
        OtpSmsAutoFill.registerSmsRetrieverReceiver(activity, smsRetrieverReceiver!!)
    }

    private fun installUrlChangeHook() {
        val wv = webViewProvider() ?: return
        wv.post { wv.evaluateJavascript(URL_CHANGE_HOOK_JS, null) }
    }

    private fun unregisterReceiver() {
        smsRetrieverReceiver?.let { OtpSmsAutoFill.unregisterReceiver(activity, it) }
        smsRetrieverReceiver = null
    }

    private fun stopListening() {
        if (!listening && smsRetrieverReceiver == null) return
        listening = false
        lastInjectedCode = null
        pendingCode = null
        retrieverRetries = 0
        injectPollCount = 0
        cancelInjectPoll()
        unregisterReceiver()
    }

    private fun deliverCode(raw: String) {
        val code = OtpSmsAutoFill.normalizeOtpDigits(raw)
        if (code.length != 5) return
        if (code == lastInjectedCode) return
        Log.i(TAG, "OTP received")
        pendingCode = code
        tryInject(code, fromPoll = false)
        Toast.makeText(
            activity,
            activity.getString(R.string.login_otp_auto_filled),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun tryInject(code: String, fromPoll: Boolean) {
        injectIntoWebView(code, 0) { ok ->
            if (ok) {
                lastInjectedCode = code
                pendingCode = null
                injectPollCount = 0
                cancelInjectPoll()
            } else if (listening && pendingCode != null) {
                scheduleInjectPoll()
            } else if (!fromPoll) {
                scheduleInjectPoll()
            }
        }
    }

    private fun scheduleInjectPoll() {
        if (!listening || pendingCode == null) return
        if (injectPollCount >= MAX_INJECT_POLLS) return
        cancelInjectPoll()
        webViewProvider()?.postDelayed(injectPollRunnable, INJECT_POLL_MS)
    }

    private fun pollPendingInject() {
        if (!listening) return
        injectPollCount++
        pendingCode?.let { tryInject(it, fromPoll = true) }
    }

    private fun cancelInjectPoll() {
        webViewProvider()?.removeCallbacks(injectPollRunnable)
    }

    private fun injectIntoWebView(code: String, attempt: Int, onDone: (Boolean) -> Unit) {
        val wv = webViewProvider()
        if (wv == null) {
            onDone(false)
            return
        }
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
              return '';
            })();
        """.trimIndent()
        wv.post {
            wv.evaluateJavascript(js) { result ->
                val ok = result != null && result != "null" && result.replace("\"", "").isNotEmpty()
                if (!ok && attempt < MAX_INJECT_RETRIES) {
                    wv.postDelayed(
                        { injectIntoWebView(code, attempt + 1, onDone) },
                        INJECT_RETRY_MS,
                    )
                } else {
                    onDone(ok)
                }
            }
        }
    }

    companion object {
        private const val TAG = "WebLoginOtpSms"
        private const val MAX_RETRIEVER_RETRIES = 6
        private const val MAX_INJECT_RETRIES = 12
        private const val MAX_INJECT_POLLS = 180
        private const val INJECT_RETRY_MS = 300L
        private const val INJECT_POLL_MS = 400L

        private val URL_CHANGE_HOOK_JS = """
            (function(){
              if (window.__vinorOtpUrlHook) return;
              window.__vinorOtpUrlHook = 1;
              function notify(){
                try {
                  if (typeof VinorOtpBridge !== 'undefined') {
                    if (VinorOtpBridge.onUrlChanged) VinorOtpBridge.onUrlChanged(String(location.href || ''));
                    if (/\/login\/otp/i.test(location.pathname || '') && VinorOtpBridge.onOtpPageReady) {
                      VinorOtpBridge.onOtpPageReady();
                    }
                  }
                } catch (_) {}
              }
              var push = history.pushState;
              var replace = history.replaceState;
              history.pushState = function(){ push.apply(history, arguments); notify(); };
              history.replaceState = function(){ replace.apply(history, arguments); notify(); };
              window.addEventListener('popstate', notify);
              notify();
            })();
        """.trimIndent()
    }
}
