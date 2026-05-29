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
 * خواندن خودکار OTP با SMS Retriever (بدون مجوز READ_SMS/RECEIVE_SMS در مانیفست).
 * این روش باعث هشدار «برنامه دارای مشکل» در نصب سایدلود نمی‌شود.
 */
object OtpSmsAutoFill {

    private const val TAG = "OtpSmsAutoFill"

    private val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
    private val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"

    private val OTP_PATTERNS = listOf(
        Regex("""(?:کد|code|CODE|رمز|verify|تأیید|تایید|vinor|وینور)[^\d۰-۹٠-٩]{0,20}([0-9۰-۹٠-٩]{5})""", RegexOption.IGNORE_CASE),
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
        val fallback = normalizeOtpDigits(body)
        return fallback.takeIf { it.length == 5 }
    }

    fun startSmsRetriever(context: Context) {
        try {
            val task = SmsRetriever.getClient(context).startSmsRetriever()
            task.addOnSuccessListener { Log.d(TAG, "SmsRetriever started") }
            task.addOnFailureListener { e -> Log.w(TAG, "SmsRetriever start failed", e) }
        } catch (e: Exception) {
            Log.w(TAG, "SmsRetriever unavailable", e)
        }
    }

    class SmsRetrieverReceiver(private val onCode: (String) -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
            val extras = intent.extras ?: return
            @Suppress("DEPRECATION")
            val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
            when (status.statusCode) {
                CommonStatusCodes.SUCCESS -> {
                    val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
                    extractOtp(message)?.let(onCode)
                }
                CommonStatusCodes.TIMEOUT -> Log.d(TAG, "SmsRetriever timeout")
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
