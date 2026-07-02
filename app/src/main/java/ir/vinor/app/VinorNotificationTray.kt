package ir.vinor.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object VinorNotificationTray {
    const val CHANNEL_ID = "vinor_express_partner"
    private const val TAG = "VinorNotifyTray"
    const val DEFAULT_NOTIF_ID = 1001
    const val ACTION_OPEN = "OPEN_NOTIFICATIONS"
    const val EXTRA_URL = "url"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.importance >= NotificationManager.IMPORTANCE_HIGH) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "اعلان‌های وینور",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "اعلان‌های پنل همکاری وینور در نوار اعلان گوشی"
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
    }

    fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun show(
        context: Context,
        title: String,
        body: String,
        url: String? = null,
        notificationId: Int = DEFAULT_NOTIF_ID,
    ) {
        ensureChannel(context)
        if (!hasPostPermission(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — tray notification skipped")
            return
        }

        val targetUrl = normalizeUrl(url)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_OPEN
            putExtra(VinorNotifyHelper.EXTRA_OPEN_NOTIFICATIONS, true)
            putExtra(EXTRA_URL, targetUrl)
        }
        val pi = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title.ifBlank { "اعلان جدید" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notif)
            Log.d(TAG, "tray notification shown id=$notificationId")
        } catch (se: SecurityException) {
            Log.w(TAG, "notify failed — permission denied", se)
        }
    }

    fun normalizeUrl(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return "https://vinor.ir/express/partner/notifications"
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return "https://vinor.ir${if (trimmed.startsWith("/")) trimmed else "/$trimmed"}"
    }
}
