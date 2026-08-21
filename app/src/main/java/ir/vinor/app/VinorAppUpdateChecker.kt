package ir.vinor.app

import android.content.Context
import android.webkit.WebView

/**
 * Facade سازگار با فراخوانی‌های قبلی — منطق واقعی در [VinorAppUpdateController] است.
 */
object VinorAppUpdateChecker {

    data class UpdateState(
        val show: Boolean,
        val version: String = "",
        val url: String = "",
        val sourceUrl: String = "",
    )

    fun appVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }

    fun appVersionCode(context: Context): Long {
        val fromPm = VinorApkTrust.installedVersionCode(context)
        if (fromPm > 0L) return fromPm
        return ApkVersionUtils.versionCodeFromName(appVersionName(context))
    }

    fun readCached(context: Context): UpdateState {
        val s = VinorAppUpdateController.read(context)
        return UpdateState(
            show = s.show,
            version = s.versionName,
            url = s.url,
            sourceUrl = s.sourceUrl,
        )
    }

    fun check(context: Context, webView: WebView? = null, force: Boolean = false) {
        VinorAppUpdateController.check(context, webView, force)
    }

    fun applyToWebView(webView: WebView, @Suppress("UNUSED_PARAMETER") state: UpdateState) {
        VinorAppUpdateController.syncToWebView(webView)
    }

    fun syncCachedToWebView(context: Context, webView: WebView) {
        VinorAppUpdateController.syncToWebView(webView)
        val s = VinorAppUpdateController.read(context)
        if (s.show && !s.ready && s.status != VinorAppUpdateStatus.FAILED) {
            VinorAppUpdateController.check(context, webView, force = false)
        }
    }
}
