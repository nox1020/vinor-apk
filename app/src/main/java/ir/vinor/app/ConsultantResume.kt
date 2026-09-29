package ir.vinor.app

import android.content.Context

/**
 * پرچم ناوبری بعد از OTP و دیده‌شدن اسلایدها.
 * در همان prefs نشست همکار است؛ رکورد apply جدا ذخیره نمی‌شود.
 */
object ConsultantResume {
    private const val PREFS = "vinor_auth"
    private const val KEY_RESUME = "consultant_onboarding_resume"
    private const val KEY_SLIDES = "consultant_slides_seen"

    fun arm(context: Context) {
        prefs(context).edit().putBoolean(KEY_RESUME, true).apply()
    }

    fun consume(context: Context): Boolean {
        val store = prefs(context)
        val armed = store.getBoolean(KEY_RESUME, false)
        if (armed) store.edit().putBoolean(KEY_RESUME, false).apply()
        return armed
    }

    fun slidesSeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SLIDES, false)

    fun markSlidesSeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_SLIDES, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
