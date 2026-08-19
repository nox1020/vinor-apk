package ir.vinor.app

import android.content.Context
import android.webkit.CookieManager
import java.security.MessageDigest

/**
 * جداسازی کش کاربر بدون ذخیرهٔ خود کوکی.
 * اثر انگشت = SHA-256 کوتاه از کوکی دامنه؛ بعد از logout عوض می‌شود.
 */
object VinorCacheSession {

    private const val PREFS = "vinor_cache_session"
    private const val KEY_FP = "fp"
    const val ANON = "anon"
    const val PUBLIC = "public"

    /**
     * فقط از ترد UI صدا زده شود (CookieManager روی ChromiumNetThread کرش می‌دهد).
     * اگر خواندن کوکی شکست بخورد، مقدار قبلی prefs را با anon بازنویسی نمی‌کند.
     */
    fun fingerprint(context: Context? = null): String {
        val cookie = runCatching {
            CookieManager.getInstance().getCookie("https://vinor.ir")
                ?: CookieManager.getInstance().getCookie("https://www.vinor.ir")
                ?: ""
        }.getOrNull()
        if (cookie == null) {
            return context?.let { lastKnown(it) } ?: ANON
        }
        val trimmed = cookie.trim()
        if (trimmed.length < 12) {
            persist(context, ANON)
            return ANON
        }
        val fp = sha16(trimmed)
        persist(context, fp)
        return fp
    }

    /** برای shouldInterceptRequest — فقط SharedPreferences، بدون CookieManager. */
    fun scopeForRead(context: Context): String = lastKnown(context)

    fun scopeForWrite(context: Context): String = fingerprint(context)

    fun lastKnown(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FP, ANON)?.trim()?.ifBlank { ANON } ?: ANON

    private fun persist(context: Context?, fp: String) {
        if (context == null) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_FP, fp).apply()
    }

    fun clearPrefs(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun sha16(raw: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return dig.take(8).joinToString("") { "%02x".format(it) }
    }
}
