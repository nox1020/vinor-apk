package ir.vinor.app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** دریافت FCM — نمایش در سینی سیستم از طریق VinorNotificationTray */
class VinorFcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "VinorFcmService"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        VinorFcmRegistrar.registerToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "اعلان جدید"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "پیام جدید در وینور اکسپرس"
        val url = message.data["url"]

        Log.d(TAG, "FCM received title=$title foreground=${VinorAppForeground.isInForeground}")

        if (!VinorNotificationTray.hasPostPermission(applicationContext)) {
            Log.w(TAG, "POST_NOTIFICATIONS denied — enable in Settings > Apps > Vinor > Notifications")
            return
        }

        VinorNotificationTray.show(
            context = applicationContext,
            title = title,
            body = body,
            url = url,
            notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
        )
    }
}
