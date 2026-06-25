package ir.vinor.app

import android.content.Context

object VinorUserAgent {
    /** سافیکس یکسان با WebView در MainActivity — برای API، دانلود و FCM */
    fun appSuffix(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName?.trim()
        }.getOrNull().orEmpty()
        return "VinorApp/WebViewOnly" + if (version.isNotEmpty()) "/$version" else ""
    }

    fun forHttpRequest(context: Context): String = appSuffix(context)
}
