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
        runCatching {
            Log.d(TAG, "FCM token refreshed")
            // force: حتی در پس‌زمینه token جدید را به سرور بفرست
            VinorFcmRegistrar.registerToken(applicationContext, token, force = true)
        }.onFailure {
            Log.w(TAG, "onNewToken failed", it)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        runCatching {
            val title = message.notification?.title
                ?: message.data["title"]
                ?: "اعلان جدید"
            val body = message.notification?.body
                ?: message.data["body"]
                ?: "پیام جدید در وینور اکسپرس"
            val url = message.data["url"]

            Log.d(TAG, "FCM received title=$title foreground=${VinorAppForeground.isInForeground}")

            // وقتی اپ پس‌زمینه/بسته است، بلوک notification سرور خودش در سینی نمایش می‌دهد
            if (!VinorAppForeground.isInForeground && message.notification != null) {
                Log.d(TAG, "background + notification payload — system tray handles display")
                return@runCatching
            }

            if (!VinorNotificationTray.canNotify(applicationContext)) {
                Log.w(TAG, "notifications disabled — enable in Settings > Apps > Vinor > Notifications")
                return@runCatching
            }

            VinorNotificationTray.show(
                context = applicationContext,
                title = title,
                body = body,
                url = url,
                notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            )
        }.onFailure {
            // جلوگیری از کرش → ری‌استارت سرویس → حلقهٔ «اپ مکرر خراب می‌شود»
            Log.e(TAG, "onMessageReceived failed", it)
        }
    }
}
