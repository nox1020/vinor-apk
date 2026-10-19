package ir.vinor.app

import android.content.Context

/** شمارهٔ نشست فعلی. با خروج پاک می‌شود تا اعلان حساب قبلی روی صفحه نماند. */
internal object SessionAccount {
    private const val PREFS = "vinor_session_account"
    private const val KEY_PHONE = "phone"

    fun remember(context: Context, phone: String) {
        val canonical = VisitAudience.canonical(phone)
        if (canonical.isEmpty()) return
        prefs(context).edit().putString(KEY_PHONE, canonical).apply()
    }

    fun phone(context: Context): String =
        prefs(context).getString(KEY_PHONE, "").orEmpty()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** اگر ورود شماره را ننوشته، همان me_phone کش‌شدهٔ پروفایل یا خانه کافی است. */
    fun captureFromCache(context: Context): String {
        val saved = phone(context)
        if (saved.isNotBlank()) return saved
        val paths = listOf(LandVisit.PROFILE_DATA, LandVisit.HOME_DATA)
        for (path in paths) {
            val cached = PartnerApi.peekJson(path, context) ?: continue
            val found = VisitAudience.canonical(cached.optString("me_phone"))
            if (found.isEmpty()) continue
            remember(context, found)
            return found
        }
        return ""
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
