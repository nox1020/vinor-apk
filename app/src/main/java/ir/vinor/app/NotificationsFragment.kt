package ir.vinor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentNotificationsBinding
import ir.vinor.app.databinding.ItemNotificationBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.webkit.CookieManager
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * صفحه اعلان‌ها - نیتیو، معادل /express/partner/notifications
 * از API های:
 * - GET /express/partner/api/notifications
 * - POST /express/partner/api/notifications/<id>/read
 * - POST /express/partner/api/notifications/read-all
 * استفاده می‌کند.
 */
class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var loadGen = 0
    private var rendered = false
    private var skipNextResume = true
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "NotificationsFragment"
        private const val BASE = "https://vinor.ir"
        private const val API_LIST = "/express/partner/api/notifications"
        private const val API_MARK_ALL = "/express/partner/api/notifications/read-all"
        private const val API_MARK_ONE = "/express/partner/api/notifications/%s/read"
        private const val EMPTY = "اعلانی برای نمایش وجود ندارد."
        private var remembered: JSONObject? = null
        private var sessionStamp = 0

        fun clearRemembered() {
            remembered = null
            sessionStamp++
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        skipNextResume = true
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.notificationsBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.notificationsMarkAll.setOnClickListener {
            markAllRead()
        }
        remembered?.let {
            if (paint(it)) rendered = true
        }
        loadNotifications()
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResume) {
            skipNextResume = false
            return
        }
        if (_binding != null) loadNotifications()
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun cookieHeader(): String = CookieManager.getInstance().getCookie(BASE) ?: ""

    private fun formatDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val raw = iso.trim().replace("Z", "")
        val outFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa"))
        val parsers = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        )
        for (p in parsers) {
            try {
                val d = p.parse(raw.take(19).trim())
                if (d != null) return outFmt.format(d)
            } catch (_: Exception) {
            }
        }
        return raw.take(16).replace("T", " ")
    }

    private fun loadNotifications() {
        val ctx = context ?: return
        if (_binding == null) return
        if (!rendered) {
            binding.notificationsProgress.visibility = View.VISIBLE
            binding.notificationsEmpty.visibility = View.GONE
        }
        val gen = ++loadGen
        val stamp = sessionStamp
        scope.launch {
            val wasFresh = withContext(Dispatchers.IO) { PartnerApi.isFresh(API_LIST, ctx) }
            try {
                val obj = PartnerApi.loadDisplay(API_LIST, ctx) { data ->
                    if (_binding == null || gen != loadGen || stamp != sessionStamp) return@loadDisplay
                    if (!paint(data)) return@loadDisplay
                    rendered = true
                    binding.notificationsProgress.visibility = View.GONE
                }
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                if (VinorPageLoad.needsLogin(obj)) {
                    clearRemembered()
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (paint(obj)) {
                    rendered = true
                    if (VinorPageLoad.canRemember(obj)) remembered = VinorPageLoad.rememberedCopy(obj)
                }
                if (rendered && VinorPageLoad.networkFailed(wasFresh, obj)) showOffline()
                else if (!rendered) showOffline()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "loadNotifications error", e)
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                showOffline()
            } finally {
                if (_binding != null && gen == loadGen) binding.notificationsProgress.visibility = View.GONE
            }
        }
    }

    private fun paint(obj: JSONObject): Boolean {
        if (VinorPageLoad.needsLogin(obj) || obj.optInt("http_status") == 403) return false
        if (obj.optBoolean("offline") || obj.optBoolean("non_json")) return false
        if (!obj.optBoolean("success", false) && obj.optJSONArray("notifications") == null) return false
        val code = if (obj.has("http_status")) obj.optInt("http_status") else 200
        if (code !in 200..299) return false
        val arr = obj.optJSONArray("notifications") ?: org.json.JSONArray()
        val items = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
        bind(items)
        return true
    }

    private fun showOffline() {
        if (_binding == null) return
        binding.notificationsProgress.visibility = View.GONE
        if (rendered) {
            VinorPageLoad.showOffline(binding.root) { loadNotifications() }
            return
        }
        binding.notificationsEmpty.text = VinorPageLoad.OFFLINE
        binding.notificationsEmpty.visibility = View.VISIBLE
    }

    private fun bind(items: List<JSONObject>) {
        if (_binding == null) return
        binding.notificationsList.removeAllViews()

        if (items.isEmpty()) {
            binding.notificationsEmpty.text = EMPTY
            binding.notificationsEmpty.visibility = View.VISIBLE
            return
        }
        binding.notificationsEmpty.visibility = View.GONE

        for (i in items.indices) {
            val n = items[i]
            val itemBinding = ItemNotificationBinding.inflate(layoutInflater, binding.notificationsList, false)
            val type = n.optString("type", "")
            val isRead = n.optBoolean("is_read", false)
            val title = n.optString("title", "اعلان جدید")
            val body = n.optString("body", "")
            val createdAt = n.optString("created_at", "")
            val actionUrl = n.optString("action_url", "")
            val id = n.optString("id", "")

            itemBinding.notificationTitle.text = title
            itemBinding.notificationBody.text = body
            itemBinding.notificationDate.text = formatDate(createdAt)

            // وضعیت خوانده‌نشده
            itemBinding.notificationUnreadDot.visibility = if (isRead) View.GONE else View.VISIBLE

            // آیکون بر اساس نوع
            when (type) {
                "success" -> {
                    itemBinding.notificationIcon.text = "✅"
                }
                "warning" -> {
                    itemBinding.notificationIcon.text = "⚠️"
                }
                "error" -> {
                    itemBinding.notificationIcon.text = "⛔"
                }
                else -> {
                    itemBinding.notificationIcon.text = "🔔"
                }
            }

            // دکمه «جزئیات»
            if (actionUrl.isNotBlank()) {
                itemBinding.notificationAction.visibility = View.VISIBLE
                itemBinding.notificationAction.setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl)))
                    } catch (_: Exception) {
                        context?.let { ctx ->
                            Toast.makeText(ctx, "خطا در باز کردن لینک", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                itemBinding.notificationAction.visibility = View.GONE
            }

            // دکمه «خوانده شد»
            itemBinding.notificationMarkRead.isEnabled = !isRead
            itemBinding.notificationMarkRead.setOnClickListener {
                if (id.isNotBlank() && !isRead) {
                    markOneRead(id)
                }
            }

            binding.notificationsList.addView(itemBinding.root)
        }
    }

    private fun markAllRead() {
        if (_binding == null) return
        binding.notificationsMarkAll.isEnabled = false
        scope.launch {
            try {
                val req = Request.Builder()
                    .url("$BASE$API_MARK_ALL")
                    .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .addHeader("Cookie", cookieHeader())
                    .addHeader("Content-Type", "application/json")
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                withContext(Dispatchers.Main) {
                    binding.notificationsMarkAll.isEnabled = true
                    if (!resp.isSuccessful) {
                        context?.let {
                            Toast.makeText(it, "خطا در بروزرسانی", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        loadNotifications()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "markAllRead error", e)
                withContext(Dispatchers.Main) {
                    binding.notificationsMarkAll.isEnabled = true
                    context?.let {
                        Toast.makeText(it, "خطا در ارتباط", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun markOneRead(id: String) {
        if (_binding == null) return
        scope.launch {
            try {
                val url = "$BASE" + API_MARK_ONE.format(id)
                val req = Request.Builder()
                    .url(url)
                    .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .addHeader("Cookie", cookieHeader())
                    .addHeader("Content-Type", "application/json")
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) {
                        loadNotifications()
                    } else {
                        context?.let {
                            Toast.makeText(it, "خطا در بروزرسانی", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "markOneRead error", e)
                withContext(Dispatchers.Main) {
                    context?.let {
                        Toast.makeText(it, "خطا در ارتباط", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

