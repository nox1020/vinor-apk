package ir.vinor.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager

/**
 * نگهداری Deep Link معلق تا پس از ورود موفق، کاربر به همان لینک برگردد.
 */
object DeepLinkPendingStore {

    private const val PREFS = "vinor_deeplink_prefs"
    private const val KEY_PENDING = "pending_url"
    private const val TAG = "DeepLinkPending"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        prefs(context).edit().putString(KEY_PENDING, trimmed).apply()
        Log.i(TAG, "saved pending=$trimmed")
    }

    fun peek(context: Context): String? =
        prefs(context).getString(KEY_PENDING, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun consume(context: Context): String? {
        val url = peek(context) ?: return null
        clear(context)
        Log.i(TAG, "consumed pending=$url")
        return url
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    /**
     * تشخیص تقریبی لاگین همکار از روی کوکی جلسه Flask روی دامنه vinor.ir.
     * Fail-open: اگر کوکی نامشخص باشد، برای مسیرهای عمومی اجازه می‌دهد.
     */
    fun hasPartnerSessionCookie(context: Context? = null): Boolean {
        if (!VinorSafe.isMainThread()) return false
        return try {
            val cm = CookieManager.getInstance()
            val cookie = cm.getCookie("https://vinor.ir") ?: cm.getCookie("https://www.vinor.ir") ?: ""
            if (cookie.isBlank()) return false
            // Flask session cookie معمولاً session=... است؛ وجود کوکی غیرخالی + طول کافی
            val parts = cookie.split(";").map { it.trim() }
            parts.any { part ->
                val name = part.substringBefore("=", "").lowercase()
                name == "session" || name.contains("vinor") || name.contains("remember")
            } && cookie.length > 20
        } catch (e: Exception) {
            Log.w(TAG, "session cookie check failed", e)
            false
        }
    }
}
