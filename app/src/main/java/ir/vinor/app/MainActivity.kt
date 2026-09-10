package ir.vinor.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import ir.vinor.app.databinding.ActivityMainBinding

/**
 * شِل نیتیو همکار: تب‌ها + ورود OTP + چت/فایل — بدون WebView تمام‌صفحه.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var notifyHelper: VinorNotifyHelper? = null
    private var keepSystemSplash = true

    private val navController: NavController
        get() = (supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { keepSystemSplash }
        super.onCreate(savedInstanceState)
        SystemUi.apply(this)
        CookieManager.getInstance().setAcceptCookie(true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val nav = navController
        binding.bottomNav.setupWithNavController(nav)
        nav.addOnDestinationChangedListener { _, dest, _ ->
            val hideTabs = dest.id == R.id.loginStep1Fragment ||
                dest.id == R.id.loginStep2Fragment ||
                dest.id == R.id.loginWebFragment ||
                dest.id == R.id.landDetailFragment ||
                dest.id == R.id.profileEditFragment ||
                dest.id == R.id.notificationsFragment ||
                dest.id == R.id.notesFragment
            binding.bottomNav.visibility = if (hideTabs) View.GONE else View.VISIBLE
        }

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
                if (!nav.popBackStack()) finish()
            }
        })

        handleDeepLink(intent)
        VinorAppUpdateChecker.check(this, webView = null)
        VinorFcmRegistrar.requestTokenAndRegister(this)
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
    }

    override fun onDestroy() {
        notifyHelper?.destroy()
        super.onDestroy()
    }

    fun requestNotificationPermission() {
        notifyHelper?.requestPermissionIfNeeded()
    }

    fun openPartnerHome() {
        val nav = navController
        if (!nav.popBackStack(R.id.dashboardFragment, false)) {
            nav.navigate(R.id.dashboardFragment)
        }
    }

    fun openLogin() {
        if (navController.currentDestination?.id != R.id.loginStep1Fragment) {
            navController.navigate(R.id.loginStep1Fragment)
        }
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
