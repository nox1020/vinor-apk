package ir.vinor.app

import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
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
    private var resumeChatAfterAuth = false
    private var pendingShowHome = false
    private var drawerIsSpecial = false
    private var tabbarForcedHidden = false
    private var tabbarImeHidden = false
    private var connectivityCallback: android.net.ConnectivityManager.NetworkCallback? = null

    fun notifyHelperOrNull(): VinorNotifyHelper? = notifyHelper

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
            webViewProvider = { findWebPage()?.peekWebView() },
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
        PartnerApi.setSessionExpiredListener {
            if (isFinishing || isDestroyed) return@setSessionExpiredListener
            clearPageMemory()
            openLogin(sessionExpired = true)
        }
        runCatching { VinorCacheSession.fingerprint(this) }
        runCatching { VinorApiCacheStore.warmup(this) }
        runCatching { VinorAppUpdateChecker.check(this, webView = null) }
        runCatching { VinorFcmRegistrar.requestTokenAndRegister(this) }
        keepSystemSplash = false
        updateOfflineBanner()
        registerConnectivityCallback()
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
        PartnerApi.setSessionExpiredListener(null)
        unregisterConnectivityCallback()
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
        val stack = runCatching { navController.currentBackStack.value.map { it.destination.id } }.getOrDefault(emptyList())
        val tab = FooterTabs.selected(
            stack,
            findDashboard()?.isChatOpen() == true,
            FooterTabs.Ids(
                dashboard = R.id.dashboardFragment,
                search = R.id.searchFragment,
                profile = R.id.profileFragment,
                myProperties = R.id.myPropertiesFragment,
                propertyForm = R.id.myPropertyFormFragment,
            ),
        )
        val adsOn = tab == FooterTabs.Tab.ADS
        val searchOn = tab == FooterTabs.Tab.SEARCH
        val postOn = tab == FooterTabs.Tab.POST
        val chatOn = tab == FooterTabs.Tab.CHAT
        val meOn = tab == FooterTabs.Tab.ME
        paintTab(
            binding.divarTabbar.tabAdsIcon,
            binding.divarTabbar.tabAdsLabel,
            adsOn,
            R.drawable.ic_tab_home,
            R.drawable.ic_home,
        )
        paintTab(
            binding.divarTabbar.tabSearchIcon,
            binding.divarTabbar.tabSearchLabel,
            searchOn,
            R.drawable.ic_tab_search,
            R.drawable.ic_search,
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
        openLogin(sessionExpired = false)
    }

    fun closeDrawer() {}

    fun openPartnerHome() {
        pendingOpenVinorChat = false
        pendingShowHome = true
        val nav = navController
        val dash = findDashboard()
        if (nav.currentDestination?.id == R.id.dashboardFragment && dash != null) {
            dash.showHomeEntry()
            dash.scrollHomeToTop()
            pendingShowHome = false
            syncDivarTab()
            return
        }
        if (!nav.popBackStack(R.id.dashboardFragment, false)) {
            runCatching { nav.navigate(R.id.dashboardFragment) }
        }
        revealPendingHome()
    }

    /** popBackStack خانه را همان لحظه بالا نمی‌آورد؛ بدون این، تب خانه روی چت می‌ماند. */
    private fun revealPendingHome(left: Int = 12) {
        if (!pendingShowHome) {
            syncDivarTab()
            return
        }
        val live = findDashboard()
        if (live != null && navController.currentDestination?.id == R.id.dashboardFragment) {
            live.showHomeEntry()
            live.scrollHomeToTop()
            pendingShowHome = false
            syncDivarTab()
            return
        }
        if (left > 0) binding.root.post { revealPendingHome(left - 1) }
        else {
            pendingShowHome = false
            syncDivarTab()
        }
    }

    fun openVinorChat() {
        if (!PartnerApi.hasSession()) {
            requireChatLogin()
            return
        }
        pendingShowHome = false
        val nav = navController
        val dash = findDashboard()
        if (nav.currentDestination?.id == R.id.dashboardFragment && dash != null) {
            if (!dash.isCurrentAccountChatOpen()) dash.openVinorChat()
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
            if (!PartnerApi.hasSession()) {
                requireChatLogin()
                return
            }
            val live = findDashboard()
            if (live != null && navController.currentDestination?.id == R.id.dashboardFragment) {
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

    /** مهمان تب چت را زده؛ بعد از OTP همان چتِ حساب جدید باز می‌شود. */
    fun requireChatLogin(sessionExpired: Boolean = false) {
        resumeChatAfterAuth = true
        pendingOpenVinorChat = false
        openLogin(sessionExpired)
    }

    fun clearChatResume() {
        resumeChatAfterAuth = false
    }

    fun openChatIfResumed() {
        if (!resumeChatAfterAuth || !PartnerApi.hasSession()) return
        resumeChatAfterAuth = false
        openVinorChat()
    }

    fun dropChatForSignedOutAccount() {
        resumeChatAfterAuth = false
        pendingOpenVinorChat = false
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        host?.childFragmentManager?.fragments?.forEach { (it as? DashboardFragment)?.releaseChatAccount() }
    }

    fun openLogin(sessionExpired: Boolean = false) {
        if (sessionExpired) captureAuthReturn()
        val args = Bundle().apply {
            if (sessionExpired) {
                putString(LoginStep1Fragment.ARG_REASON, AuthReturnStore.REASON_SESSION_EXPIRED)
            }
        }
        val current = navController.currentDestination?.id
        if (current == R.id.loginStep1Fragment) {
            if (sessionExpired) findLoginStep1()?.showSessionExpiredHint()
            return
        }
        navController.navigate(R.id.loginStep1Fragment, args)
    }

    /** بعد از ورود موفق؛ false یعنی مقصدی نبود. */
    fun consumeAuthReturn(): Boolean {
        val dest = AuthReturnStore.consume(this) ?: return false
        if (!AuthReturnStore.isInternal(dest)) return false
        return when (dest.kind) {
            "home" -> {
                openPartnerHome()
                true
            }
            "chat" -> {
                openVinorChat()
                true
            }
            "search" -> {
                openSearch()
                true
            }
            "profile" -> {
                openProfile()
                true
            }
            "profile_edit" -> {
                openProfileEdit()
                true
            }
            "notifications" -> {
                openNotifications()
                true
            }
            "notes" -> {
                if (navController.currentDestination?.id != R.id.notesFragment) {
                    navController.navigate(R.id.notesFragment)
                }
                true
            }
            "commissions" -> {
                if (navController.currentDestination?.id != R.id.commissionsFragment) {
                    navController.navigate(R.id.commissionsFragment)
                }
                true
            }
            "routine" -> {
                if (navController.currentDestination?.id != R.id.routineFragment) {
                    navController.navigate(R.id.routineFragment)
                }
                true
            }
            "my_properties" -> {
                openMyProperty()
                true
            }
            "property_form" -> {
                openMyPropertyForm(dest.param == "1")
                true
            }
            "land" -> {
                if (!AuthReturnStore.isSafeLandCode(dest.param)) false
                else {
                    openLand(dest.param)
                    true
                }
            }
            "native" -> {
                if (!AuthReturnStore.isSafeNativePage(dest.param)) false
                else {
                    openNativePage(dest.param)
                    true
                }
            }
            "consultant" -> {
                openConsultantOnboarding()
                true
            }
            "partner" -> {
                val path = AuthReturnStore.internalPartnerPath(dest.param) ?: return false
                openPath(path)
                true
            }
            else -> false
        }
    }

    fun openMyPropertyForm(publicListing: Boolean) {
        openListingCompose(publicListing)
    }

    fun openProfile() {
        if (navController.currentDestination?.id == R.id.profileFragment) return
        if (popToInStack(R.id.profileFragment)) {
            syncDivarTab()
            return
        }
        navigateTab(R.id.profileFragment)
    }

    fun openProfileEdit() {
        if (navController.currentDestination?.id != R.id.profileEditFragment) {
            navController.navigate(R.id.profileEditFragment)
        }
    }

    fun openNativePage(page: String) {
        openNativePage(page, asTab = false)
    }

    /** ورود موجود را دوباره استفاده می‌کند و فلو نیتیو مشاور را باز می‌کند. */
    fun openConsultantOnboarding() {
        if (!PartnerApi.hasSession()) {
            ConsultantResume.arm(this)
            openLogin()
            return
        }
        if (navController.currentDestination?.id == R.id.consultantOnboardingFragment) return
        navController.navigate(R.id.consultantOnboardingFragment)
    }

    fun openSearch() {
        if (navController.currentDestination?.id == R.id.searchFragment) return
        if (popToInStack(R.id.searchFragment)) {
            syncDivarTab()
            return
        }
        navigateTab(R.id.searchFragment)
    }

    /** اگر ریشهٔ همین تب زیر صفحهٔ فعلی است، همان را نشان بده و فرگمنت را از نو نساز. */
    private fun popToInStack(destId: Int): Boolean {
        if (runCatching { navController.getBackStackEntry(destId) }.isFailure) return false
        return navController.popBackStack(destId, false)
    }

    private fun openNativePage(page: String, asTab: Boolean) {
        val args = Bundle().apply { putString(NativePartnerFragment.ARG_PAGE, page) }
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        val current = host?.childFragmentManager?.primaryNavigationFragment as? NativePartnerFragment
        if (current != null &&
            current.page == page &&
            navController.currentDestination?.id == R.id.nativePartnerFragment
        ) {
            return
        }
        if (asTab) navigateTab(R.id.nativePartnerFragment, args)
        else navController.navigate(R.id.nativePartnerFragment, args)
    }

    fun openNotifications() {
        if (navController.currentDestination?.id != R.id.notificationsFragment) {
            navController.navigate(R.id.notificationsFragment)
        }
    }

    private fun openCustomers(uri: Uri) {
        if (!PartnerApi.hasSession()) {
            DeepLinkPendingStore.save(this, uri.toString())
            openLogin()
            return
        }
        val url = uri.toString()
        val current = navController.currentBackStackEntry?.arguments?.getString("url")
        if (navController.currentDestination?.id == R.id.customersFragment && current == url) return
        val args = Bundle().apply { putString("url", url) }
        navController.navigate(R.id.customersFragment, args)
    }

    fun openLand(code: String) {
        val trimmed = code.trim()
        if (trimmed.isBlank()) return
        val currentCode = navController.currentBackStackEntry?.arguments?.getString("code")
        if (navController.currentDestination?.id == R.id.landDetailFragment && currentCode == trimmed) {
            return
        }
        val args = Bundle().apply { putString("code", trimmed) }
        navController.navigate(R.id.landDetailFragment, args)
    }

    fun reloadHome() {
        findDashboard()?.reload()
    }

    private fun findDashboard(): DashboardFragment? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        return host?.childFragmentManager?.primaryNavigationFragment as? DashboardFragment
    }

    private fun findLoginStep1(): LoginStep1Fragment? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        return host?.childFragmentManager?.primaryNavigationFragment as? LoginStep1Fragment
    }

    private fun captureAuthReturn() {
        val destId = runCatching { navController.currentDestination?.id }.getOrNull() ?: return
        if (destId == R.id.loginStep1Fragment ||
            destId == R.id.loginStep2Fragment ||
            destId == R.id.loginWebFragment
        ) {
            return
        }
        val args = navController.currentBackStackEntry?.arguments
        val dest = when (destId) {
            R.id.dashboardFragment -> AuthReturnStore.Dest(
                if (findDashboard()?.isChatOpen() == true) "chat" else "home",
                "",
            )
            R.id.searchFragment -> AuthReturnStore.Dest("search", "")
            R.id.profileFragment -> AuthReturnStore.Dest("profile", "")
            R.id.profileEditFragment -> AuthReturnStore.Dest("profile_edit", "")
            R.id.notificationsFragment -> AuthReturnStore.Dest("notifications", "")
            R.id.notesFragment -> AuthReturnStore.Dest("notes", "")
            R.id.commissionsFragment -> AuthReturnStore.Dest("commissions", "")
            R.id.routineFragment -> AuthReturnStore.Dest("routine", "")
            R.id.myPropertiesFragment -> AuthReturnStore.Dest("my_properties", "")
            R.id.myPropertyFormFragment -> AuthReturnStore.Dest(
                "property_form",
                if (args?.getBoolean(MyPropertyFormFragment.ARG_PUBLIC) == true) "1" else "0",
            )
            R.id.landDetailFragment -> {
                val code = args?.getString("code").orEmpty()
                if (!AuthReturnStore.isSafeLandCode(code)) return
                AuthReturnStore.Dest("land", code)
            }
            R.id.nativePartnerFragment -> {
                val page = args?.getString(NativePartnerFragment.ARG_PAGE).orEmpty()
                if (!AuthReturnStore.isSafeNativePage(page)) return
                AuthReturnStore.Dest("native", page)
            }
            R.id.consultantOnboardingFragment -> AuthReturnStore.Dest("consultant", "")
            R.id.partnerPageFragment -> {
                val raw = args?.getString(PartnerPageFragment.ARG_URL).orEmpty()
                val path = AuthReturnStore.internalPartnerPath(raw) ?: return
                AuthReturnStore.Dest("partner", path)
            }
            else -> return
        }
        if (AuthReturnStore.isInternal(dest)) AuthReturnStore.save(this, dest)
    }

    private fun wireDivarTabbar() {
        binding.divarTabbar.tabAds.setOnClickListener { openPartnerHome() }
        binding.divarTabbar.tabSearch.setOnClickListener { openSearch() }
        binding.divarTabbar.tabPost.setOnClickListener { openPostAd() }
        binding.divarTabbar.tabChat.setOnClickListener { openVinorChat() }
        binding.divarTabbar.tabMe.setOnClickListener { openProfile() }
        binding.root.post {
            navController.addOnDestinationChangedListener { _, destination, _ ->
                val onLogin = destination.id == R.id.loginStep1Fragment ||
                    destination.id == R.id.loginStep2Fragment ||
                    destination.id == R.id.loginWebFragment
                if (!onLogin && !PartnerApi.hasSession()) resumeChatAfterAuth = false
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
        openOwnerHomeForm()
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
            dest == R.id.myPropertyManageFragment ||
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
        val c = ContextCompat.getColor(
            this,
            if (on) R.color.vinor_nav_selected else R.color.vinor_nav_unselected,
        )
        icon.setColorFilter(c, PorterDuff.Mode.SRC_IN)
        label.setTextColor(c)
        label.typeface = ResourcesCompat.getFont(
            this,
            if (on) R.font.vazirmatn_bold else R.font.vazirmatn_regular,
        )
    }

    private fun findWebPage(): BaseWebViewFragment? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        return host?.childFragmentManager?.primaryNavigationFragment as? BaseWebViewFragment
    }

    fun openMyProperty() {
        if (navController.currentDestination?.id == R.id.myPropertiesFragment) return
        if (navController.currentDestination?.id == R.id.profileFragment) {
            navController.navigate(R.id.myPropertiesFragment)
            return
        }
        navigateTab(R.id.myPropertiesFragment)
    }

    /** بازگشت هدر تنظیمات، نشان‌شده‌ها و آگهی‌های من به تب وینور من. */
    fun returnToVinorMan() {
        if (!navController.popBackStack(R.id.profileFragment, false)) {
            openProfile()
        }
    }

    fun openMyPropertyManage(snapshot: org.json.JSONObject) {
        val args = Bundle().apply { putString(MyPropertyManageFragment.ARG_SNAPSHOT, snapshot.toString()) }
        navController.navigate(R.id.myPropertyManageFragment, args)
    }

    fun openPropertyEdit(snapshot: org.json.JSONObject) {
        val args = Bundle().apply {
            putBoolean(MyPropertyFormFragment.ARG_PUBLIC, false)
            putString(MyPropertyFormFragment.ARG_CODE, MyPropertyManage.codeOf(snapshot))
            putString(MyPropertyFormFragment.ARG_SNAPSHOT, snapshot.toString())
        }
        navController.navigate(R.id.myPropertyFormFragment, args)
    }

    fun openOwnerHomeForm() {
        openListingCompose(publicListing = false)
    }

    fun openListingForm() {
        openMyProperty()
    }

    private fun openListingCompose(publicListing: Boolean) {
        val args = Bundle().apply {
            putBoolean(MyPropertyFormFragment.ARG_PUBLIC, publicListing)
        }
        if (navController.currentDestination?.id == R.id.myPropertyFormFragment) return
        navigateTab(R.id.myPropertyFormFragment, args)
    }

    private fun navigateTab(dest: Int, args: Bundle? = null) {
        val opts = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(R.id.dashboardFragment, false, true)
            .build()
        runCatching { navController.navigate(dest, args, opts) }
            .onFailure { navController.navigate(dest, args) }
    }

    fun openPath(path: String) {
        val uri = Uri.parse(if (path.startsWith("http")) path else "${PartnerApi.BASE}$path")
        val p = (uri.path ?: "/").trimEnd('/').ifBlank { "/" }
        val low = p.lowercase()
        when {
            p == "/express/partner/home" || p == "/express/partner" -> {
                val land = uri.getQueryParameter("land")
                val divar = uri.getQueryParameter("divar")
                when {
                    !land.isNullOrBlank() -> openLand(land)
                    !divar.isNullOrBlank() -> openLand("divar:$divar")
                    uri.getQueryParameter("ai") == "1" -> openVinorChat()
                    else -> openPartnerHome()
                }
            }
            p.matches(Regex("^/express/partner/lands/[^/]+$")) -> {
                val code = p.substringAfterLast('/')
                if (code.isNotBlank()) openLand(code) else openPartnerHome()
            }
            p.matches(Regex("^/express/partner/divar/[^/]+$")) -> {
                val token = p.substringAfterLast('/')
                if (token.isNotBlank()) openLand("divar:$token") else openPartnerHome()
            }
            low.endsWith("/news") -> openNativePage(NativePartnerFragment.PAGE_NEWS)
            low.endsWith("/training") -> openNativePage(NativePartnerFragment.PAGE_TRAINING)
            low.endsWith("/levels") -> openNativePage(NativePartnerFragment.PAGE_LEVELS)
            low.endsWith("/plus") -> openNativePage(NativePartnerFragment.PAGE_LEVELS)
            low.endsWith("/invite") -> openNativePage(NativePartnerFragment.PAGE_INVITE)
            low.endsWith("/settings") -> openNativePage(NativePartnerFragment.PAGE_SETTINGS)
            low.endsWith("/favorites") -> openNativePage(NativePartnerFragment.PAGE_FAVORITES)
            low.endsWith("/search") -> {
                val land = uri.getQueryParameter("land")
                val divar = uri.getQueryParameter("divar")
                when {
                    !land.isNullOrBlank() -> openLand(land)
                    !divar.isNullOrBlank() -> openLand("divar:$divar")
                    else -> openSearch()
                }
            }
            low == "/express/partner/requests" || low.startsWith("/express/partner/requests/") ->
                openNativePage(NativePartnerFragment.PAGE_REQUESTS)
            low.endsWith("/listings/new") -> openOwnerHomeForm()
            low.endsWith("/listings") -> openMyProperty()
            low.endsWith("/my-property") || low.endsWith("/my-properties") -> {
                if (uri.getQueryParameter("register") == "1") {
                    openOwnerHomeForm()
                } else if (navController.currentDestination?.id != R.id.myPropertiesFragment) {
                    navController.navigate(R.id.myPropertiesFragment)
                }
            }
            low.endsWith("/consultant") || low.contains("/consultant/") ||
                low.endsWith("/apply") || low.contains("/apply/") || low.endsWith("/thanks") ->
                openConsultantOnboarding()
            low.contains("/legal/terms") || low.endsWith("/cooperation-terms") ->
                openNativePage(NativePartnerFragment.PAGE_TERMS)
            low == "/privacy" || low.contains("/legal/privacy") ->
                openNativePage(NativePartnerFragment.PAGE_PRIVACY)
            low.contains("/notifications") -> openNotifications()
            low.contains("/commissions") -> {
                if (navController.currentDestination?.id != R.id.commissionsFragment) {
                    navigateTab(R.id.commissionsFragment)
                }
            }
            low.contains("/profile/edit") -> openProfileEdit()
            low.endsWith("/routine") -> {
                if (navController.currentDestination?.id != R.id.routineFragment) {
                    navController.navigate(R.id.routineFragment)
                }
            }
            low.endsWith("/customers") || low.contains("/customers/") -> openCustomers(uri)
            else -> openInAppPartnerPage(uri)
        }
    }

    private fun openInAppPartnerPage(uri: Uri) {
        val args = Bundle().apply { putString(PartnerPageFragment.ARG_URL, uri.toString()) }
        navController.navigate(R.id.partnerPageFragment, args)
    }

    fun consumePendingDeepLink(): Boolean {
        val pending = DeepLinkPendingStore.consume(this) ?: return false
        if (VinorManMenu.isSafeInternalPath(pending)) {
            openPath(pending)
            return true
        }
        handleDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse(pending)))
        return true
    }

    private fun clearPageMemory() {
        DashboardFragment.clearRememberedHome()
        SearchFragment.clearRemembered()
        MyPropertyFormFragment.clearRemembered()
        ProfileFragment.clearRemembered()
        NativePartnerFragment.clearRemembered()
        ListingMemory.clearSession()
        NotificationsFragment.clearRemembered()
        NotesFragment.clearRemembered()
        ProfileEditFragment.clearRemembered()
        CommissionsFragment.clearRemembered()
        CustomersFragment.clearRemembered()
        RoutineFragment.clearRemembered()
        ConsultantOnboardingFragment.clearRemembered()
    }

    fun logoutFromDrawer(onFinished: (() -> Unit)? = null) {
        clearPageMemory()
        VinorSessionLogout.perform(this, lifecycleScope) {
            try {
                dropChatForSignedOutAccount()
                refreshPartnerFlags()
                openPartnerHome()
            } finally {
                onFinished?.invoke()
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
            DeepLinkRouter.DestinationType.PARTNER_CUSTOMERS -> openPath(route.targetUrl)
            DeepLinkRouter.DestinationType.PARTNER_REQUESTS ->
                openNativePage(NativePartnerFragment.PAGE_REQUESTS)
            DeepLinkRouter.DestinationType.PARTNER_LOGIN -> openLogin()
            DeepLinkRouter.DestinationType.PARTNER_WEB -> openPath(route.targetUrl)
            DeepLinkRouter.DestinationType.APK_DOWNLOAD -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route.targetUrl)))
            }
            else -> openPartnerHome()
        }
    }

    private fun updateOfflineBanner() {
        if (!::binding.isInitialized) return
        val online = VinorNetworkStatus.isOnline(this)
        binding.offlineBanner.visibility = if (online) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun registerConnectivityCallback() {
        if (connectivityCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runOnUiThread {
                    updateOfflineBanner()
                    VinorOfflineQueue.onNetworkRestored(this@MainActivity) { remaining ->
                        val wv = findWebPage()?.peekWebView()
                        VinorCacheBridge.dispatchSync(
                            wv,
                            remaining,
                            VinorWebOfflineSession.lastCachedAt(this@MainActivity),
                        )
                        if (wv != null) {
                            VinorConnectivityBridge.syncToWeb(
                                wv,
                                VinorNetworkStatus.current(this@MainActivity),
                                VinorWebOfflineSession.lastCachedAt(this@MainActivity),
                            )
                        }
                    }
                }
            }

            override fun onLost(network: android.net.Network) {
                runOnUiThread { updateOfflineBanner() }
            }
        }
        connectivityCallback = cb
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                cm.registerNetworkCallback(
                    android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    cb,
                )
            }
        } catch (_: Exception) {
            connectivityCallback = null
        }
    }

    private fun unregisterConnectivityCallback() {
        val cb = connectivityCallback ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        connectivityCallback = null
    }
}
