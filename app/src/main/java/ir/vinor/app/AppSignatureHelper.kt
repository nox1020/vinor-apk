package ir.vinor.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * هش ۱۱ کاراکتری — خط آخر قالب sms.ir.
 *
 * ```
 * کد تایید:
 * <#> 36423
 * @vinor.ir #36423
 * YOUR_11_CHAR_HASH
 * ```
 */
object AppSignatureHelper {

    private const val TAG = "AppSignatureHelper"

    fun getAppSignatures(context: Context): List<String> {
        val appCodes = mutableListOf<String>()
        try {
            val packageName = context.packageName
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }.orEmpty()
            } else {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.map { it.toCharsString() }.orEmpty()
            }
            for (signature in signatures) {
                hash(packageName, signature)?.let { appCodes.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read app signatures", e)
        }
        return appCodes.distinct()
    }

    fun logHashesForSmsTemplate(context: Context) {
        val hashes = getAppSignatures(context)
        if (hashes.isEmpty()) {
            Log.w(TAG, "No SMS Retriever hashes found — check signing config")
            return
        }
        Log.i(
            TAG,
            "SMS Retriever hash (set SMS_RETRIEVER_HASH in api_keys.py): ${hashes.joinToString(" | ")}",
        )
    }

    private fun hash(packageName: String, signature: String): String? {
        return try {
            val appInfo = "$packageName $signature"
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(appInfo.toByteArray(StandardCharsets.UTF_8))
            val hashSignature = digest.digest().copyOfRange(0, 9)
            Base64.encodeToString(hashSignature, Base64.NO_PADDING or Base64.NO_WRAP)
                .substring(0, 11)
        } catch (e: Exception) {
            Log.w(TAG, "Hash generation failed", e)
            null
        }
    }
}
