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
 * خواندن خودکار OTP — SMS Retriever + User Consent (بدون READ_SMS).
 *
 * قالب پیامک وینور:
 * ```
 * کد ورود شما : (آن را در اختیار هیچ کس نگذارید)
 * https://vinor.ir
 * <#> 38415
 * @vinor.ir #38415   ← یا هش ۱۱ کاراکتری امضای اپ (ترجیحی برای Retriever)
 * ```
 */
object OtpSmsAutoFill {

    private const val TAG = "OtpSmsAutoFill"
    const val SMS_CONSENT_REQUEST_CODE = 7429

    private val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
    private val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"

    /** اولویت: خط `<#>`، سپس `@vinor.ir #`، سپس «کد ورود»، در نهایت هر ۵ رقم مجزا. */
    private val OTP_PATTERNS = listOf(
        Regex("""<#>\s*([0-9۰-۹٠-٩]{5})"""),
        Regex("""@vinor\.ir\s*#([0-9۰-۹٠-٩]{5})""", RegexOption.IGNORE_CASE),
        Regex(
            """(?:کد\s*ورود|کد\s*تأ?یید|verify|code|vinor|وینور)[^\d۰-۹٠-٩]{0,40}([0-9۰-۹٠-٩]{5})""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""(?m)^([0-9۰-۹٠-٩]{5})\s*$"""),
        Regex("""(?:^|[^\d۰-۹٠-٩])([0-9۰-۹٠-٩]{5})(?:[^\d۰-۹٠-٩]|$)"""),
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

    fun startSmsRetriever(context: Context) {
        try {
            SmsRetriever.getClient(context).startSmsRetriever()
                .addOnSuccessListener { Log.d(TAG, "SmsRetriever started") }
                .addOnFailureListener { e -> Log.w(TAG, "SmsRetriever start failed", e) }
        } catch (e: Exception) {
            Log.w(TAG, "SmsRetriever unavailable", e)
        }
    }

    fun startSmsUserConsent(activity: Activity, senderPhone: String? = null) {
        try {
            SmsRetriever.getClient(activity).startSmsUserConsent(senderPhone)
                .addOnSuccessListener { Log.d(TAG, "SmsUserConsent started") }
                .addOnFailureListener { e -> Log.w(TAG, "SmsUserConsent start failed", e) }
        } catch (e: Exception) {
            Log.w(TAG, "SmsUserConsent unavailable", e)
        }
    }

    /**
     * @param onCode کد ۵ رقمی استخراج‌شده
     * @param onConsentRequired Intent تأیید کاربر برای خواندن پیامک (User Consent)
     * @param onTimeout پایان ۵ دقیقهٔ Retriever — برای راه‌اندازی مجدد یا Consent
     */
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
                        onConsentRequired?.invoke(consentIntent)
                        return
                    }
                    val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
                    Log.d(TAG, "SMS retrieved (${message.length} chars)")
                    extractOtp(message)?.let(onCode)
                }
                CommonStatusCodes.TIMEOUT -> {
                    Log.d(TAG, "SmsRetriever timeout")
                    onTimeout?.invoke()
                }
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
            Log.d(TAG, "SmsUserConsent declined or empty")
            return true
        }
        val message = data.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
        Log.d(TAG, "SMS via consent (${message.length} chars)")
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
