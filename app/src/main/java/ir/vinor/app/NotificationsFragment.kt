package ir.vinor.app

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentNotificationsBinding
import ir.vinor.app.databinding.ItemNotificationBinding
import ir.vinor.app.databinding.ItemNotificationSectionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.webkit.CookieManager
import java.util.concurrent.TimeUnit

/**
 * صفحه اعلان‌ها به سبک دیوار - نیتیو، معادل /express/partner/notifications (notifications.html وب).
 * نوار «همه / خوانده‌نشده»، «خواندن همه»، گروه روز، ردیف تخت با آیکون نوع و نقطهٔ خوانده‌نشده؛
 * لمس ردیف = خوانده‌شده + رفتن به لینک داخلی (MainActivity.openPath). مدل نمایشی: NotificationFeed.
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
    private var paintedGeneration = -1
    private var items: List<JSONObject> = emptyList()
    private var tab = NotificationFeed.Tab.ALL
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
        private var remembered: JSONObject? = null
        private val markScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var sessionStamp = 0

        fun generation(): Int = sessionStamp

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
        binding.notificationsTabAll.setOnClickListener { selectTab(NotificationFeed.Tab.ALL) }
        binding.notificationsTabUnread.setOnClickListener { selectTab(NotificationFeed.Tab.UNREAD) }
        binding.notificationsPostchi.setOnClickListener {
            (activity as? MainActivity)?.openPath(NotificationFeed.POSTCHI_PATH)
        }
        binding.notificationsRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.vinor_primary_red),
        )
        binding.notificationsRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.vinor_raised),
        )
        binding.notificationsRefresh.setOnRefreshListener { loadNotifications(pull = true) }
        paintTabs()
        if (!dropStaleAccountPaint()) {
            remembered?.let {
                if (paint(it)) rendered = true
            }
            paintedGeneration = sessionStamp
        }
        loadNotifications()
    }

    override fun onResume() {
        super.onResume()
        if (dropStaleAccountPaint()) {
            skipNextResume = false
            if (_binding != null) loadNotifications()
            return
        }
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

    private fun loadNotifications(pull: Boolean = false) {
        val ctx = context ?: return
        if (_binding == null) return
        if (pull) binding.notificationsRefresh.isRefreshing = true
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
                if (_binding != null && gen == loadGen) {
                    binding.notificationsProgress.visibility = View.GONE
                    binding.notificationsRefresh.isRefreshing = false
                }
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
        val me = context?.let { SessionAccount.captureFromCache(it) }.orEmpty()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            if (!VisitAudience.showNotification(row, me)) continue
            list.add(row)
        }
        items = list
        bind()
        return true
    }

    private fun dropStaleAccountPaint(): Boolean {
        if (!AccountPaint.isStale(paintedGeneration, sessionStamp)) return false
        rendered = false
        if (_binding != null) {
            binding.notificationsList.removeAllViews()
            binding.notificationsEmpty.visibility = View.GONE
        }
        items = emptyList()
        paintedGeneration = sessionStamp
        return true
    }

    private fun showOffline() {
        if (_binding == null) return
        binding.notificationsProgress.visibility = View.GONE
        if (rendered) {
            VinorPageLoad.showOffline(binding.root) { loadNotifications() }
            return
        }
        binding.notificationsEmptyTitle.text = VinorPageLoad.OFFLINE
        binding.notificationsEmptySub.isVisible = false
        binding.notificationsEmpty.visibility = View.VISIBLE
    }

    private fun selectTab(next: NotificationFeed.Tab) {
        if (tab == next) return
        tab = next
        paintTabs()
        bind()
    }

    /** چیپ فعال همان «ته‌رنگ + خط سبز» هدر خانه (bg_search_chip_state با isSelected). */
    private fun paintTabs() {
        val b = _binding ?: return
        b.notificationsTabAll.isSelected = tab == NotificationFeed.Tab.ALL
        b.notificationsTabUnread.isSelected = tab == NotificationFeed.Tab.UNREAD
    }

    private fun kindIcon(kind: NotificationFeed.Kind): Triple<Int, Int, Int> = when (kind) {
        NotificationFeed.Kind.VISIT -> Triple(R.drawable.ic_notif_visit, R.color.notif_visit_bg, R.color.notif_visit_fg)
        NotificationFeed.Kind.WALLET -> Triple(R.drawable.ic_notif_wallet, R.color.notif_wallet_bg, R.color.notif_wallet_fg)
        NotificationFeed.Kind.LISTING -> Triple(R.drawable.ic_notif_listing, R.color.notif_listing_bg, R.color.notif_listing_fg)
        NotificationFeed.Kind.GENERAL -> Triple(R.drawable.ic_notif_general, R.color.notif_general_bg, R.color.notif_general_fg)
    }

    private fun bind() {
        val b = _binding ?: return
        val ctx = context ?: return
        val feed = NotificationFeed.build(items, System.currentTimeMillis() / 1000, tab)
        b.notificationsList.removeAllViews()

        val unreadFa = NotificationFeed.faDigits(feed.unread)
        b.notificationsUnreadBadge.text = unreadFa
        b.notificationsUnreadBadge.isVisible = feed.unread > 0
        b.notificationsTabUnread.contentDescription = if (feed.unread > 0) {
            "اعلان‌های خوانده‌نشده، $unreadFa"
        } else {
            "اعلان‌های خوانده‌نشده"
        }
        b.notificationsMarkAll.isVisible = feed.unread > 0

        val empty = NotificationFeed.emptyState(feed, tab)
        b.notificationsEmpty.isVisible = empty != null
        if (empty != null) {
            b.notificationsEmptyTitle.text = empty.first
            b.notificationsEmptySub.text = empty.second
            b.notificationsEmptySub.isVisible = true
            b.notificationsEmptyIcon.setImageResource(
                if (empty == NotificationFeed.EMPTY_UNREAD) R.drawable.ic_notif_check_all else R.drawable.ic_notif_general,
            )
        }

        for (section in feed.sections) {
            val head = ItemNotificationSectionBinding.inflate(layoutInflater, b.notificationsList, false)
            head.notificationSection.text = section.day.label
            b.notificationsList.addView(head.root)
            section.rows.forEachIndexed { index, row ->
                val item = ItemNotificationBinding.inflate(layoutInflater, b.notificationsList, false)
                val (icon, bg, fg) = kindIcon(row.kind)
                item.notificationIcon.setImageResource(icon)
                item.notificationIcon.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bg))
                item.notificationIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, fg))
                item.notificationDivider.isVisible = index > 0
                item.notificationTitle.text = row.title
                item.notificationBody.text = row.body
                item.notificationBody.isVisible = row.body.isNotEmpty()
                item.notificationDate.text = row.time
                item.notificationUnreadDot.isVisible = row.unread
                item.notificationChevron.isVisible = row.link != null
                if (row.unread) {
                    item.notificationRow.setBackgroundColor(ContextCompat.getColor(ctx, R.color.notif_unread_tint))
                    item.notificationTitle.setTextColor(ContextCompat.getColor(ctx, R.color.vinor_text))
                    item.notificationTitle.setTypeface(item.notificationTitle.typeface, android.graphics.Typeface.BOLD)
                    item.notificationBody.setTextColor(ContextCompat.getColor(ctx, R.color.notif_body_unread))
                }
                item.notificationRow.contentDescription = buildString {
                    append(row.kind.label).append(": ").append(row.title)
                    if (row.unread) append("، خوانده‌نشده")
                    if (row.time.isNotEmpty()) append("، ").append(row.time)
                }
                item.notificationRow.setOnClickListener { openRow(row) }
                b.notificationsList.addView(item.root)
            }
        }
    }

    /** لمس ردیف: اگر خوانده‌نشده است فوراً خوانده‌شده نمایش داده و به سرور گفته می‌شود؛ بعد لینک داخلی باز می‌شود. */
    private fun openRow(row: NotificationFeed.Row) {
        if (row.unread && row.id.isNotBlank()) {
            markLocal(row.id)
            bind()
            markOneRead(row.id)
        }
        row.link?.let { (activity as? MainActivity)?.openPath(it) }
    }

    private fun markLocal(id: String?) {
        for (n in items) {
            if (id == null || n.optString("id") == id) n.put("is_read", true)
        }
        remembered?.optJSONArray("notifications")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                if (id == null || n.optString("id") == id) n.put("is_read", true)
            }
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
                val ok = withContext(Dispatchers.IO) { client.newCall(req).execute().use { it.isSuccessful } }
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.notificationsMarkAll.isEnabled = true
                    if (!ok) {
                        context?.let {
                            Toast.makeText(it, "خطا در بروزرسانی", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        markLocal(null)
                        bind()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "markAllRead error", e)
                withContext(Dispatchers.Main) {
                    _binding?.notificationsMarkAll?.isEnabled = true
                    context?.let {
                        Toast.makeText(it, "خطا در ارتباط", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * خواندن یک اعلان روی scope مستقل از View اجرا می‌شود تا رفتن به لینک (و بسته شدن این صفحه)
     * درخواست را لغو نکند.
     */
    private fun markOneRead(id: String) {
        val url = "$BASE" + API_MARK_ONE.format(id)
        val cookie = cookieHeader()
        markScope.launch {
            try {
                val req = Request.Builder()
                    .url(url)
                    .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .addHeader("Cookie", cookie)
                    .addHeader("Content-Type", "application/json")
                    .build()
                val ok = client.newCall(req).execute().use { it.isSuccessful }
                if (!ok) Log.w(TAG, "markOneRead failed for $id")
            } catch (e: Exception) {
                Log.e(TAG, "markOneRead error", e)
            }
        }
    }
}
