package ir.vinor.app

import android.content.Context

object VinorUserAgent {
    fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.trim()
    }.getOrNull().orEmpty()

    /** سافیکس یکسان با WebView در MainActivity — برای API، دانلود و FCM */
    fun appSuffix(context: Context): String {
        val version = appVersionName(context)
        return "VinorApp/WebViewOnly" + if (version.isNotEmpty()) "/$version" else ""
    }

    fun forHttpRequest(context: Context): String = appSuffix(context)

    /** هدر نسخه برای درخواست‌های HTML پنل — تشخیص APK در سرور */
    fun webViewLoadHeaders(context: Context): Map<String, String> {
        val version = appVersionName(context)
        return if (version.isNotEmpty()) mapOf("X-Vinor-App-Version" to version) else emptyMap()
    }
}
