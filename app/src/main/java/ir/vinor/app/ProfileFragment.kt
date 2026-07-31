package ir.vinor.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.navigation.fragment.findNavController
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import ir.vinor.app.databinding.FragmentProfileBinding
import ir.vinor.app.databinding.ItemProfileLinkBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.webkit.CookieManager
import java.util.concurrent.TimeUnit

/**
 * تب من (پروفایل) - کاملاً نیتیو و هماهنگ با وب.
 * داده از API /express/partner/profile/data؛ خروج از /express/partner/api/logout.
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "ProfileFragment"
        private const val BASE = "https://vinor.ir"
        private const val PROFILE_DATA = "/express/partner/profile/data"
        private const val API_LOGOUT = "/express/partner/api/logout"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun cookieHeader(): String = CookieManager.getInstance().getCookie(BASE) ?: ""

    private fun appVersionName(): String = try {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun loadData() {
        if (_binding == null) return
        binding.profileProgress.visibility = View.VISIBLE
        binding.profileGuest.visibility = View.GONE
        binding.profileCard.visibility = View.GONE
        binding.profileLinks.visibility = View.GONE

        scope.launch {
            try {
                val req = Request.Builder()
                    .url("$BASE$PROFILE_DATA")
                    .addHeader("Cookie", cookieHeader())
                    .addHeader("Accept", "application/json")
                    .addHeader("X-Vinor-App-Version", appVersionName())
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) { showGuest() }
                    return@launch
                }
                val body = resp.body?.string() ?: ""
                val obj = JSONObject(body)
                if (!obj.optBoolean("success", false)) {
                    withContext(Dispatchers.Main) { showGuest() }
                    return@launch
                }
                val mePhone = obj.optString("me_phone", "").trim()
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.profileProgress.visibility = View.GONE
                    if (mePhone.isEmpty()) {
                        showGuest()
                    } else {
                        val apkUrl = obj.optString("android_apk_url", "").trim()
                        val apkVersion = obj.optString("android_apk_version", "").trim()
                        val showUpdate = obj.optBoolean("show_app_update", false) ||
                            (apkUrl.isNotEmpty() && apkVersion.isNotEmpty() &&
                                ApkVersionUtils.isServerNewer(apkVersion, appVersionName()))
                        bindProfile(
                            mePhone = mePhone,
                            meName = obj.optString("me_name", "همکار وینور"),
                            avatarUrl = obj.optString("avatar_url", "").takeIf { it.isNotEmpty() },
                            isApproved = obj.optBoolean("is_approved", false),
                            showAppUpdate = showUpdate,
                            androidApkUrl = if (showUpdate) apkUrl else "",
                            androidApkVersion = if (showUpdate) apkVersion else ""
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadData error", e)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.profileProgress.visibility = View.GONE
                    showGuest()
                }
            }
        }
    }

    private fun showGuest() {
        if (_binding == null) return
        binding.profileGuest.visibility = View.VISIBLE
        binding.profileCard.visibility = View.GONE
        binding.profileLinks.visibility = View.GONE
        binding.profileNotifications.visibility = View.GONE
        binding.profileGuestLogin.setOnClickListener {
            // استفاده از WebView داخلی اپ برای ورود
            try {
                findNavController().navigate(R.id.action_profile_to_login)
            } catch (_: Exception) {
                // در صورت بروز خطا در ناوبری، از مرورگر خارجی استفاده می‌کنیم
                openUrl("$BASE/express/partner/login?next=$BASE/express/partner/profile")
            }
        }
        binding.profileGuestApply.setOnClickListener { openUrl("$BASE/express/partner/apply/step1") }
    }

    private fun bindProfile(
        mePhone: String,
        meName: String,
        avatarUrl: String?,
        isApproved: Boolean,
        showAppUpdate: Boolean,
        androidApkUrl: String,
        androidApkVersion: String
    ) {
        if (_binding == null) return
        binding.profileGuest.visibility = View.GONE
        binding.profileCard.visibility = View.VISIBLE
        binding.profileLinks.visibility = View.VISIBLE
        binding.profileNotifications.visibility = View.VISIBLE

        binding.profileName.text = meName
        binding.profilePhone.text = mePhone
        binding.profileStatus.text = if (isApproved) "همکار تایید شده" else "کاربر عادی"
        val initials = when {
            meName.length >= 2 -> meName.take(2)
            mePhone.length >= 4 -> mePhone.takeLast(4).take(2)
            else -> "—"
        }
        binding.profileInitials.text = initials
        binding.profileInitials.visibility = View.VISIBLE
        binding.profileAvatar.visibility = View.GONE
        if (!avatarUrl.isNullOrEmpty()) {
            loadAvatar("$BASE$avatarUrl")
        }

        binding.profileCard.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_profile_to_profileEdit)
            } catch (_: Exception) {
                openUrl("$BASE/express/partner/profile/edit")
            }
        }
        binding.profileLogout.setOnClickListener { doLogout() }
        binding.profileNotifications.setOnClickListener {
            try {
                findNavController().navigate(R.id.notificationsFragment)
            } catch (_: Exception) {
                openUrl("$BASE/express/partner/notifications")
            }
        }

        binding.profileLinks.removeAllViews()
        val links = mutableListOf<Triple<String, String, String>>()
        if (!isApproved) {
            links.add(Triple("درخواست همکاری", "برای تبدیل به همکار اکسپرس درخواست دهید", "$BASE/express/partner/apply/step1"))
        }
        links.add(Triple("یادداشت‌ها", "یادداشت‌های خصوصی", "notes_native"))
        if (showAppUpdate && androidApkUrl.isNotEmpty()) {
            val appUpdateSub = if (androidApkVersion.isNotEmpty()) {
                "نسخه جدید برای دانلود آماده است (نسخه $androidApkVersion)"
            } else {
                "نسخه جدید برای دانلود آماده است"
            }
            links.add(
                Triple(
                    "بروزرسانی اپلیکیشن",
                    appUpdateSub,
                    "apk_update:$androidApkUrl|$androidApkVersion",
                ),
            )
        }
        links.add(Triple("فروشنده‌های برتر", "رتبه‌بندی همکاران", "$BASE/express/partner/top-sellers"))
        links.add(Triple("راهنما", "راهنمای استفاده از پلتفرم", "$BASE/express/partner/help"))
        links.add(Triple("تماس با پشتیبانی", "راهنمایی و پشتیبانی", "$BASE/express/partner/support"))
        links.add(Triple("اجرای مجدد تور راهنما", "مشاهده دوباره آموزش‌ها", "restart_tour"))

        val iconRes = mapOf(
            "درخواست همکاری" to R.drawable.ic_add,
            "یادداشت‌ها" to R.drawable.ic_help,
            "بروزرسانی اپلیکیشن" to R.drawable.ic_help,
            "فروشنده‌های برتر" to R.drawable.ic_commissions,
            "راهنما" to R.drawable.ic_help,
            "تماس با پشتیبانی" to R.drawable.ic_messages,
            "اجرای مجدد تور راهنما" to R.drawable.ic_help
        )
        for ((title, subtitle, url) in links) {
            val item = ItemProfileLinkBinding.inflate(layoutInflater, binding.profileLinks, false)
            item.itemProfileLinkTitle.text = title
            item.itemProfileLinkSubtitle.text = subtitle
            item.itemProfileLinkIcon.setImageResource(iconRes[title] ?: R.drawable.ic_help)
            item.root.setOnClickListener {
                when {
                    url == "restart_tour" -> {
                        context?.getSharedPreferences("vinor", 0)?.edit()?.putBoolean("restart_tour", true)?.apply()
                        context?.let { Toast.makeText(it, "برای اجرای تور به تب وینور بروید", Toast.LENGTH_SHORT).show() }
                    }
                    url == "notes_native" -> {
                        try {
                            findNavController().navigate(R.id.notesFragment)
                        } catch (_: Exception) {
                            openUrl("$BASE/express/partner/notes")
                        }
                    }
                    url.startsWith("apk_update:") -> {
                        val payload = url.removePrefix("apk_update:")
                        val sep = payload.indexOf('|')
                        val apkUrl = if (sep >= 0) payload.substring(0, sep) else payload
                        val ver = if (sep >= 0) payload.substring(sep + 1) else androidApkVersion
                        startApkUpdate(apkUrl, ver)
                    }
                    url.isBlank() -> { }
                    else -> openUrl(url)
                }
            }
            binding.profileLinks.addView(item.root)
        }
    }

    private fun loadAvatar(fullUrl: String) {
        scope.launch {
            try {
                val req = Request.Builder()
                    .url(fullUrl)
                    .addHeader("Cookie", cookieHeader())
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                if (!resp.isSuccessful) return@launch
                val bytes = resp.body?.bytes() ?: return@launch
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.profileAvatar.setImageBitmap(bmp)
                    binding.profileAvatar.visibility = View.VISIBLE
                    binding.profileInitials.visibility = View.GONE
                }
            } catch (_: Exception) { }
        }
    }

    private fun doLogout() {
        scope.launch {
            try {
                val req = Request.Builder()
                    .url("$BASE$API_LOGOUT")
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .addHeader("Cookie", cookieHeader())
                    .build()
                val ok = withContext(Dispatchers.IO) {
                    client.newCall(req).execute().use { it.isSuccessful }
                }
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    if (!ok) {
                        context?.let { Toast.makeText(it, "خطا در خروج", Toast.LENGTH_SHORT).show() }
                        return@withContext
                    }
                    VinorFcmRegistrar.unregisterCurrentToken(requireContext())
                    /* removeAllCookies ناهمزمان است؛ loadData فقط بعد از flush تا درخواست بعدی بدون کوکی قدیمی باشد */
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies { _ ->
                        cm.flush()
                        view?.post {
                            if (_binding == null) return@post
                            context?.let { Toast.makeText(it, "از حساب خارج شدید.", Toast.LENGTH_SHORT).show() }
                            loadData()
                            try {
                                findNavController().navigate(R.id.dashboardFragment)
                            } catch (_: Exception) {
                                openUrl(VinorPartnerUrls.ENTRY)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "logout error", e)
                withContext(Dispatchers.Main) {
                    context?.let { Toast.makeText(it, "خطا در ارتباط", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun startApkUpdate(apkUrl: String, version: String) {
        val ctx = context ?: return
        val abs = VinorDownloadBridge.resolveAbsoluteUrl(apkUrl)
        if (abs.isNullOrBlank()) {
            Toast.makeText(ctx, "آدرس بروزرسانی نامعتبر است", Toast.LENGTH_SHORT).show()
            return
        }
        val ver = version.trim().ifEmpty {
            VinorAppUpdateChecker.readCached(ctx).version
        }
        if (ver.isNotEmpty()) {
            VinorApkUpdateInstaller.ensureDownload(ctx.applicationContext, abs, ver)
        }
        val state = VinorApkUpdateInstaller.readState(ctx.applicationContext)
        if (state.status == VinorApkUpdateInstaller.Status.READY) {
            VinorApkUpdateInstaller.install(ctx)
        } else {
            Toast.makeText(ctx, "در حال آماده‌سازی فایل نصب…", Toast.LENGTH_SHORT).show()
            if (ver.isNotEmpty()) {
                VinorApkUpdateInstaller.ensureDownload(ctx.applicationContext, abs, ver)
            } else {
                VinorDownloadBridge.enqueueDownload(
                    ctx,
                    abs,
                    VinorDownloadBridge.apkDownloadFilename(abs, version),
                    showToast = true,
                )
            }
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        val absolute = when {
            url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring(7)
            url.startsWith("/") -> "$BASE$url"
            else -> url
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(absolute)))
        } catch (e: Exception) {
            context?.let { Toast.makeText(it, "خطا در باز کردن لینک", Toast.LENGTH_SHORT).show() }
        }
    }
}
