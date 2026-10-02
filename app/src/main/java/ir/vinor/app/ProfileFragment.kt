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
        private const val HOME_DATA = "/express/partner/home/data"
        private const val API_LOGOUT = "/express/partner/api/logout"
    }

    private var latestProfile: JSONObject? = null
    private var loadGen = 0
    private var crmFetchGen = -1

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
        binding.profileBack.setOnClickListener {
            if (!findNavController().navigateUp()) {
                (activity as? MainActivity)?.openPartnerHome()
            }
        }
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
        val ctx = context ?: return
        if (_binding == null) return
        loadGen++
        binding.profileProgress.visibility = View.VISIBLE
        binding.profileGuest.visibility = View.GONE
        binding.profileCard.visibility = View.GONE
        binding.profileLinks.visibility = View.GONE

        scope.launch {
            try {
                val obj = PartnerApi.loadDisplay(PROFILE_DATA, ctx) { data ->
                    if (_binding == null) return@loadDisplay
                    bindProfilePayload(data)
                }
                if (_binding == null) return@launch
                if (!obj.optBoolean("success", false) && obj.optString("me_phone").isBlank()) {
                    binding.profileProgress.visibility = View.GONE
                    showGuest()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadData error", e)
                if (_binding == null) return@launch
                binding.profileProgress.visibility = View.GONE
                showGuest()
            }
        }
    }

    private fun bindProfilePayload(obj: JSONObject) {
        if (!obj.optBoolean("success", false) && obj.optString("me_phone").isBlank()) return
        latestProfile = obj
        binding.profileProgress.visibility = View.GONE
        val plusOn = obj.optBoolean("plus_system_enabled", false)
        val lawyerOn = obj.optBoolean("lawyer_feature_on", false)
        val mePhone = obj.optString("me_phone", "").trim()
        if (mePhone.isEmpty()) {
            showGuest(plusOn = plusOn, lawyerOn = lawyerOn)
            return
        }
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
            isSpecial = obj.optBoolean("is_special", false),
            hasPendingApp = obj.optBoolean("has_pending_app", false),
            plusOn = plusOn,
            lawyerOn = lawyerOn,
            showAppUpdate = showUpdate,
            androidApkUrl = if (showUpdate) apkUrl else "",
            androidApkVersion = if (showUpdate) apkVersion else "",
            showCustomers = customersMenuVisible(obj),
        )
    }

    /** کلید حاضر در JSON؛ نبود کلید `null` است تا منبع دیگر (خانه) بررسی شود. */
    private fun flagOrNull(obj: JSONObject?, key: String): Boolean? {
        if (obj == null || !obj.has(key)) return null
        return obj.optBoolean(key, false)
    }

    private fun customersMenuVisible(profile: JSONObject): Boolean {
        if (!profile.optBoolean("is_approved", false)) return false
        val profileFlag = flagOrNull(profile, PartnerCrm.FLAG)
        if (profileFlag != null) return profileFlag
        val ctx = context
        val home = if (ctx != null) PartnerApi.peekJson(HOME_DATA, ctx) else null
        val homeFlag = flagOrNull(home, PartnerCrm.FLAG)
        val fresh = ctx != null && homeFlag != null && PartnerApi.isFresh(HOME_DATA, ctx)
        if (!fresh) refreshCrmFromHome(profile, PartnerCrm.showMenu(true, null, homeFlag))
        return PartnerCrm.showMenu(true, null, homeFlag)
    }

    private fun refreshCrmFromHome(profile: JSONObject, shown: Boolean) {
        val ctx = context ?: return
        val gen = loadGen
        if (crmFetchGen == gen) return
        crmFetchGen = gen
        scope.launch {
            val home = withContext(Dispatchers.IO) {
                runCatching { PartnerApi.get(HOME_DATA, ctx) }.getOrNull()
            } ?: return@launch
            if (gen != loadGen || _binding == null) return@launch
            val visible = PartnerCrm.showMenu(
                profile.optBoolean("is_approved", false),
                null,
                flagOrNull(home, PartnerCrm.FLAG),
            )
            if (visible == shown) return@launch
            bindProfilePayload(latestProfile ?: profile)
        }
    }

    private fun showGuest(plusOn: Boolean = false, lawyerOn: Boolean = false) {
        if (_binding == null) return
        binding.profileGuest.visibility = View.VISIBLE
        binding.profileCard.visibility = View.GONE
        binding.profileLinks.visibility = View.VISIBLE
        binding.profileNotifications.visibility = View.GONE
        binding.profileGuestLogin.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_profile_to_login)
            } catch (_: Exception) {
                (activity as? MainActivity)?.openLogin()
            }
        }
        binding.profileGuestApply.setOnClickListener { openUrl("$BASE/express/partner/apply/step1") }
        binding.profileLinks.removeAllViews()
        addMenuRow("ملک من", "ثبت ملک و انتشار آگهی", R.drawable.ic_home, "$BASE/express/partner/my-property")
        addMenuRow("آموزش", "", R.drawable.ic_graduation, "$BASE/express/partner/training")
        addMenuRow("بازاریاب وینور", "", R.drawable.ic_person_add, "$BASE/express/partner/apply/step1")
        if (plusOn) addMenuRow("وینور پلاس", "دریافت اشتراک", R.drawable.ic_sparkle, "login")
        if (lawyerOn) addMenuRow("وکیل ملکی", "برای استفاده وارد شوید", R.drawable.ic_scale, "login")
    }

    private fun bindProfile(
        mePhone: String,
        meName: String,
        avatarUrl: String?,
        isApproved: Boolean,
        isSpecial: Boolean,
        hasPendingApp: Boolean,
        plusOn: Boolean,
        lawyerOn: Boolean,
        showAppUpdate: Boolean,
        androidApkUrl: String,
        androidApkVersion: String,
        showCustomers: Boolean,
    ) {
        if (_binding == null) return
        binding.profileGuest.visibility = View.GONE
        binding.profileCard.visibility = View.VISIBLE
        binding.profileLinks.visibility = View.VISIBLE
        binding.profileNotifications.visibility = View.VISIBLE

        binding.profileName.text = meName
        binding.profilePhone.text = mePhone
        binding.profileStatus.text = when {
            isSpecial -> "پلاس"
            isApproved -> "بازاریاب"
            else -> "عادی"
        }
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
        addMenuRow("ملک من", "ثبت ملک و انتشار آگهی", R.drawable.ic_home, "$BASE/express/partner/my-property")
        if (isApproved) {
            addMenuRow("پیگیری", "درخواست، بازدید و معامله", R.drawable.ic_routine, "$BASE/express/partner/requests")
            if (showCustomers) {
                addMenuRow("مشتریان", "", R.drawable.ic_users_stroke, "$BASE${PartnerCrm.PATH}")
            }
            addMenuRow("کیف پول", "", R.drawable.ic_wallet, "$BASE/express/partner/commissions")
            addMenuRow("نشان‌ها", "", R.drawable.ic_bookmark, "$BASE/express/partner/favorites")
            addMenuRow("آموزش", "", R.drawable.ic_graduation, "$BASE/express/partner/training")
            addMenuRow("دعوت همکار", "", R.drawable.ic_person_add, "$BASE/express/partner/invite")
        } else {
            if (hasPendingApp) {
                addMenuRow("وضعیت بازاریاب وینور", "", R.drawable.ic_clock, "$BASE/express/partner/thanks")
            } else {
                addMenuRow("بازاریاب وینور", "", R.drawable.ic_person_add, "$BASE/express/partner/apply/step1")
            }
            addMenuRow("دعوت دوستان", "", R.drawable.ic_share_nodes, "$BASE/express/partner/invite")
            addMenuRow("آموزش", "", R.drawable.ic_graduation, "$BASE/express/partner/training")
        }
        if (plusOn) {
            addMenuRow(
                if (isSpecial) "وینور پلاس" else "دریافت وینور پلاس",
                "",
                R.drawable.ic_sparkle,
                "$BASE/express/partner/plus",
            )
        }
        if (lawyerOn) {
            addMenuRow("وکیل ملکی", "مشاوره حقوقی املاک ایران", R.drawable.ic_scale, "lawyer")
        }
        if (showAppUpdate && androidApkUrl.isNotEmpty()) {
            val appUpdateSub = if (androidApkVersion.isNotEmpty()) {
                "نسخه جدید برای دانلود آماده است (نسخه $androidApkVersion)"
            } else {
                "نسخه جدید برای دانلود آماده است"
            }
            addMenuRow(
                "بروزرسانی اپلیکیشن",
                appUpdateSub,
                R.drawable.ic_help,
                "apk_update:$androidApkUrl|$androidApkVersion",
            )
        }
        addMenuRow("تنظیمات", "", R.drawable.ic_settings, "$BASE/express/partner/settings")
    }

    private fun addMenuRow(title: String, subtitle: String, icon: Int, url: String) {
        val item = ItemProfileLinkBinding.inflate(layoutInflater, binding.profileLinks, false)
        item.itemProfileLinkTitle.text = title
        item.itemProfileLinkSubtitle.text = subtitle
        item.itemProfileLinkSubtitle.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        item.itemProfileLinkIcon.setImageResource(icon)
        item.root.setOnClickListener {
            when {
                url == "login" -> (activity as? MainActivity)?.openLogin()
                url == "lawyer" -> (activity as? MainActivity)?.openVinorChat()
                url.startsWith("apk_update:") -> {
                    val payload = url.removePrefix("apk_update:")
                    val sep = payload.indexOf('|')
                    val apkUrl = if (sep >= 0) payload.substring(0, sep) else payload
                    val ver = if (sep >= 0) payload.substring(sep + 1) else ""
                    startApkUpdate(apkUrl, ver)
                }
                url.isBlank() -> { }
                else -> openUrl(url)
            }
        }
        binding.profileLinks.addView(item.root)
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
            VinorFcmRegistrar.unregisterCurrentToken(requireContext())
            try {
                withContext(Dispatchers.IO) {
                    PartnerApi.postJson(API_LOGOUT, JSONObject(), requireContext())
                }
            } catch (e: Exception) {
                Log.e(TAG, "logout error", e)
            }
            PartnerApi.clearCookies {
                if (_binding == null) return@clearCookies
                context?.let { Toast.makeText(it, "از حساب خارج شدید.", Toast.LENGTH_SHORT).show() }
                val host = activity as? MainActivity
                if (host != null) {
                    host.openPartnerHome()
                } else {
                    try {
                        findNavController().navigate(R.id.dashboardFragment)
                    } catch (_: Exception) {
                        openUrl(VinorPartnerUrls.ENTRY)
                    }
                }
            }
        }
    }

    private fun startApkUpdate(apkUrl: String, version: String) {
        val ctx = context ?: return
        val state = VinorAppUpdateController.read(ctx.applicationContext)
        if (state.status == VinorAppUpdateStatus.READY) {
            VinorApkUpdateInstaller.install(ctx)
            return
        }
        Toast.makeText(ctx, "در حال آماده‌سازی فایل نصب…", Toast.LENGTH_SHORT).show()
        if (state.show) {
            VinorAppUpdateController.retryDownload(ctx.applicationContext)
        } else {
            VinorAppUpdateController.check(ctx.applicationContext, force = true)
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
        val act = activity as? MainActivity
        if (act != null && (absolute.contains("vinor.ir") || absolute.startsWith("/"))) {
            act.openPath(absolute)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(absolute)))
        } catch (e: Exception) {
            context?.let { Toast.makeText(it, "خطا در باز کردن لینک", Toast.LENGTH_SHORT).show() }
        }
    }
}
