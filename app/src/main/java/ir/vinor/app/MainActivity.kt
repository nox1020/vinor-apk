package ir.vinor.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import ir.vinor.app.databinding.ActivityMainBinding
import ir.vinor.app.databinding.ItemDrawerNavBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** شِل نیتیو همکار: خانه مثل وب + کشوی GPT — بدون تب پایین. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var notifyHelper: VinorNotifyHelper? = null
    private var keepSystemSplash = true
    private var drawerPhoneNormalized = ""
    private var drawerApproved = false
    private var drawerPendingApp = false
    private var resendLeft = 0
    private var drawerOtpPending = false
    private var drawerSmsReceiver: OtpSmsAutoFill.SmsRetrieverReceiver? = null
    private var otpSubmitting = false
    private var pendingOpenVinorChat = false
    private val resendHandler = Handler(Looper.getMainLooper())
    private val resendTick = object : Runnable {
        override fun run() {
            if (!::binding.isInitialized) return
            resendLeft -= 1
            if (resendLeft <= 0) {
                binding.drawerResend.isEnabled = true
                binding.drawerResend.setTextColor(Color.parseColor("#8E8E93"))
                binding.drawerResendTimer.text = ""
            } else {
                binding.drawerResendTimer.text = "${resendLeft}ث"
                resendHandler.postDelayed(this, 1000)
            }
        }
    }

    private val navController: NavController
        get() = (supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(VinorLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { keepSystemSplash }
        super.onCreate(savedInstanceState)
        SystemUi.apply(this)
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.bottomNav.visibility = View.GONE
        sizeDrawer()
        wireDrawer()

        notifyHelper = VinorNotifyHelper(
            this,
            webViewProvider = { null },
            onOpenNotifications = { openNotifications() },
        ).also { helper ->
            helper.attach(this)
            helper.requestPermissionIfNeeded()
            helper.handleOpenIntent(intent, immediate = true)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDrawerOpen()) {
                    closeDrawer()
                    return
                }
                val dash = findDashboard()
                if (dash != null && dash.closeOverlays()) return
                val web = findWebPage()
                if (web != null && web.goBackInWebView()) return
                if (!navController.popBackStack()) finish()
            }
        })

        handleDeepLink(intent)
        runCatching { VinorCacheSession.fingerprint(this) }
        runCatching { VinorApiCacheStore.warmup(this) }
        runCatching { VinorAppUpdateChecker.check(this, webView = null) }
        runCatching { VinorFcmRegistrar.requestTokenAndRegister(this) }
        keepSystemSplash = false
        updateOfflineBanner()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notifyHelper?.handleOpenIntent(intent, immediate = true)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        VinorAppUpdateController.onReturnedFromUnknownSources(this)
        VinorAppUpdateChecker.check(this, webView = null)
        updateOfflineBanner()
        if (isDrawerOpen()) syncDrawerSession()
    }

    override fun onDestroy() {
        resendHandler.removeCallbacks(resendTick)
        stopDrawerSmsListen()
        notifyHelper?.destroy()
        super.onDestroy()
    }

    fun requestNotificationPermission() {
        notifyHelper?.requestPermissionIfNeeded()
    }

    fun setBottomNavVisible(visible: Boolean) {
        if (!::binding.isInitialized) return
        binding.bottomNav.visibility = View.GONE
    }

    fun isDrawerOpen(): Boolean = ::binding.isInitialized && binding.drawerPanel.isVisible

    fun openDrawer() {
        if (!::binding.isInitialized) return
        binding.drawerUserMenu.isVisible = false
        sizeDrawer()
        syncDrawerSession()
        binding.drawerBackdrop.animate().cancel()
        binding.drawerPanel.animate().cancel()
        binding.drawerBackdrop.visibility = View.VISIBLE
        binding.drawerPanel.visibility = View.VISIBLE
        binding.drawerBackdrop.alpha = 0f
        val hideX = binding.drawerPanel.layoutParams.width.toFloat().coerceAtLeast(1f)
        binding.drawerPanel.translationX = hideX
        findDashboard()?.setHeaderVisible(false)
        binding.drawerPanel.post {
            if (!binding.drawerPanel.isVisible) return@post
            val w = binding.drawerPanel.width.toFloat().coerceAtLeast(hideX)
            binding.drawerPanel.translationX = w
            binding.drawerBackdrop.animate().alpha(1f).setDuration(200).start()
            binding.drawerPanel.animate().translationX(0f).setDuration(280).start()
        }
    }

    fun closeDrawer() {
        if (!::binding.isInitialized || !binding.drawerPanel.isVisible) return
        binding.drawerBackdrop.animate().cancel()
        binding.drawerPanel.animate().cancel()
        val w = binding.drawerPanel.width.toFloat().coerceAtLeast(1f)
        findDashboard()?.setHeaderVisible(true)
        binding.drawerUserMenu.isVisible = false
        binding.drawerBackdrop.animate().alpha(0f).setDuration(180).withEndAction {
            binding.drawerBackdrop.visibility = View.GONE
        }.start()
        binding.drawerPanel.animate().translationX(w).setDuration(220).withEndAction {
            binding.drawerPanel.visibility = View.GONE
            binding.drawerPanel.translationX = 0f
        }.start()
    }

    fun openPartnerHome() {
        closeDrawer()
        pendingOpenVinorChat = false
        if (::binding.isInitialized) syncDrawerSession()
        val nav = navController
        if (!nav.popBackStack(R.id.dashboardFragment, false)) {
            nav.navigate(R.id.dashboardFragment)
        }
        findDashboard()?.reload()
    }

    fun openVinorChat() {
        closeDrawer()
        val nav = navController
        val dash = findDashboard()
        if (nav.currentDestination?.id == R.id.dashboardFragment && dash != null) {
            dash.openVinorChat()
            return
        }
        pendingOpenVinorChat = true
        if (!nav.popBackStack(R.id.dashboardFragment, false)) {
            nav.navigate(R.id.dashboardFragment)
        }
        binding.root.post {
            findDashboard()?.openVinorChat()
            pendingOpenVinorChat = false
        }
    }

    fun consumePendingVinorChat(): Boolean {
        val pending = pendingOpenVinorChat
        pendingOpenVinorChat = false
        return pending
    }

    fun openLogin() {
        closeDrawer()
        if (navController.currentDestination?.id != R.id.loginStep1Fragment) {
            navController.navigate(R.id.loginStep1Fragment)
        }
    }

    fun openProfile() {
        closeDrawer()
        if (navController.currentDestination?.id != R.id.profileFragment) {
            navController.navigate(R.id.profileFragment)
        }
    }

    fun openNotifications() {
        closeDrawer()
        if (navController.currentDestination?.id != R.id.notificationsFragment) {
            navController.navigate(R.id.notificationsFragment)
        }
    }

    fun openLand(code: String) {
        closeDrawer()
        val args = Bundle().apply { putString("code", code) }
        navController.navigate(R.id.landDetailFragment, args)
    }

    fun reloadHome() {
        findDashboard()?.reload()
    }

    private fun findDashboard(): DashboardFragment? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        return host?.childFragmentManager?.primaryNavigationFragment as? DashboardFragment
    }

    private fun findWebPage(): BaseWebViewFragment? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        return host?.childFragmentManager?.primaryNavigationFragment as? BaseWebViewFragment
    }

    private fun sizeDrawer() {
        val dm = resources.displayMetrics
        val rem = 16f * dm.density
        val peek = (3.25f * rem).toInt()
        val cap = (26f * rem).toInt()
        val width = minOf(
            (dm.widthPixels * 0.85f).toInt(),
            cap,
            (dm.widthPixels - peek).coerceAtLeast(1),
        )
        val lp = binding.drawerPanel.layoutParams
        lp.width = width.coerceAtLeast(1)
        binding.drawerPanel.layoutParams = lp
    }

    private fun wireDrawer() {
        bindNavRows()
        binding.drawerBackdrop.setOnClickListener { closeDrawer() }
        binding.drawerVinorChat.setOnClickListener { openVinorChat() }
        binding.drawerPostchi.setOnClickListener { openNotifications() }
        binding.drawerTraining.root.setOnClickListener { openTraining() }
        binding.drawerTrainingStart.root.setOnClickListener { openTraining() }
        binding.drawerTrainingApproved.root.setOnClickListener { openTraining() }
        binding.drawerApply.root.setOnClickListener {
            openPath(if (drawerPendingApp) "/express/partner/thanks" else "/express/partner/apply")
        }
        binding.drawerInvite.root.setOnClickListener { openPath("/express/partner/invite") }
        binding.drawerInviteStart.root.setOnClickListener { openPath("/express/partner/invite") }
        binding.drawerRoutine.root.setOnClickListener { openPath("/express/partner/requests") }
        binding.drawerWallet.root.setOnClickListener {
            closeDrawer()
            if (navController.currentDestination?.id != R.id.commissionsFragment) {
                navController.navigate(R.id.commissionsFragment)
            }
        }
        binding.drawerFavorites.root.setOnClickListener { openPath("/express/partner/favorites") }
        binding.drawerListings.root.setOnClickListener { openPath("/express/partner/listings") }
        binding.drawerUserCard.setOnClickListener {
            closeDrawer()
            if (navController.currentDestination?.id != R.id.profileEditFragment) {
                navController.navigate(R.id.profileEditFragment)
            }
        }
        binding.drawerUserRole.setOnClickListener { openPath("/express/partner/levels") }
        binding.drawerUserMore.setOnClickListener {
            binding.drawerUserMenu.isVisible = !binding.drawerUserMenu.isVisible
        }
        binding.drawerSettings.setOnClickListener { openPath("/express/partner/settings") }
        binding.drawerLogout.setOnClickListener { logoutFromDrawer() }
        binding.drawerLoginSubmit.setOnClickListener { submitDrawerPhone() }
        binding.drawerOtpSubmit.setOnClickListener { submitDrawerOtp() }
        binding.drawerChangePhone.setOnClickListener { showDrawerPhoneStep() }
        binding.drawerResend.setOnClickListener { resendDrawerOtp() }
        binding.drawerPhone.addTextChangedListener(simpleWatcher { syncDrawerPhoneEnabled() })
        binding.drawerOtp.addTextChangedListener(simpleWatcher {
            syncDrawerOtpEnabled()
            val digits = OtpSmsAutoFill.normalizeOtpDigits(binding.drawerOtp.text?.toString())
            if (digits.length >= 5) submitDrawerOtp()
        })
        bindDrawerLegal()
        syncDrawerPhoneEnabled()
        syncDrawerOtpEnabled()
    }

    private fun bindNavRows() {
        fun row(b: ItemDrawerNavBinding, icon: Int, title: String) {
            b.drawerRowIcon.setImageResource(icon)
            b.drawerRowTitle.text = title
        }
        row(binding.drawerTraining, R.drawable.ic_graduation, "آموزش")
        row(binding.drawerTrainingStart, R.drawable.ic_graduation, "آموزش")
        row(binding.drawerTrainingApproved, R.drawable.ic_graduation, "آموزش")
        row(binding.drawerApply, R.drawable.ic_person_add, "درخواست همکاری")
        row(binding.drawerInviteStart, R.drawable.ic_share_nodes, "دعوت دوستان")
        row(binding.drawerInvite, R.drawable.ic_person_add, "دعوت همکار")
        row(binding.drawerRoutine, R.drawable.ic_routine, "پیگیری")
        row(binding.drawerListings, R.drawable.ic_building, "آگهی‌های من")
        binding.drawerNavPlus.isVisible = false
        row(binding.drawerWallet, R.drawable.ic_wallet, "کیف پول")
        row(binding.drawerFavorites, R.drawable.ic_bookmark, "نشان‌ها")
    }

    private fun openTraining() = openPath("/express/partner/training")

    fun openPath(path: String) {
        closeDrawer()
        val uri = Uri.parse(if (path.startsWith("http")) path else "${PartnerApi.BASE}$path")
        val p = (uri.path ?: "/").trimEnd('/').ifBlank { "/" }
        if (p == "/express/partner/home" || p == "/express/partner") {
            val land = uri.getQueryParameter("land")
            if (!land.isNullOrBlank()) openLand(land) else openPartnerHome()
            return
        }
        val args = Bundle().apply { putString(PartnerPageFragment.ARG_URL, uri.toString()) }
        navController.navigate(R.id.partnerPageFragment, args)
    }

    fun consumePendingDeepLink(): Boolean {
        val pending = DeepLinkPendingStore.consume(this) ?: return false
        handleDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse(pending)))
        return true
    }

    private fun bindDrawerLegal() {
        val full = "با ادامه، شرایط و حریم خصوصی را می‌پذیرید."
        val spanned = SpannableString(full)
        fun link(label: String, path: String) {
            val start = full.indexOf(label)
            if (start < 0) return
            spanned.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openPath(path)
                }
                override fun updateDrawState(ds: TextPaint) {
                    ds.color = Color.parseColor("#E4E4E7")
                    ds.isUnderlineText = true
                }
            }, start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        link("شرایط", "/express/partner/legal/terms")
        link("حریم خصوصی", "/privacy")
        binding.drawerLegal.text = spanned
        binding.drawerLegal.movementMethod = LinkMovementMethod.getInstance()
        binding.drawerLegal.highlightColor = Color.TRANSPARENT
    }

    private fun syncDrawerSession() {
        val session = PartnerApi.hasSession()
        binding.drawerUserMenu.isVisible = false
        if (drawerOtpPending && !session) {
            binding.drawerGuestLogin.isVisible = true
            binding.drawerUserFoot.isVisible = false
            binding.drawerPostchi.isVisible = false
            binding.drawerChatLabel.text = "گفتگو"
            binding.drawerNavGuest.isVisible = true
            binding.drawerNavStart.isVisible = false
            binding.drawerNavApproved.isVisible = false
            binding.drawerNavPlus.isVisible = false
            return
        }
        binding.drawerGuestLogin.isVisible = !session
        binding.drawerUserFoot.isVisible = session
        binding.drawerPostchi.isVisible = session
        binding.drawerChatLabel.text = if (session) "گفتگوها" else "گفتگو"
        if (!session) {
            binding.drawerNavGuest.isVisible = true
            binding.drawerNavStart.isVisible = false
            binding.drawerNavApproved.isVisible = false
            binding.drawerNavPlus.isVisible = false
            if (!drawerOtpPending) showDrawerPhoneStep()
            return
        }
        binding.drawerNavGuest.isVisible = false
        stopDrawerSmsListen()
        refreshDrawerProfile()
    }

    private fun refreshDrawerProfile() {
        lifecycleScope.launch {
            var approved = drawerApproved
            var pending = drawerPendingApp
            var name = "همکار وینور"
            var phone = ""
            var avatar = ""
            var special = false
            var tier = ""
            var wallet = 0
            fun applyHome(home: JSONObject) {
                if (home.has("is_approved")) approved = home.optBoolean("is_approved")
                pending = home.optBoolean("has_pending_app")
                wallet = home.optInt("withdrawable_balance")
            }
            fun applyProfile(profile: JSONObject) {
                if (profile.optString("me_phone").isNotBlank()) {
                    phone = profile.optString("me_phone")
                    name = profile.optString("me_name").ifBlank { "همکار وینور" }
                    avatar = profile.optString("avatar_url")
                    approved = profile.optBoolean("is_approved")
                    special = profile.optBoolean("is_special")
                    tier = profile.optString("partner_tier")
                }
            }
            PartnerApi.peekJson("/express/partner/profile/data", this@MainActivity)?.let { applyProfile(it) }
            PartnerApi.peekJson("/express/partner/home/data", this@MainActivity)?.let { applyHome(it) }
            if (phone.isNotBlank() && ::binding.isInitialized) bindDrawerProfileUi(
                approved, pending, name, phone, avatar, special, tier, wallet,
            )
            try {
                val profile = withContext(Dispatchers.IO) {
                    PartnerApi.get("/express/partner/profile/data", this@MainActivity)
                }
                applyProfile(profile)
            } catch (_: Exception) {
            }
            try {
                val home = withContext(Dispatchers.IO) {
                    PartnerApi.get("/express/partner/home/data", this@MainActivity)
                }
                applyHome(home)
            } catch (_: Exception) {
            }
            if (!::binding.isInitialized) return@launch
            bindDrawerProfileUi(approved, pending, name, phone, avatar, special, tier, wallet)
        }
    }

    private fun bindDrawerProfileUi(
        approved: Boolean,
        pending: Boolean,
        name: String,
        phone: String,
        avatar: String,
        special: Boolean,
        tier: String,
        wallet: Int,
    ) {
        val loggedIn = phone.isNotBlank() && PartnerApi.hasSession()
        if (!loggedIn) {
            if (drawerOtpPending) return
            binding.drawerGuestLogin.isVisible = true
            binding.drawerUserFoot.isVisible = false
            binding.drawerPostchi.isVisible = false
            binding.drawerNavGuest.isVisible = true
            binding.drawerNavStart.isVisible = false
            binding.drawerNavApproved.isVisible = false
            binding.drawerNavPlus.isVisible = false
            binding.drawerChatLabel.text = "گفتگو"
            return
        }
        drawerApproved = approved
        drawerPendingApp = pending
        binding.drawerGuestLogin.isVisible = false
        binding.drawerUserFoot.isVisible = true
        binding.drawerPostchi.isVisible = true
        binding.drawerNavGuest.isVisible = false
        binding.drawerChatLabel.text = "گفتگوها"
        binding.drawerNavStart.isVisible = !approved
        binding.drawerNavApproved.isVisible = approved
        binding.drawerNavPlus.isVisible = special
        binding.drawerApply.drawerRowTitle.text = if (pending) "وضعیت درخواست" else "درخواست همکاری"
        binding.drawerApply.drawerRowIcon.setImageResource(
            if (pending) R.drawable.ic_clock else R.drawable.ic_person_add
        )
        binding.drawerWallet.drawerRowMeta.isVisible = true
        binding.drawerWallet.drawerRowMeta.text = PartnerFormat.toman(wallet) + " تومان"
        binding.drawerUserName.text = name
        binding.drawerUserPhone.text = phone
        val initials = when {
            name.isNotBlank() && name != "همکار وینور" -> name.take(1)
            phone.length >= 2 -> phone.takeLast(2)
            else -> "و"
        }
        binding.drawerUserInitials.text = initials
        val role = when {
            special || tier.contains("پلاس") -> Triple("پلاس", R.drawable.bg_drawer_role_plus, "#FBBF24")
            approved || tier.contains("بازاریاب") -> Triple("بازاریاب", R.drawable.bg_drawer_role_marketer, "#2DD4BF")
            else -> Triple("عادی", R.drawable.bg_drawer_role_normal, "#9EFFFFFF")
        }
        binding.drawerUserRole.text = role.first
        binding.drawerUserRole.setBackgroundResource(role.second)
        binding.drawerUserRole.setTextColor(Color.parseColor(role.third))
        if (avatar.isNotBlank()) {
            PartnerImages.roundClip(binding.drawerUserAvatar, 19f)
            PartnerImages.bind(lifecycleScope, binding.drawerUserAvatar, avatar)
            binding.drawerUserAvatar.isVisible = true
            binding.drawerUserInitials.isVisible = false
        } else {
            binding.drawerUserAvatar.isVisible = false
            binding.drawerUserInitials.isVisible = true
        }
    }

    private fun showDrawerPhoneStep() {
        if (!::binding.isInitialized) return
        drawerOtpPending = false
        stopDrawerSmsListen()
        binding.drawerPhoneStep.isVisible = true
        binding.drawerOtpStep.isVisible = false
        binding.drawerOtp.setText("")
        binding.drawerOtpHint.isVisible = false
        binding.drawerLoginHint.isVisible = false
        drawerPhoneNormalized = ""
        resendHandler.removeCallbacks(resendTick)
        binding.drawerResend.isEnabled = false
        binding.drawerResend.setTextColor(Color.parseColor("#8E8E93"))
        binding.drawerResendTimer.text = ""
        syncDrawerPhoneEnabled()
    }

    private fun startResendTimer() {
        resendHandler.removeCallbacks(resendTick)
        resendLeft = 60
        binding.drawerResend.isEnabled = false
        binding.drawerResend.setTextColor(Color.parseColor("#8E8E93"))
        binding.drawerResendTimer.text = "${resendLeft}ث"
        resendHandler.postDelayed(resendTick, 1000)
    }

    private fun resendDrawerOtp() {
        val phone = drawerPhoneNormalized.ifBlank { PartnerApi.normalizePhone(binding.drawerPhone.text?.toString().orEmpty()) }
        if (phone.length != 11) return
        binding.drawerResend.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/otp/resend", JSONObject().put("phone", phone), this@MainActivity)
                }
                val ok = resp.optBoolean("ok") || resp.optBoolean("success")
                if (ok) {
                    showOtpHint(resp.optString("message").ifBlank { "کد مجدد ارسال شد." }, ok = true)
                    startResendTimer()
                } else {
                    showOtpHint(resp.optString("error").ifBlank { "ارسال مجدد ممکن نشد." })
                    binding.drawerResend.isEnabled = true
                }
            } catch (_: Exception) {
                showOtpHint("ارتباط برقرار نشد.")
                binding.drawerResend.isEnabled = true
            }
        }
    }

    private fun logoutFromDrawer() {
        binding.drawerUserMenu.isVisible = false
        lifecycleScope.launch {
            VinorFcmRegistrar.unregisterCurrentToken(this@MainActivity)
            try {
                withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/api/logout", JSONObject(), this@MainActivity)
                }
            } catch (_: Exception) {
            }
            PartnerApi.clearCookies {
                drawerOtpPending = false
                Toast.makeText(this@MainActivity, "از حساب خارج شدید.", Toast.LENGTH_SHORT).show()
                syncDrawerSession()
                openPartnerHome()
            }
        }
    }

    private fun syncDrawerPhoneEnabled() {
        val on = PartnerApi.normalizePhone(binding.drawerPhone.text?.toString().orEmpty()).length == 11
        binding.drawerLoginSubmit.isEnabled = on
        binding.drawerLoginSubmit.alpha = if (on) 1f else 0.45f
    }

    private fun syncDrawerOtpEnabled() {
        val on = OtpSmsAutoFill.normalizeOtpDigits(binding.drawerOtp.text?.toString()).length >= 5
        binding.drawerOtpSubmit.isEnabled = on
        binding.drawerOtpSubmit.alpha = if (on) 1f else 0.45f
    }

    private fun submitDrawerPhone() {
        val phone = PartnerApi.normalizePhone(binding.drawerPhone.text?.toString().orEmpty())
        binding.drawerLoginHint.isVisible = false
        if (phone.length != 11 || !phone.startsWith("09")) {
            binding.drawerLoginHint.text = "شماره موبایل ۱۱ رقمی معتبر وارد کنید."
            binding.drawerLoginHint.isVisible = true
            return
        }
        binding.drawerLoginSubmit.isEnabled = false
        lifecycleScope.launch {
            try {
                val body = JSONObject().put("phone", phone).put("accept_legal", true)
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/api/login-request", body, this@MainActivity)
                }
                if (resp.optBoolean("success")) {
                    drawerPhoneNormalized = phone
                    drawerOtpPending = true
                    binding.drawerOtpPhoneLabel.text = formatPhoneDisplay(phone)
                    binding.drawerPhoneStep.isVisible = false
                    binding.drawerOtpStep.isVisible = true
                    binding.drawerOtpHint.isVisible = false
                    binding.drawerOtp.requestFocus()
                    startResendTimer()
                    startDrawerSmsListen()
                } else {
                    binding.drawerLoginHint.text = resp.optString("error").ifBlank { "ارسال کد ممکن نشد." }
                    binding.drawerLoginHint.isVisible = true
                }
            } catch (_: Exception) {
                binding.drawerLoginHint.text = "ارتباط برقرار نشد. دوباره تلاش کنید."
                binding.drawerLoginHint.isVisible = true
            } finally {
                syncDrawerPhoneEnabled()
            }
        }
    }

    private fun submitDrawerOtp() {
        if (otpSubmitting) return
        val phone = drawerPhoneNormalized.ifBlank {
            PartnerApi.normalizePhone(binding.drawerPhone.text?.toString().orEmpty())
        }
        val code = OtpSmsAutoFill.normalizeOtpDigits(binding.drawerOtp.text?.toString())
        binding.drawerOtpHint.isVisible = false
        if (code.length < 5) {
            showOtpHint("کد ۵ رقمی را وارد کنید.")
            return
        }
        otpSubmitting = true
        binding.drawerOtpSubmit.isEnabled = false
        lifecycleScope.launch {
            try {
                val body = JSONObject().put("code", code).put("phone", phone)
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/api/verify", body, this@MainActivity)
                }
                if (resp.optBoolean("success")) {
                    PartnerApi.markLoggedIn()
                    drawerOtpPending = false
                    stopDrawerSmsListen()
                    VinorCacheSession.fingerprint(this@MainActivity)
                    VinorApiCacheStore.warmup(this@MainActivity)
                    VinorFcmRegistrar.registerAfterLogin(this@MainActivity)
                    requestNotificationPermission()
                    showDrawerPhoneStep()
                    closeDrawer()
                    syncDrawerSession()
                    if (!consumePendingDeepLink()) openPartnerHome()
                } else {
                    showOtpHint(resp.optString("error").ifBlank { "کد نادرست است." })
                }
            } catch (_: Exception) {
                showOtpHint("ارتباط برقرار نشد. دوباره تلاش کنید.")
            } finally {
                otpSubmitting = false
                syncDrawerOtpEnabled()
            }
        }
    }

    private fun showOtpHint(msg: String, ok: Boolean = false) {
        if (!::binding.isInitialized) return
        if (msg.isBlank()) {
            binding.drawerOtpHint.isVisible = false
            return
        }
        binding.drawerOtpHint.text = msg
        binding.drawerOtpHint.setTextColor(Color.parseColor(if (ok) "#86EFAC" else "#FDA4AF"))
        binding.drawerOtpHint.isVisible = true
    }

    private fun formatPhoneDisplay(phone: String): String {
        val d = PartnerApi.normalizePhone(phone)
        return if (d.length >= 11) "${d.take(4)} ${d.substring(4, 7)} ${d.substring(7, 11)}" else d
    }

    private fun startDrawerSmsListen() {
        stopDrawerSmsListen()
        val receiver = OtpSmsAutoFill.SmsRetrieverReceiver(
            onCode = { code ->
                runOnUiThread {
                    if (!::binding.isInitialized || !binding.drawerOtpStep.isVisible) return@runOnUiThread
                    val digits = OtpSmsAutoFill.normalizeOtpDigits(code)
                    if (digits.length != 5) return@runOnUiThread
                    binding.drawerOtp.setText(digits)
                    binding.drawerOtp.setSelection(digits.length)
                    submitDrawerOtp()
                }
            },
            onTimeout = {
                runOnUiThread { OtpSmsAutoFill.startSmsRetriever(this) }
            },
        )
        drawerSmsReceiver = receiver
        OtpSmsAutoFill.registerSmsRetrieverReceiver(this, receiver)
        OtpSmsAutoFill.startSmsRetriever(this)
    }

    private fun stopDrawerSmsListen() {
        drawerSmsReceiver?.let { OtpSmsAutoFill.unregisterReceiver(this, it) }
        drawerSmsReceiver = null
    }

    private fun simpleWatcher(after: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { after() }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.dataString ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (data.isNullOrBlank() && intent?.action != Intent.ACTION_VIEW) {
            if (intent?.getBooleanExtra(VinorNotifyHelper.EXTRA_OPEN_NOTIFICATIONS, false) == true) {
                openNotifications()
            }
            return
        }
        val uri = intent?.data?.toString() ?: data ?: return
        val route = DeepLinkRouter.resolve(uri)
        if (route.requiresAuth && !PartnerApi.hasSession()) {
            DeepLinkPendingStore.save(this, uri)
            openDrawer()
            return
        }
        when (route.type) {
            DeepLinkRouter.DestinationType.PROPERTY_DETAIL,
            DeepLinkRouter.DestinationType.PARTNER_LANDS,
            DeepLinkRouter.DestinationType.EXPRESS_PUBLIC -> {
                val code = route.propertyId
                if (!code.isNullOrBlank()) openLand(code)
                else openPartnerHome()
            }
            DeepLinkRouter.DestinationType.PROFILE -> navController.navigate(R.id.profileFragment)
            DeepLinkRouter.DestinationType.PARTNER_COMMISSIONS -> navController.navigate(R.id.commissionsFragment)
            DeepLinkRouter.DestinationType.PARTNER_ROUTINE -> navController.navigate(R.id.routineFragment)
            DeepLinkRouter.DestinationType.PARTNER_NOTIFICATIONS -> openNotifications()
            DeepLinkRouter.DestinationType.PARTNER_LOGIN -> openLogin()
            DeepLinkRouter.DestinationType.PARTNER_WEB -> openPath(route.targetUrl)
            DeepLinkRouter.DestinationType.APK_DOWNLOAD -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route.targetUrl)))
            }
            else -> openPartnerHome()
        }
    }

    private fun updateOfflineBanner() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val online = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
        binding.offlineBanner.visibility = if (online) View.GONE else View.VISIBLE
    }
}
