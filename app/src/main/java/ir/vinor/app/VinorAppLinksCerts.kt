package ir.vinor.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * SHA-256 Certificate Fingerprint برای Digital Asset Links (assetlinks.json).
 */
object VinorAppLinksCerts {

    private const val TAG = "VinorAppLinksCerts"

    fun sha256Fingerprints(context: Context): List<String> {
        val out = mutableListOf<String>()
        try {
            val pm = context.packageManager
            val packageName = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return emptyList()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }
            for (sig in signatures.orEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                out.add(digest.joinToString(":") { b -> "%02X".format(b) })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read signing certs", e)
        }
        return out.distinct()
    }

    fun logForAssetLinks(context: Context) {
        val fps = sha256Fingerprints(context)
        if (fps.isEmpty()) {
            Log.w(TAG, "No SHA-256 fingerprints — check signing config")
            return
        }
        Log.i(TAG, "=== Android App Links SHA-256 (put in assetlinks.json) ===")
        fps.forEachIndexed { i, fp ->
            Log.i(TAG, "[$i] $fp")
        }
        Log.i(
            TAG,
            "Host file at: https://vinor.ir/.well-known/assetlinks.json " +
                "package=${context.packageName}",
        )
    }

    fun assetLinksJson(context: Context): String {
        val fps = sha256Fingerprints(context)
        val fingerprints = fps.joinToString(",\n        ") { "\"$it\"" }
        return """
        |[{
        |  "relation": ["delegate_permission/common.handle_all_urls"],
        |  "target": {
        |    "namespace": "android_app",
        |    "package_name": "${context.packageName}",
        |    "sha256_cert_fingerprints": [
        |        $fingerprints
        |    ]
        |  }
        |}]
        """.trimMargin()
    }
}
