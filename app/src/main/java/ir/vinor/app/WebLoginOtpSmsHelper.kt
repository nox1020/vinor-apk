package ir.vinor.app

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * خواندن OTP از پیامک هنگام ورود در WebView (صفحه /express/partner/login/otp).
 */
class WebLoginOtpSmsHelper(
    private val activity: AppCompatActivity,
    private val webViewProvider: () -> WebView?,
) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var lastInjectedCode: String? = null
    private var smsRetrieverReceiver: OtpSmsAutoFill.SmsRetrieverReceiver? = null
    private var incomingSmsReceiver: OtpSmsAutoFill.IncomingSmsReceiver? = null

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ok = OtpSmsAutoFill.requiredPermissions().all { grants[it] == true }
        if (ok) {
            startListening()
        } else if (listening) {
            Toast.makeText(
                activity,
                activity.getString(R.string.login_sms_permission_denied),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!listening) return
            if (!OtpSmsAutoFill.hasSmsPermission(activity)) {
                handler.postDelayed(this, POLL_MS)
                return
            }
            val code = OtpSmsAutoFill.readRecentOtpFromInbox(activity.applicationContext)
            if (!code.isNullOrEmpty()) {
                deliverCode(code)
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

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
        if (OtpSmsAutoFill.hasSmsPermission(activity)) {
            startListening()
        } else {
            permissionLauncher.launch(OtpSmsAutoFill.requiredPermissions())
        }
    }

    private fun startListening() {
        registerReceivers()
        OtpSmsAutoFill.startSmsRetriever(activity)
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        Thread {
            val code = OtpSmsAutoFill.readRecentOtpFromInbox(activity.applicationContext)
            if (!code.isNullOrEmpty()) {
                handler.post { deliverCode(code) }
            }
        }.start()
    }

    private fun registerReceivers() {
        unregisterReceivers()
        smsRetrieverReceiver = OtpSmsAutoFill.SmsRetrieverReceiver { code ->
            activity.runOnUiThread { deliverCode(code) }
        }
        incomingSmsReceiver = OtpSmsAutoFill.IncomingSmsReceiver { code ->
            activity.runOnUiThread { deliverCode(code) }
        }
        OtpSmsAutoFill.registerReceivers(
            activity,
            smsRetrieverReceiver!!,
            incomingSmsReceiver!!,
        )
    }

    private fun unregisterReceivers() {
        val receivers = listOfNotNull(smsRetrieverReceiver, incomingSmsReceiver)
        if (receivers.isNotEmpty()) {
            OtpSmsAutoFill.unregisterReceivers(activity, *receivers.toTypedArray())
        }
        smsRetrieverReceiver = null
        incomingSmsReceiver = null
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        lastInjectedCode = null
        handler.removeCallbacks(pollRunnable)
        unregisterReceivers()
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

    companion object {
        private const val POLL_MS = 2_000L
    }
}
