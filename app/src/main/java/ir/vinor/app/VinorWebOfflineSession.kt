package ir.vinor.app

import android.content.Context
import android.webkit.WebView

/**
 * وضعیت آفلاین وب در APK: آخرین URL موفق + پرچم «قبلاً محتوا دیده شده».
 * HTML پنل no-store است؛ کش آفلاین: SW runtime (VinorApp) + WebView زنده.
 * چرخهٔ عمر کش: [VinorWebCacheManager]
 */
object VinorWebOfflineSession {

    private const val PREFS = "vinor_web_state"
    private const val KEY_REVEALED = "content_revealed_once"
    private const val KEY_LAST_URL = "last_partner_url"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isContentRevealed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REVEALED, false)

    fun lastPartnerUrl(context: Context): String? =
        prefs(context).getString(KEY_LAST_URL, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun markSuccess(context: Context, url: String?) {
        if (!isPartnerBrowsableUrl(url)) return
        prefs(context).edit()
            .putBoolean(KEY_REVEALED, true)
            .putString(KEY_LAST_URL, url)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun isPartnerBrowsableUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (VinorWebViewErrorShield.isNeutralPlaceholderUrl(url)) return false
        if (VinorWebViewErrorShield.looksLikeChromeErrorPage(url)) return false
        if (url.startsWith("about:")) return false
        return url.contains("/express/partner") || url.contains("vinor.ir")
    }

    fun webViewHasBrowsablePage(webView: WebView): Boolean =
        isPartnerBrowsableUrl(webView.url)
}
