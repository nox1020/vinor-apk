package ir.vinor.app

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
 * خواندن خودکار OTP با SMS Retriever — بدون READ_SMS و بدون دیالوگ User Consent.
 *
 * قالب پیامک sms.ir (نمونه با CODE=38415):
 * ```
 * کد ورود شما : (آن را در اختیار هیچ کس نگذارید)
 * https://vinor.ir
 * <#> 38415
 * @vinor.ir #38415
 * HASH11CHARS
 * ```
 * خط آخر هش ۱۱ کاراکتری امضای release است (برای Retriever؛ در پنل sms.ir متغیر HASH).
 */
object OtpSmsAutoFill {

    private const val TAG = "OtpSmsAutoFill"

    private val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
    private val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"

    /** اولویت: `<#> CODE` سپس `@vinor.ir #CODE` یا `@vinor.ir CODE`. */
    private val OTP_PATTERNS = listOf(
        Regex("""<#>\s*#?([0-9۰-۹٠-٩]{5})"""),
        Regex("""@vinor\.ir\s*#?([0-9۰-۹٠-٩]{5})""", RegexOption.IGNORE_CASE),
        Regex(
            """(?:کد\s*ورود)[^\d۰-۹٠-٩]{0,60}([0-9۰-۹٠-٩]{5})""",
            RegexOption.IGNORE_CASE,
        ),
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

    class SmsRetrieverReceiver(
        private val onCode: (String) -> Unit,
        private val onTimeout: (() -> Unit)? = null,
    ) : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
            val extras = intent.extras ?: return
            @Suppress("DEPRECATION")
            val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
            when (status.statusCode) {
                CommonStatusCodes.SUCCESS -> {
                    val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
                    if (message.isEmpty()) {
                        Log.w(TAG, "SMS empty — check HASH line in sms.ir template")
                        return
                    }
                    Log.d(TAG, "SMS retrieved (${message.length} chars)")
                    extractOtp(message)?.let(onCode)
                        ?: Log.w(TAG, "OTP not found; expected <#> ##### or @vinor.ir ######")
                }
                CommonStatusCodes.TIMEOUT -> {
                    Log.d(TAG, "SmsRetriever timeout")
                    onTimeout?.invoke()
                }
            }
        }
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
