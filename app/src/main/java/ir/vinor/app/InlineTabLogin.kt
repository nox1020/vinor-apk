package ir.vinor.app

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** OTP UI hosted by Customers and Follow-up without changing Navigation destination. */
object InlineTabLogin {
    fun show(
        fragment: Fragment,
        container: ViewGroup,
        tab: String,
        expired: Boolean = false,
        onAuthenticated: () -> Unit,
    ) {
        if (container.childCount > 0 && !expired) return // Preserve an in-progress OTP.
        container.removeAllViews()
        val form = LayoutInflater.from(container.context)
            .inflate(R.layout.view_inline_tab_login, container, false)
        container.addView(form)
        val heading = form.findViewById<TextView>(R.id.inlineLoginLead)
        val error = form.findViewById<TextView>(R.id.inlineLoginError)
        val phone = form.findViewById<EditText>(R.id.inlineLoginPhone)
        val code = form.findViewById<EditText>(R.id.inlineLoginCode)
        val phoneStage = form.findViewById<View>(R.id.inlinePhoneStage)
        val otpStage = form.findViewById<View>(R.id.inlineOtpStage)
        val send = form.findViewById<Button>(R.id.inlineLoginSend)
        val verify = form.findViewById<Button>(R.id.inlineLoginVerify)
        val resend = form.findViewById<Button>(R.id.inlineLoginResend)
        val change = form.findViewById<Button>(R.id.inlineLoginChange)
        val progress = form.findViewById<View>(R.id.inlineLoginProgress)
        heading.text = if (expired) "نشست شما منقضی شده است. برای مشاهده $tab دوباره وارد شوید."
            else "برای مشاهده $tab شماره موبایل خود را تأیید کنید."
        var normalizedPhone = ""
        var busy = false
        var lastSend = 0L
        fun report(message: String) {
            error.text = message
            error.isVisible = message.isNotBlank()
        }
        fun active(): Boolean = fragment.isAdded && container.getChildAt(0) === form
        fun setBusy(value: Boolean) {
            busy = value
            progress.isVisible = value
            send.isEnabled = !value
            verify.isEnabled = !value
            resend.isEnabled = !value
            change.isEnabled = !value
        }
        fun requestOtp(resending: Boolean) {
            if (busy) return
            val candidate = PartnerApi.normalizePhone(phone.text.toString())
            if (!candidate.matches(Regex("09[0-9]{9}"))) {
                report("لطفاً شماره موبایل ۱۱ رقمی معتبر وارد کنید.")
                return
            }
            if (resending && SystemClock.elapsedRealtime() - lastSend < 60_000L) {
                report("برای ارسال مجدد کد، کمی صبر کنید.")
                return
            }
            report("")
            setBusy(true)
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val response = try {
                    withContext(Dispatchers.IO) {
                        PartnerApi.postJson(
                            "/express/partner/api/login-request",
                            JSONObject().put("phone", candidate).put("accept_legal", true),
                            container.context,
                        )
                    }
                } catch (_: Exception) {
                    JSONObject().put("error", "خطا در ارتباط. دوباره تلاش کنید.")
                }
                if (!active()) return@launch
                setBusy(false)
                if (response.optBoolean("success")) {
                    lastSend = SystemClock.elapsedRealtime()
                    normalizedPhone = candidate
                    heading.text = "کد تأیید به $candidate ارسال شد."
                    phoneStage.isVisible = false
                    otpStage.isVisible = true
                    code.requestFocus()
                    if (resending) Toast.makeText(container.context, "کد تأیید دوباره ارسال شد.", Toast.LENGTH_SHORT).show()
                } else {
                    report(response.optString("error").ifBlank { "ارسال کد تأیید ناموفق بود." })
                }
            }
        }
        send.setOnClickListener { requestOtp(false) }
        resend.setOnClickListener { requestOtp(true) }
        change.setOnClickListener {
            if (!busy) {
                normalizedPhone = ""
                code.text?.clear()
                otpStage.isVisible = false
                phoneStage.isVisible = true
                heading.text = "برای مشاهده $tab شماره موبایل خود را تأیید کنید."
                report("")
            }
        }
        verify.setOnClickListener {
            if (busy) return@setOnClickListener
            val otp = OtpSmsAutoFill.normalizeOtpDigits(code.text?.toString().orEmpty())
            if (normalizedPhone.isBlank() || otp.length != 5) {
                report("کد تأیید را به‌درستی وارد کنید.")
                return@setOnClickListener
            }
            report("")
            setBusy(true)
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val response = try {
                    withContext(Dispatchers.IO) {
                        PartnerApi.postJson(
                            "/express/partner/api/verify",
                            JSONObject().put("phone", normalizedPhone).put("code", otp),
                            container.context,
                        )
                    }
                } catch (_: Exception) {
                    JSONObject().put("error", "خطا در ارتباط. دوباره تلاش کنید.")
                }
                if (!active()) return@launch
                setBusy(false)
                if (response.optBoolean("success")) {
                    SessionAccount.remember(container.context, normalizedPhone)
                    PartnerApi.markLoggedIn()
                    (fragment.activity as? MainActivity)?.let { host ->
                        host.forgetPreviousAccount()
                        host.requestNotificationPermission()
                    }
                    VinorFcmRegistrar.registerAfterLogin(container.context)
                    if (PartnerApi.hasSession()) onAuthenticated()
                    else report("نشست ورود برقرار نشد. لطفاً دوباره تلاش کنید.")
                } else {
                    report(response.optString("error").ifBlank { "کد تأیید نادرست است." })
                }
            }
        }
    }
}
