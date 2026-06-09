package ir.vinor.app

import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * خواندن OTP از پیامک هنگام ورود در WebView — SMS Retriever + inject با retry/pending.
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
    private var urlHookInstalled = false
    private var injectPollCount = 0
    private val injectPollRunnable = Runnable { pollPendingInject() }

    init {
        AppSignatureHelper.logHashesForSmsTemplate(activity.applicationContext)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (listening) {
            OtpSmsAutoFill.startSmsRetriever(activity.applicationContext)
            pendingCode?.let { tryInject(it, fromPoll = false) }
        }
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

    /** بعد از onPageFinished یا soft-nav (pushState). */
    fun onPageContentReady() {
        if (!listening) return
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
        if (listening) {
            OtpSmsAutoFill.startSmsRetriever(activity.applicationContext)
            installUrlChangeHook()
            return
        }
        listening = true
        lastInjectedCode = null
        retrieverRetries = 0
        registerReceiver()
        OtpSmsAutoFill.startSmsRetriever(activity.applicationContext)
        installUrlChangeHook()
    }

    private fun installUrlChangeHook() {
        if (urlHookInstalled) return
        val wv = webViewProvider() ?: return
        urlHookInstalled = true
        wv.post {
            wv.evaluateJavascript(URL_CHANGE_HOOK_JS, null)
        }
    }

    private fun registerReceiver() {
        unregisterReceiver()
        val appCtx = activity.applicationContext
        smsRetrieverReceiver = OtpSmsAutoFill.SmsRetrieverReceiver(
            onCode = { code -> activity.runOnUiThread { deliverCode(code) } },
            onTimeout = {
                activity.runOnUiThread {
                    if (!listening) return@runOnUiThread
                    if (retrieverRetries < MAX_RETRIEVER_RETRIES) {
                        retrieverRetries++
                        android.util.Log.d(TAG, "SmsRetriever retry $retrieverRetries")
                        OtpSmsAutoFill.startSmsRetriever(activity.applicationContext)
                    } else {
                        android.util.Log.w(
                            TAG,
                            "SmsRetriever exhausted — check SMS ≤140 bytes and HASH line",
                        )
                    }
                }
            },
        )
        OtpSmsAutoFill.registerSmsRetrieverReceiver(appCtx, smsRetrieverReceiver!!)
    }

    private fun unregisterReceiver() {
        smsRetrieverReceiver?.let {
            OtpSmsAutoFill.unregisterReceiver(activity.applicationContext, it)
        }
        smsRetrieverReceiver = null
    }

    private fun stopListening() {
        if (!listening) return
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
        android.util.Log.i(TAG, "OTP received from SMS Retriever")
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
        if (injectPollCount >= MAX_INJECT_POLLS) {
            android.util.Log.w(TAG, "OTP inject polls exhausted — otpInput not ready?")
            return
        }
        cancelInjectPoll()
        webViewProvider()?.postDelayed(injectPollRunnable, INJECT_POLL_MS)
    }

    private fun pollPendingInject() {
        if (!listening) return
        injectPollCount++
        val code = pendingCode ?: return
        tryInject(code, fromPoll = true)
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
        private const val MAX_RETRIEVER_RETRIES = 8
        private const val MAX_INJECT_RETRIES = 8
        private const val MAX_INJECT_POLLS = 120
        private const val INJECT_RETRY_MS = 350L
        private const val INJECT_POLL_MS = 500L

        private val URL_CHANGE_HOOK_JS = """
            (function(){
              if (window.__vinorOtpUrlHook) return;
              window.__vinorOtpUrlHook = 1;
              function notify(){
                try {
                  if (typeof VinorOtpBridge !== 'undefined' && VinorOtpBridge.onUrlChanged) {
                    VinorOtpBridge.onUrlChanged(String(location.href || ''));
                  }
                } catch (_) {}
              }
              var push = history.pushState;
              var replace = history.replaceState;
              history.pushState = function(){ push.apply(history, arguments); notify(); };
              history.replaceState = function(){ replace.apply(history, arguments); notify(); };
              window.addEventListener('popstate', notify);
            })();
        """.trimIndent()
    }
}
