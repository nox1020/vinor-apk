package ir.vinor.app

import android.content.Context

/** A local preference for presentation only. Notifications are never unsubscribed. */
object VinorNotificationPreferences {
    private const val STORE = "vinor_notification_preferences"
    private const val SOUND_ENABLED = "sound_enabled"

    fun soundEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getBoolean(SOUND_ENABLED, true)

    fun isMuted(context: Context): Boolean = !soundEnabled(context)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(SOUND_ENABLED, enabled).apply()
    }
}
