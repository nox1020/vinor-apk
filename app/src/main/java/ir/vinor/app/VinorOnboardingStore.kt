package ir.vinor.app

import android.content.Context
import android.content.Intent

/** اسلایدهای معرفی تلگرام‌مانند — فقط اولین نصب. */
object VinorOnboardingStore {

    private const val PREFS = "vinor_onboarding"
    private const val KEY_DONE = "onboarding_v1_done"

    fun isDone(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()
    }

    fun shouldShow(context: Context, intent: Intent?): Boolean {
        if (isDone(context)) return false
        val action = intent?.action ?: return false
        if (action != Intent.ACTION_MAIN) return false
        return intent.hasCategory(Intent.CATEGORY_LAUNCHER)
    }
}
