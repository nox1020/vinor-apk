package ir.vinor.app

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
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
        // وقتی WebView باز است، بج فوتر وب کافی است — از نوتیف تکراری سینی جلوگیری می‌شود
        if (VinorAppForeground.isInForeground) {
            Log.d(TAG, "skip tray notification — app in foreground")
            return
        }

        val title = message.notification?.title
            ?: message.data["title"]
            ?: "اعلان جدید"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "پیام جدید در وینور اکسپرس"
        val url = message.data["url"]

        // foreground: خودمان نشان می‌دهیم. background/killed با notification payload خود اندروید نشان می‌دهد.
        VinorNotificationTray.show(
            context = applicationContext,
            title = title,
            body = body,
            url = url,
            notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
        )
    }
}
