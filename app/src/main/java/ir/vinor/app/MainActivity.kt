package ir.vinor.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

/** شِل نیتیو همکار: خانه مثل وب + فوتر دیوار‌مانند. منو در «وینور من». */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var notifyHelper: VinorNotifyHelper? = null
    private var keepSystemSplash = true
    private var pendingOpenVinorChat = false
    private var drawerIsSpecial = false
    private var tabbarForcedHidden = false
    private var tabbarImeHidden = false

    private val navController: NavController
        get() = (supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(VinorLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        val splash = installSplashScreen()
        val fromOnboarding = intent?.getBooleanExtra(OnboardingActivity.EXTRA_FROM_ONBOARDING, false) == true
        splash.setKeepOnScreenCondition { keepSystemSplash && !fromOnboarding }
        super.onCreate(savedInstanceState)
        if (fromOnboarding) keepSystemSplash = false
        if (!fromOnboarding && VinorOnboardingStore.shouldShow(this, intent)) {
            keepSystemSplash = false
            startActivity(Intent(this, OnboardingActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
            return
        }
        SystemUi.apply(this)
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        wireDivarTabbar()
        wireChatImeTabbar()
        refreshPartnerFlags()

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
        refreshPartnerFlags()
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
        tabbarForcedHidden = !visible
        applyTabbarVisibility()
    }

    fun syncDivarTab() {
        if (!::binding.isInitialized) return
        applyTabbarVisibility()
        val dest = runCatching { navController.currentDestination?.id }.getOrNull()
        val chatOn = dest == R.id.dashboardFragment && findDashboard()?.isChatOpen() == true
        val favsOn = dest == R.id.nativePartnerFragment &&
            findNativePage() == NativePartnerFragment.PAGE_FAVORITES
        val postOn = dest == R.id.myPropertyFormFragment
        val meOn = dest == R.id.profileFragment
        val adsOn = dest == R.id.dashboardFragment && !chatOn
        paintTab(
            binding.divarTabbar.tabAdsIcon,
            binding.divarTabbar.tabAdsLabel,
            adsOn,
            R.drawable.ic_tab_home,
            R.drawable.ic_home,
        )
        paintTab(
            binding.divarTabbar.tabFavsIcon,
            binding.divarTabbar.tabFavsLabel,
            favsOn,
            R.drawable.ic_tab_bookmark,
            R.drawable.ic_bookmark,
        )
        paintTab(
            binding.divarTabbar.tabPostIcon,
            binding.divarTabbar.tabPostLabel,
            postOn,
            R.drawable.ic_tab_add,
            R.drawable.ic_add,
        )
        paintTab(
            binding.divarTabbar.tabChatIcon,
            binding.divarTabbar.tabChatLabel,
            chatOn,
            R.drawable.ic_tab_chat,
            R.drawable.ic_messages,
        )
        paintTab(
            binding.divarTabbar.tabMeIcon,
            binding.divarTabbar.tabMeLabel,
            meOn,
            R.drawable.ic_tab_me,
            R.drawable.ic_profile,
        )
    }

    fun isDrawerOpen(): Boolean = false

    fun openDrawer() {
        openLogin()
    }

    fun closeDrawer() {}

    fun openPartnerHome() {
        pendingOpenVinorChat = false
        val nav = navController
        val dash = findDashboard()
        if (nav.currentDestination?.id == R.id.dashboardFragment && dash != null) {
            if (dash.isChatOpen()) dash.showHomeEntry()
            syncDivarTab()
            return
        }
        if (!nav.popBackStack(R.id.dashboardFragment, false)) {
            nav.navigate(R.id.dashboardFragment)
        }
        findDashboard()?.showHomeEntry()
        syncDivarTab()
    }

    fun openVinorChat() {
        val nav = navController
        val dash = findDashboard()
        if (nav.currentDestination?.id == R.id.dashboardFragment && dash != null) {
            dash.openVinorChat()
            syncDivarTab()
            return
        }
        pendingOpenVinorChat = true
        if (nav.currentDestination?.id != R.id.dashboardFragment) {
            if (!nav.popBackStack(R.id.dashboardFragment, false)) {
                nav.navigate(R.id.dashboardFragment)
            }
        }
        fun tryOpen(left: Int) {
            if (!pendingOpenVinorChat) {
                syncDivarTab()
                return
            }
            val live = findDashboard()
            if (live != null) {
                live.openVinorChat()
                pendingOpenVinorChat = false
                syncDivarTab()
                return
            }
            if (left > 0) binding.root.post { tryOpen(left - 1) }
        }
        binding.root.post { tryOpen(12) }
    }

    fun consumePendingVinorChat(): Boolean {
        val pending = pendingOpenVinorChat
        pendingOpenVinorChat = false
        return pending
    }

    fun openLogin() {
        if (navController.currentDestination?.id != R.id.loginStep1Fragment) {
            navController.navigate(R.id.loginStep1Fragment)
        }
    }

    fun openProfile() {
        if (navController.currentDestination?.id != R.id.profileFragment) {
            navController.navigate(R.id.profileFragment)
        }
    }

    fun openProfileEdit() {
        if (navController.currentDestination?.id != R.id.profileEditFragment) {
            navController.navigate(R.id.profileEditFragment)
        }
    }

    fun openNativePage(page: String) {
        val args = Bundle().apply { putString(NativePartnerFragment.ARG_PAGE, page) }
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        val current = host?.childFragmentManager?.primaryNavigationFragment as? NativePartnerFragment
        if (current != null &&
            current.page == page &&
            navController.currentDestination?.id == R.id.nativePartnerFragment
        ) {
            return
        }
        navController.navigate(R.id.nativePartnerFragment, args)
    }

    fun openNotifications() {
        if (navController.currentDestination?.id != R.id.notificationsFragment) {
            navController.navigate(R.id.notificationsFragment)
        }
    }

    fun openLand(code: String) {
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

    private fun findNativePage(): String? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        return (host?.childFragmentManager?.primaryNavigationFragment as? NativePartnerFragment)?.page
    }

    private fun wireDivarTabbar() {
        binding.divarTabbar.tabAds.setOnClickListener { openPartnerHome() }
        binding.divarTabbar.tabFavs.setOnClickListener {
            if (!PartnerApi.hasSession()) {
                openLogin()
                return@setOnClickListener
            }
            openNativePage(NativePartnerFragment.PAGE_FAVORITES)
        }
        binding.divarTabbar.tabPost.setOnClickListener { openPostAd() }
        binding.divarTabbar.tabChat.setOnClickListener { openVinorChat() }
        binding.divarTabbar.tabMe.setOnClickListener { openProfile() }
        binding.root.post {
            navController.addOnDestinationChangedListener { _, _, _ ->
                binding.root.post { syncDivarTab() }
            }
            syncDivarTab()
        }
    }

    private fun openPostAd() {
        if (!PartnerApi.hasSession()) {
            openLogin()
            return
        }
        if (drawerIsSpecial) openListingForm()
        else openOwnerHomeForm()
    }

    private fun applyTabbarVisibility() {
        if (!::binding.isInitialized) return
        val dest = runCatching { navController.currentDestination?.id }.getOrNull()
        val hideDest = dest == R.id.loginStep1Fragment ||
            dest == R.id.loginStep2Fragment ||
            dest == R.id.loginWebFragment ||
            dest == R.id.profileEditFragment ||
            dest == R.id.landDetailFragment ||
            dest == R.id.myPropertyFormFragment ||
            dest == R.id.partnerPageFragment
        val chatOpen = dest == R.id.dashboardFragment && findDashboard()?.isChatOpen() == true
        if (!chatOpen) tabbarImeHidden = false
        binding.divarTabbar.root.isVisible = !tabbarForcedHidden && !hideDest && !tabbarImeHidden
    }

    private fun wireChatImeTabbar() {
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            if (!::binding.isInitialized) return@addOnGlobalLayoutListener
            val rect = android.graphics.Rect()
            binding.root.getWindowVisibleDisplayFrame(rect)
            val keypad = binding.root.rootView.height - rect.bottom
            val imeOpen = keypad > (120 * resources.displayMetrics.density)
            val hide = imeOpen && findDashboard()?.isChatOpen() == true
            if (hide != tabbarImeHidden) {
                tabbarImeHidden = hide
                applyTabbarVisibility()
            }
        }
    }

    private fun paintTab(
        icon: android.widget.ImageView,
        label: android.widget.TextView,
        on: Boolean,
        outline: Int,
        fill: Int,
    ) {
        icon.setImageResource(if (on) fill else outline)
        val c = Color.parseColor(if (on) "#FFFFFF" else "#A3A3A3")
        icon.setColorFilter(c, PorterDuff.Mode.SRC_IN)
        label.setTextColor(c)
        label.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun findWebPage(): BaseWebViewFragment? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        return host?.childFragmentManager?.primaryNavigationFragment as? BaseWebViewFragment
    }

    fun openOwnerHomeForm() {
        openListingCompose(publicListing = false)
    }

    fun openListingForm() {
        openListingCompose(publicListing = true)
    }

    private fun openListingCompose(publicListing: Boolean) {
        val args = Bundle().apply {
            putBoolean(MyPropertyFormFragment.ARG_PUBLIC, publicListing)
        }
        if (navController.currentDestination?.id != R.id.myPropertyFormFragment) {
            navController.navigate(R.id.myPropertyFormFragment, args)
        }
    }

    fun openPath(path: String) {
        val uri = Uri.parse(if (path.startsWith("http")) path else "${PartnerApi.BASE}$path")
        val p = (uri.path ?: "/").trimEnd('/').ifBlank { "/" }
        val low = p.lowercase()
        when {
            p == "/express/partner/home" || p == "/express/partner" -> {
                val land = uri.getQueryParameter("land")
                if (!land.isNullOrBlank()) openLand(land) else openPartnerHome()
            }
            low.endsWith("/training") -> openNativePage(NativePartnerFragment.PAGE_TRAINING)
            low.endsWith("/levels") -> openNativePage(NativePartnerFragment.PAGE_LEVELS)
            low.endsWith("/plus") -> openNativePage(NativePartnerFragment.PAGE_LEVELS)
            low.endsWith("/invite") -> openNativePage(NativePartnerFragment.PAGE_INVITE)
            low.endsWith("/settings") -> openNativePage(NativePartnerFragment.PAGE_SETTINGS)
            low.endsWith("/favorites") -> openNativePage(NativePartnerFragment.PAGE_FAVORITES)
            low.endsWith("/requests") -> openNativePage(NativePartnerFragment.PAGE_REQUESTS)
            low.endsWith("/listings/new") -> openListingForm()
            low.endsWith("/listings") -> openNativePage(NativePartnerFragment.PAGE_LISTINGS)
            low.endsWith("/my-property") || low.endsWith("/my-properties") -> {
                if (uri.getQueryParameter("register") == "1") {
                    openOwnerHomeForm()
                } else if (navController.currentDestination?.id != R.id.myPropertiesFragment) {
                    navController.navigate(R.id.myPropertiesFragment)
                }
            }
            low.endsWith("/apply") || low.contains("/apply/") || low.endsWith("/thanks") ->
                openNativePage(NativePartnerFragment.PAGE_APPLY)
            low.contains("/legal/terms") || low.endsWith("/cooperation-terms") ->
                openNativePage(NativePartnerFragment.PAGE_TERMS)
            low == "/privacy" || low.contains("/legal/privacy") ->
                openNativePage(NativePartnerFragment.PAGE_PRIVACY)
            low.contains("/notifications") -> openNotifications()
            low.contains("/commissions") -> {
                if (navController.currentDestination?.id != R.id.commissionsFragment) {
                    navController.navigate(R.id.commissionsFragment)
                }
            }
            low.contains("/profile/edit") -> openProfileEdit()
            low.endsWith("/routine") -> {
                if (navController.currentDestination?.id != R.id.routineFragment) {
                    navController.navigate(R.id.routineFragment)
                }
            }
            else -> {
                val args = Bundle().apply { putString(PartnerPageFragment.ARG_URL, uri.toString()) }
                navController.navigate(R.id.partnerPageFragment, args)
            }
        }
    }

    fun consumePendingDeepLink(): Boolean {
        val pending = DeepLinkPendingStore.consume(this) ?: return false
        handleDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse(pending)))
        return true
    }

    fun logoutFromDrawer() {
        lifecycleScope.launch {
            VinorFcmRegistrar.unregisterCurrentToken(this@MainActivity)
            try {
                withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/api/logout", JSONObject(), this@MainActivity)
                }
            } catch (_: Exception) {
            }
            PartnerApi.clearCookies {
                Toast.makeText(this@MainActivity, "از حساب خارج شدید.", Toast.LENGTH_SHORT).show()
                refreshPartnerFlags()
                openPartnerHome()
            }
        }
    }

    private fun refreshPartnerFlags() {
        lifecycleScope.launch {
            fun apply(obj: JSONObject) {
                if (obj.has("is_special")) drawerIsSpecial = obj.optBoolean("is_special")
            }
            PartnerApi.peekJson("/express/partner/profile/data", this@MainActivity)?.let { apply(it) }
            PartnerApi.peekJson("/express/partner/home/data", this@MainActivity)?.let { apply(it) }
            try {
                apply(
                    withContext(Dispatchers.IO) {
                        PartnerApi.get("/express/partner/profile/data", this@MainActivity)
                    },
                )
            } catch (_: Exception) {
            }
            try {
                apply(
                    withContext(Dispatchers.IO) {
                        PartnerApi.get("/express/partner/home/data", this@MainActivity)
                    },
                )
            } catch (_: Exception) {
            }
        }
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
        binding.offlineBanner.visibility = if (online) android.view.View.GONE else android.view.View.VISIBLE
    }
}
