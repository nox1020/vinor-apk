package ir.vinor.app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** دریافت FCM وقتی اپ بسته/پس‌زمینه است — نوتیف سینی سیستم */
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

        // وقتی WebView باز است، بج فوتر وب کافی است — از نوتیف تکراری سینی جلوگیری می‌شود
        if (VinorAppForeground.isInForeground) {
            Log.d(TAG, "skip tray notification — app in foreground")
            return
        }

        Log.d(TAG, "show tray notification title=$title")
        VinorNotificationTray.show(
            context = applicationContext,
            title = title,
            body = body,
            url = url,
            notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
        )
    }
}
