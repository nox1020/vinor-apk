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
                if (dash != null && dash.closeAdsAll()) return
                if (!navController.popBackStack()) finish()
            }
        })

        handleDeepLink(intent)
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
        val nav = navController
        if (!nav.popBackStack(R.id.dashboardFragment, false)) {
            nav.navigate(R.id.dashboardFragment)
        }
        findDashboard()?.reload()
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

    private fun sizeDrawer() {
        val dm = resources.displayMetrics
        val peek = (3.25f * dm.density).toInt()
        val cap = (26f * dm.density).toInt()
        val width = minOf((dm.widthPixels * 0.85f).toInt(), cap, dm.widthPixels - peek)
        val lp = binding.drawerPanel.layoutParams
        lp.width = width.coerceAtLeast((280 * dm.density).toInt())
        binding.drawerPanel.layoutParams = lp
    }

    private fun wireDrawer() {
        binding.drawerBackdrop.setOnClickListener { closeDrawer() }
        binding.drawerVinorChat.setOnClickListener { openPartnerHome() }
        binding.drawerTraining.setOnClickListener {
            closeDrawer()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${PartnerApi.BASE}/express/partner/training")))
        }
        binding.drawerWallet.setOnClickListener {
            closeDrawer()
            if (navController.currentDestination?.id != R.id.commissionsFragment) {
                navController.navigate(R.id.commissionsFragment)
            }
        }
        binding.drawerRoutine.setOnClickListener {
            closeDrawer()
            if (navController.currentDestination?.id != R.id.routineFragment) {
                navController.navigate(R.id.routineFragment)
            }
        }
        binding.drawerProfile.setOnClickListener { openProfile() }
        binding.drawerLoginSubmit.setOnClickListener { submitDrawerPhone() }
        binding.drawerOtpSubmit.setOnClickListener { submitDrawerOtp() }
        binding.drawerChangePhone.setOnClickListener { showDrawerPhoneStep() }
        binding.drawerPhone.addTextChangedListener(simpleWatcher { syncDrawerPhoneEnabled() })
        binding.drawerOtp.addTextChangedListener(simpleWatcher { syncDrawerOtpEnabled() })
        bindDrawerLegal()
        syncDrawerPhoneEnabled()
        syncDrawerOtpEnabled()
    }

    private fun bindDrawerLegal() {
        val full = "با ادامه، شرایط و حریم خصوصی را می‌پذیرید."
        val spanned = SpannableString(full)
        fun link(label: String, path: String) {
            val start = full.indexOf(label)
            if (start < 0) return
            spanned.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${PartnerApi.BASE}$path")))
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
        binding.drawerGuestLogin.isVisible = !session
        binding.drawerWallet.isVisible = session
        binding.drawerRoutine.isVisible = session
        binding.drawerProfile.isVisible = session
        if (session) showDrawerPhoneStep()
    }

    private fun showDrawerPhoneStep() {
        binding.drawerPhoneStep.isVisible = true
        binding.drawerOtpStep.isVisible = false
        binding.drawerOtp.setText("")
        binding.drawerOtpHint.isVisible = false
        binding.drawerLoginHint.isVisible = false
        drawerPhoneNormalized = ""
        syncDrawerPhoneEnabled()
    }

    private fun syncDrawerPhoneEnabled() {
        val on = normalizePhone(binding.drawerPhone.text?.toString().orEmpty()).length == 11
        binding.drawerLoginSubmit.isEnabled = on
        binding.drawerLoginSubmit.alpha = if (on) 1f else 0.45f
    }

    private fun syncDrawerOtpEnabled() {
        val on = (binding.drawerOtp.text?.toString()?.filter { it.isDigit() }?.length ?: 0) >= 5
        binding.drawerOtpSubmit.isEnabled = on
        binding.drawerOtpSubmit.alpha = if (on) 1f else 0.45f
    }

    private fun submitDrawerPhone() {
        val phone = normalizePhone(binding.drawerPhone.text?.toString().orEmpty())
        binding.drawerLoginHint.isVisible = false
        if (phone.length != 11 || !phone.startsWith("09")) {
            binding.drawerLoginHint.text = "لطفاً یک شماره موبایل ۱۱ رقمی معتبر وارد کنید."
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
                    binding.drawerOtpPhoneLabel.text = phone
                    binding.drawerPhoneStep.isVisible = false
                    binding.drawerOtpStep.isVisible = true
                    binding.drawerOtp.requestFocus()
                    Toast.makeText(this@MainActivity, resp.optString("message", "کد تأیید ارسال شد."), Toast.LENGTH_SHORT).show()
                } else {
                    binding.drawerLoginHint.text = resp.optString("error", "خطا در ارتباط. دوباره تلاش کنید.")
                    binding.drawerLoginHint.isVisible = true
                }
            } catch (e: Exception) {
                binding.drawerLoginHint.text = e.message ?: "خطا در ارتباط. دوباره تلاش کنید."
                binding.drawerLoginHint.isVisible = true
            } finally {
                syncDrawerPhoneEnabled()
            }
        }
    }

    private fun submitDrawerOtp() {
        val phone = drawerPhoneNormalized.ifBlank { normalizePhone(binding.drawerPhone.text?.toString().orEmpty()) }
        val code = binding.drawerOtp.text?.toString()?.filter { it.isDigit() }.orEmpty()
        binding.drawerOtpHint.isVisible = false
        if (code.length < 5) {
            binding.drawerOtpHint.text = "کد تأیید ناقص است."
            binding.drawerOtpHint.isVisible = true
            return
        }
        binding.drawerOtpSubmit.isEnabled = false
        lifecycleScope.launch {
            try {
                val body = JSONObject().put("code", code).put("phone", phone)
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/api/verify", body, this@MainActivity)
                }
                if (resp.optBoolean("success")) {
                    VinorFcmRegistrar.registerAfterLogin(this@MainActivity)
                    requestNotificationPermission()
                    Toast.makeText(this@MainActivity, "وارد شدید.", Toast.LENGTH_SHORT).show()
                    showDrawerPhoneStep()
                    closeDrawer()
                    syncDrawerSession()
                    openPartnerHome()
                } else {
                    binding.drawerOtpHint.text = resp.optString("error", "کد تأیید نادرست است.")
                    binding.drawerOtpHint.isVisible = true
                }
            } catch (e: Exception) {
                binding.drawerOtpHint.text = e.message ?: "خطا در ارتباط."
                binding.drawerOtpHint.isVisible = true
            } finally {
                syncDrawerOtpEnabled()
            }
        }
    }

    private fun normalizePhone(input: String): String {
        val digits = input.filter { it.isDigit() }
        return when {
            digits.startsWith("0098") && digits.length >= 14 -> "0" + digits.drop(4).take(10)
            digits.startsWith("98") && digits.length >= 12 -> "0" + digits.drop(2).take(10)
            digits.length == 11 && digits.startsWith("0") -> digits
            digits.length == 10 && digits.startsWith("9") -> "0$digits"
            else -> digits.take(11)
        }
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
            openLogin()
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
