package ir.vinor.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/**
 * خواندن خودکار کد OTP از پیامک — RECEIVE_SMS + READ_SMS و SMS Retriever.
 */
object OtpSmsAutoFill {

    private const val TAG = "OtpSmsAutoFill"

    private val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
    private val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"

    private val OTP_PATTERNS = listOf(
        Regex("""(?:کد|code|CODE|رمز|verify|تأیید|تایید|vinor|وینور)[^\d۰-۹٠-٩]{0,20}([0-9۰-۹٠-٩]{5})""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[^\d۰-۹٠-٩])([0-9۰-۹٠-٩]{5})(?:[^\d۰-۹٠-٩]|$)"""),
    )

    fun requiredPermissions(): Array<String> = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
    )

    fun hasSmsPermission(context: Context): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** ارقام فارسی/عربی → لاتین؛ فقط ۵ رقم اول. */
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

    fun readRecentOtpFromInbox(context: Context, maxAgeMs: Long = 180_000L): String? {
        if (!hasSmsPermission(context)) return null
        val since = System.currentTimeMillis() - maxAgeMs
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE)
        val sort = "${Telephony.Sms.DATE} DESC"
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, null, null, sort)
            if (cursor == null) return null
            var checked = 0
            while (cursor.moveToNext() && checked < 12) {
                checked++
                val date = cursor.getLong(1)
                if (date < since) break
                val body = cursor.getString(0)
                extractOtp(body)?.let { return it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "inbox read failed", e)
        } finally {
            cursor?.close()
        }
        return null
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

    class IncomingSmsReceiver(private val onCode: (String) -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
                extractOtp(body)?.let(onCode)
            } catch (e: Exception) {
                Log.w(TAG, "SMS_RECEIVED parse error", e)
            }
        }
    }

    fun registerReceivers(
        context: Context,
        smsRetrieverReceiver: BroadcastReceiver,
        incomingSmsReceiver: BroadcastReceiver,
    ) {
        val retrieverFilter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        val incomingFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                smsRetrieverReceiver,
                retrieverFilter,
                SmsRetriever.SEND_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED,
            )
            context.registerReceiver(
                incomingSmsReceiver,
                incomingFilter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                smsRetrieverReceiver,
                retrieverFilter,
                SmsRetriever.SEND_PERMISSION,
                null,
            )
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(incomingSmsReceiver, incomingFilter)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                smsRetrieverReceiver,
                retrieverFilter,
                SmsRetriever.SEND_PERMISSION,
                null,
            )
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(incomingSmsReceiver, incomingFilter)
        }
    }

    fun unregisterReceivers(context: Context, vararg receivers: BroadcastReceiver) {
        receivers.forEach { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }
}
