package ir.vinor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * اعلان‌های همکار در APK WebView:
 * - polling سبک با کوکی سشن برای همگام‌سازی baseline با بج فوتر وب
 * - نوتیف سینی سیستم فقط از FCM وقتی اپ در پس‌زمینه است
 */
class VinorNotifyHelper(
    private val activity: MainActivity,
    private val webViewProvider: () -> android.webkit.WebView?
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "VinorNotifyHelper"
        const val EXTRA_OPEN_NOTIFICATIONS = "vinor_open_notifications"
        private const val BASE = "https://vinor.ir"
        private const val API_UNREAD = "/express/partner/api/notifications/unread-count"
        private const val NOTIF_ID = 1001
        private const val POLL_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private var pollJob: Job? = null
    private var lastUnread = -1
    private var permissionRequested = false

    fun attach(lifecycleOwner: LifecycleOwner) {
        ensureChannel()
        lifecycleOwner.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        startPolling()
    }

    override fun onStop(owner: LifecycleOwner) {
        stopPolling()
    }

    fun destroy() {
        stopPolling()
        scope.cancel()
    }

    /** همگام‌سازی baseline از JS تا دوباره نوتیفیکیشن تکراری نشود */
    fun updateBaselineUnread(count: Int) {
        if (count >= 0) lastUnread = count
    }

    fun requestPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (permissionRequested) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        permissionRequested = true
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_POST_NOTIFICATIONS
        )
    }

    fun openNotificationsPage() {
        webViewProvider()?.loadUrl("$BASE/express/partner/notifications")
            ?: activity.startActivity(
                Intent(activity, MainActivity::class.java).apply {
                    putExtra(EXTRA_OPEN_NOTIFICATIONS, true)
                }
            )
    }

    fun handleOpenIntent(intent: Intent?) {
        if (intent == null) return
        val urlExtra = intent.getStringExtra(VinorNotificationTray.EXTRA_URL)?.trim().orEmpty()
        val openNotifications = intent.getBooleanExtra(EXTRA_OPEN_NOTIFICATIONS, false)
            || intent.action == VinorNotificationTray.ACTION_OPEN
            || urlExtra.isNotEmpty()
        if (!openNotifications) return

        val target = VinorNotificationTray.normalizeUrl(
            urlExtra.ifBlank { "/express/partner/notifications" }
        )
        webViewProvider()?.loadUrl(target)
            ?: activity.startActivity(
                Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_OPEN_NOTIFICATIONS, true)
                    putExtra(VinorNotificationTray.EXTRA_URL, target)
                }
            )
        intent.removeExtra(EXTRA_OPEN_NOTIFICATIONS)
        intent.removeExtra(VinorNotificationTray.EXTRA_URL)
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            pollOnce()
            while (isActive) {
                delay(POLL_MS)
                pollOnce()
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        val cookie = cookieHeader()
        if (cookie.isBlank()) {
            lastUnread = -1
            return
        }
        val unread = fetchUnread(cookie) ?: return
        if (lastUnread >= 0 && unread > lastUnread) {
            // اپ باز است — بج فوتر وب به‌روز می‌شود؛ سینی فقط از FCM در پس‌زمینه
            requestPermissionIfNeeded()
        }
        if (unread == 0) {
            NotificationManagerCompat.from(activity).cancel(NOTIF_ID)
        }
        if (lastUnread == -1) {
            requestPermissionIfNeeded()
        }
        lastUnread = unread
    }

    private fun cookieHeader(): String =
        CookieManager.getInstance().getCookie(BASE)?.trim().orEmpty()

    private suspend fun fetchUnread(cookie: String): Int? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE$API_UNREAD")
                .addHeader("Cookie", cookie)
                .addHeader("Accept", "application/json")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("User-Agent", VinorUserAgent.forHttpRequest(activity))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401) {
                    lastUnread = -1
                    return@withContext null
                }
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string().orEmpty()
                val obj = JSONObject(body)
                if (!obj.optBoolean("success", false)) return@withContext null
                obj.optInt("unread_count", 0)
            }
        } catch (e: Exception) {
            Log.d(TAG, "unread poll failed", e)
            null
        }
    }

    private fun ensureChannel() {
        VinorNotificationTray.ensureChannel(activity)
    }
}

const val REQ_POST_NOTIFICATIONS = 9103
