package ir.vinor.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/**
 * OTP از پیامک — Retriever (خاموش) + User Consent (fallback برای قالب با «کد تایید:»).
 *
 * قالب sms.ir:
 * ```
 * کد تایید:
 * <#> 36423
 * @vinor.ir #36423
 * 4R707/AKLt0
 * ```
 */
object OtpSmsAutoFill {

    private const val TAG = "OtpSmsAutoFill"
    const val SMS_CONSENT_REQUEST_CODE = 7429

    private val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
    private val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"

    private val OTP_PATTERNS = listOf(
        Regex("""<#>\s*#?([0-9۰-۹٠-٩]{5})"""),
        Regex("""@vinor\.ir\s*#?([0-9۰-۹٠-٩]{5})""", RegexOption.IGNORE_CASE),
        Regex("""(?:کد\s*ت(?:أ?|ا?)یید)[^\d۰-۹٠-٩]{0,40}([0-9۰-۹٠-٩]{5})""", RegexOption.IGNORE_CASE),
    )

    fun normalizeOtpDigits(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder()
        for (ch in raw.trim()) {
            val idxFa = PERSIAN_DIGITS.indexOf(ch)
            if (idxFa >= 0) {
                sb.append(idxFa)
                continue
            }
            val idxAr = ARABIC_DIGITS.indexOf(ch)
            if (idxAr >= 0) {
                sb.append(idxAr)
                continue
            }
            if (ch in '0'..'9') sb.append(ch)
        }
        return sb.toString().take(5)
    }

    fun extractOtp(text: String?): String? {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return null
        for (pattern in OTP_PATTERNS) {
            val m = pattern.find(body) ?: continue
            val code = normalizeOtpDigits(m.groupValues.getOrNull(1))
            if (code.length == 5) return code
        }
        return null
    }

    fun diagnoseSms(body: String?): String {
        val msg = body?.trim().orEmpty()
        if (msg.isEmpty()) return "empty"
        val bytes = msg.toByteArray(Charsets.UTF_8).size
        if (bytes > 140) return "too_long_bytes=$bytes"
        val lines = msg.lines().filter { it.isNotBlank() }
        val last = lines.lastOrNull().orEmpty().trim()
        if (last.length != 11) return "hash_line_len=${last.length}"
        if (!msg.contains("<#>")) return "missing_<#>"
        if (extractOtp(msg) == null) return "otp_not_parsed"
        return "ok"
    }

    fun startSmsRetriever(context: Context) {
        try {
            SmsRetriever.getClient(context).startSmsRetriever()
                .addOnSuccessListener { Log.i(TAG, "SmsRetriever listening") }
                .addOnFailureListener { e -> Log.e(TAG, "SmsRetriever start failed", e) }
        } catch (e: Exception) {
            Log.e(TAG, "SmsRetriever unavailable", e)
        }
    }

    fun startSmsUserConsent(activity: Activity) {
        try {
            SmsRetriever.getClient(activity).startSmsUserConsent(null)
                .addOnSuccessListener { Log.i(TAG, "SmsUserConsent listening") }
                .addOnFailureListener { e -> Log.w(TAG, "SmsUserConsent start failed", e) }
        } catch (e: Exception) {
            Log.w(TAG, "SmsUserConsent unavailable", e)
        }
    }

    class SmsRetrieverReceiver(
        private val onCode: (String) -> Unit,
        private val onConsentRequired: ((Intent) -> Unit)? = null,
        private val onTimeout: (() -> Unit)? = null,
    ) : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
            val extras = intent.extras ?: return
            @Suppress("DEPRECATION")
            val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
            when (status.statusCode) {
                CommonStatusCodes.SUCCESS -> {
                    val consentIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT)
                    }
                    if (consentIntent != null) {
                        Log.i(TAG, "SmsUserConsent prompt for incoming SMS")
                        onConsentRequired?.invoke(consentIntent)
                        return
                    }
                    val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
                    Log.i(TAG, "SMS silent diag=${diagnoseSms(message)} len=${message.length}")
                    if (message.isEmpty()) return
                    extractOtp(message)?.let(onCode)
                        ?: Log.w(TAG, "OTP parse failed")
                }
                CommonStatusCodes.TIMEOUT -> {
                    Log.w(TAG, "SmsRetriever timeout")
                    onTimeout?.invoke()
                }
                else -> Log.w(TAG, "SmsRetriever status=${status.statusCode}")
            }
        }
    }

    fun handleConsentResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        onCode: (String) -> Unit,
    ): Boolean {
        if (requestCode != SMS_CONSENT_REQUEST_CODE) return false
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.d(TAG, "SmsUserConsent declined")
            return true
        }
        val message = data.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
        Log.i(TAG, "SMS via consent len=${message.length}")
        extractOtp(message)?.let(onCode)
        return true
    }

    fun registerSmsRetrieverReceiver(context: Context, receiver: BroadcastReceiver) {
        val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                SmsRetriever.SEND_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter, SmsRetriever.SEND_PERMISSION, null)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter, SmsRetriever.SEND_PERMISSION, null)
        }
    }

    fun unregisterReceiver(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }
}
