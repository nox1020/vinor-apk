package ir.vinor.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import ir.vinor.app.databinding.ActivityMainBinding

/**
 * نسخه کاملا WebView:
 * - تمام ناوبری توسط وب‌سایت انجام می‌شود
 * - هیچ BottomNavigation/Fragment نیتیو استفاده نمی‌شود
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var offlineUi: VinorOfflineUi
    private lateinit var webView: WebView
    private lateinit var webViewFileChooser: VinorWebViewFileChooser
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var mainFrameLoadFailed = false
    private val revealAfterLoadRunnable = Runnable { finishSuccessfulPageLoadIfClean() }
    private var lastBackPressTime: Long = 0
    private val backPressThreshold = 2000L
    private val defaultUrl = VinorPartnerUrls.ENTRY
    private var otpSmsHelper: WebLoginOtpSmsHelper? = null
    private var speechBridge: VinorSpeechBridge? = null
    private var notifyHelper: VinorNotifyHelper? = null
    private var notifyBridge: VinorNotifyBridge? = null
    private var keepSystemSplash = true
    private var pendingInitialReveal = true
    private var partnerAppReady = false
    private var degradedOffline = false
    /** کاربر حداقل یک‌بار محتوای پنل را روی صفحه دیده — در آفلاین هرگز overlay تمام‌صفحه نشان نده */
    private var userHasActiveSession = false
    private var webViewStateRestored = false
    private val offlineRevealTimeoutMs = 4_500L
    private val offlineRevealTimeoutRunnable = Runnable {
        if (!isOnline() && ::webView.isInitialized && binding.offlineOverlay.root.visibility != View.VISIBLE) {
            enterDegradedOffline()
        }
    }
    private val shellRevealFallbackMs = 12_000L
    private val shellRevealFallbackRunnable = Runnable { onPartnerAppReady() }

    private fun canKeepCurrentWebContent(): Boolean =
        userHasActiveSession ||
            degradedOffline ||
            VinorWebOfflineSession.isContentRevealed(this) ||
            (::webView.isInitialized && VinorWebOfflineSession.webViewHasBrowsablePage(webView))

    /** در آفلاین هرگز WebView را خالی نکن و overlay تمام‌صفحه نشان نده */
    private fun shouldPreserveOfflineSession(): Boolean = !isOnline() && canKeepCurrentWebContent()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSystemSplash }
        super.onCreate(savedInstanceState)
        SystemUi.apply(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = WebViewProvider.attach(this, binding.webViewContainer)
        speechBridge = VinorSpeechBridge(this) { if (::webView.isInitialized) webView else null }.also {
            webView.addJavascriptInterface(it, VinorSpeechBridge.JS_INTERFACE_NAME)
        }
        notifyHelper = VinorNotifyHelper(this) { if (::webView.isInitialized) webView else null }.also { helper ->
            helper.attach(this)
            helper.handleOpenIntent(intent)
            notifyBridge = VinorNotifyBridge(helper).also { bridge ->
                webView.addJavascriptInterface(bridge, VinorNotifyBridge.JS_INTERFACE_NAME)
            }
        }
        webView.addJavascriptInterface(
            VinorShellBridge(
                onReady = { onPartnerAppReady() },
                onLogout = { onPartnerLoggedOut() }
            ),
            VinorShellBridge.JS_INTERFACE_NAME
        )
        webView.addJavascriptInterface(
            VinorConnectivityBridge { isOnline() },
            VinorConnectivityBridge.JS_INTERFACE_NAME
        )
        webView.addJavascriptInterface(
            VinorAppUpdateBridge(this) { if (::webView.isInitialized) webView else null },
            VinorAppUpdateBridge.JS_INTERFACE_NAME
        )
        webView.addJavascriptInterface(
            VinorDownloadBridge(this),
            VinorDownloadBridge.JS_INTERFACE_NAME
        )
        webViewFileChooser = VinorWebViewFileChooser(this).also { it.register(this) }
        val otpHelper = WebLoginOtpSmsHelper(this) { if (::webView.isInitialized) webView else null }
        otpSmsHelper = otpHelper
        webView.addJavascriptInterface(
            VinorOtpBridge(this) { otpSmsHelper },
            "VinorOtpBridge",
        )
        lifecycle.addObserver(otpHelper)
        setupRetryButton()
        setupBackPressHandler()
        setupWebView()
        userHasActiveSession = VinorWebOfflineSession.isContentRevealed(this)
        savedInstanceState?.let { bundle ->
            webViewStateRestored = webView.restoreState(bundle) != null
        }
        bootstrapWebContent()

        binding.root.post {
            notifyOtpPageIfNeeded(webView.url)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) {
            webView.saveState(outState)
        }
        super.onSaveInstanceState(outState)
    }

    private fun onPartnerLoggedOut() {
        runOnUiThread {
            VinorWebOfflineSession.clear(this)
            userHasActiveSession = false
            degradedOffline = false
            pendingInitialReveal = true
            partnerAppReady = false
        }
    }

    /**
     * بارگذاری اولیه: در آفلاین هرگز صفحهٔ زندهٔ WebView را بی‌دلیل reload نکن.
     */
    private fun bootstrapWebContent() {
        applyWebCacheMode()
        syncConnectivityToWeb()

        if (isOnline()) {
            if (webViewStateRestored && VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
                revealExistingWebContent()
                webView.post { loadEntryUrl(forceReload = true) }
                return
            }
            loadEntryUrl()
            return
        }

        degradedOffline = true

        if (webViewStateRestored && VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
            revealExistingWebContent()
            return
        }

        if (VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
            revealExistingWebContent()
            return
        }

        val lastUrl = VinorWebOfflineSession.lastPartnerUrl(this)
        if (!lastUrl.isNullOrBlank() && VinorWebOfflineSession.isContentRevealed(this)) {
            userHasActiveSession = true
            beginOfflineLoad(lastUrl)
            return
        }

        if (VinorWebOfflineSession.isContentRevealed(this)) {
            userHasActiveSession = true
            scheduleOfflineRevealTimeout()
            enterDegradedOffline()
            return
        }

        loadEntryUrl()
    }

    private fun revealExistingWebContent() {
        degradedOffline = !isOnline()
        userHasActiveSession = true
        pendingInitialReveal = false
        partnerAppReady = true
        cancelShellRevealFallback()
        cancelOfflineRevealTimeout()
        binding.offlineOverlay.root.visibility = View.GONE
        hideLoader()
        dismissSystemSplash()
        binding.progressBar.visibility = View.GONE
        webView.visibility = View.VISIBLE
        VinorWebOfflineSession.markSuccess(this, webView.url)
        syncConnectivityToWeb()
    }

    private fun scheduleOfflineRevealTimeout() {
        if (!::webView.isInitialized) return
        webView.removeCallbacks(offlineRevealTimeoutRunnable)
        webView.postDelayed(offlineRevealTimeoutRunnable, offlineRevealTimeoutMs)
    }

    private fun cancelOfflineRevealTimeout() {
        if (::webView.isInitialized) {
            webView.removeCallbacks(offlineRevealTimeoutRunnable)
        }
    }

    private fun beginOfflineLoad(url: String) {
        degradedOffline = true
        pendingInitialReveal = true
        partnerAppReady = false
        cancelShellRevealFallback()
        binding.offlineOverlay.root.visibility = View.GONE
        webView.visibility = View.INVISIBLE
        showLoader()
        syncConnectivityToWeb()
        scheduleOfflineRevealTimeout()
        webView.loadUrl(url)
    }

    private fun setupRetryButton() {
        offlineUi = VinorOfflineUi(binding.offlineOverlay.root)
        offlineUi.bindRetry {
            if (::webView.isInitialized) webView.removeCallbacks(revealAfterLoadRunnable)
            applyWebCacheMode()
            if (!isOnline()) {
                degradedOffline = true
                val lastUrl = VinorWebOfflineSession.lastPartnerUrl(this)
                if (!lastUrl.isNullOrBlank()) {
                    beginOfflineLoad(lastUrl)
                } else if (VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
                    revealExistingWebContent()
                } else {
                    loadEntryUrl()
                }
                binding.offlineOverlay.root.postDelayed({ offlineUi.setChecking(false) }, 480L)
                return@bindRetry
            }
            degradedOffline = false
            loadEntryUrl()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        VinorWebViewFileChooser.configureWebViewSettings(settings)
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
        settings.userAgentString = settings.userAgentString + " VinorApp/WebViewOnly" +
            if (appVersion.isNotEmpty()) "/$appVersion" else ""

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            settings.forceDark = WebSettings.FORCE_DARK_ON
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (VinorWebViewErrorShield.looksLikeChromeErrorPage(url)) {
                    if (shouldPreserveOfflineSession()) {
                        view?.stopLoading()
                        enterDegradedOffline()
                    } else {
                        showOfflineHard()
                    }
                    return true
                }
                if (isVinorDomain(url)) {
                    notifyOtpPageIfNeeded(url)
                    return false
                }
                return openExternal(url)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (!isOnline()) {
                    return super.shouldInterceptRequest(view, request)
                }
                if (VinorWebViewErrorShield.shouldInterceptChromeErrorMainFrame(request)) {
                    return VinorWebViewErrorShield.silentHtmlResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION")
            @Deprecated("API قدیمی برای زیر نسخه M")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    if (VinorWebViewErrorShield.looksLikeChromeErrorPage(url)) {
                        return VinorWebViewErrorShield.silentHtmlResponse()
                    }
                }
                return super.shouldInterceptRequest(view, url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (::webView.isInitialized) {
                    webView.removeCallbacks(revealAfterLoadRunnable)
                }
                if (VinorWebViewErrorShield.looksLikeChromeErrorPage(url)) {
                    if (shouldPreserveOfflineSession()) {
                        view?.stopLoading()
                        enterDegradedOffline()
                    } else {
                        showOfflineHard()
                    }
                    return
                }
                if (VinorWebViewErrorShield.isNeutralPlaceholderUrl(url)) return
                if (url?.startsWith("about:") == true) return
                if (pendingInitialReveal) {
                    partnerAppReady = false
                    cancelShellRevealFallback()
                }
                applyWebCacheMode()
                if (!isOnline()) {
                    degradedOffline = true
                    mainFrameLoadFailed = false
                    syncConnectivityToWeb()
                } else {
                    degradedOffline = false
                    mainFrameLoadFailed = false
                    hideOffline()
                    showLoader()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!::webView.isInitialized) return
                VinorLandDetailVolume.syncVolumeControlStream(
                    this@MainActivity,
                    url ?: webView.url
                )
                notifyOtpPageIfNeeded(url ?: webView.url)
                if (!isOnline() && pendingInitialReveal && isExpressPartnerUrl(url ?: webView.url)) {
                    partnerAppReady = true
                }
                webView.removeCallbacks(revealAfterLoadRunnable)
                probePartnerShellReady()
                VinorAppUpdateChecker.syncCachedToWebView(this@MainActivity, webView)
                syncConnectivityToWeb()
                webView.post(revealAfterLoadRunnable)
            }

            @SuppressLint("NewApi")
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
                if (!::webView.isInitialized) return
                if (VinorWebViewErrorShield.isNeutralPlaceholderUrl(url)) return
                if (url?.startsWith("about:") == true) return
                webView.removeCallbacks(revealAfterLoadRunnable)
                probePartnerShellReady()
                webView.post(revealAfterLoadRunnable)
            }

            @SuppressLint("NewApi")
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Log.w("MainActivity", "Main frame error: ${error?.errorCode} ${error?.description}")
                    if (!isOnline()) {
                        mainFrameLoadFailed = false
                        view?.stopLoading()
                        webView.post { enterDegradedOffline() }
                        return
                    }
                    mainFrameLoadFailed = true
                    VinorWebViewErrorShield.clearToSilentPlaceholder(view)
                    showOfflineHard()
                    return
                }
                super.onReceivedError(view, request, error)
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) return
                Log.w("MainActivity", "Main frame error (legacy): $errorCode $description")
                if (!isOnline()) {
                    mainFrameLoadFailed = false
                    view?.stopLoading()
                    webView.post { enterDegradedOffline() }
                    return
                }
                mainFrameLoadFailed = true
                VinorWebViewErrorShield.clearToSilentPlaceholder(view)
                showOfflineHard()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (!isOnline()) return
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return
                val code = errorResponse?.statusCode ?: return
                if (code == 404) return
                if (code == 408 || code == 429 || code in 500..599) {
                    mainFrameLoadFailed = true
                    VinorWebViewErrorShield.clearToSilentPlaceholder(view)
                    showOfflineHard()
                }
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, _, _ ->
            val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, null)
            VinorDownloadBridge.enqueueDownload(this, url, filename, showToast = true)
        }

        webView.webChromeClient = webViewFileChooser.createChromeClient { newProgress ->
            binding.progressBar.progress = newProgress
            binding.progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
        }
    }

    private fun isExpressPartnerUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("/express/partner")
    }

    fun onPartnerAppReady() {
        runOnUiThread {
            partnerAppReady = true
            cancelShellRevealFallback()
            if (::webView.isInitialized) {
                webView.removeCallbacks(revealAfterLoadRunnable)
                webView.post(revealAfterLoadRunnable)
                VinorAppUpdateChecker.check(this, webView)
            }
        }
    }

    private fun scheduleShellRevealFallback() {
        if (!::webView.isInitialized) return
        webView.removeCallbacks(shellRevealFallbackRunnable)
        webView.postDelayed(shellRevealFallbackRunnable, shellRevealFallbackMs)
    }

    private fun cancelShellRevealFallback() {
        if (::webView.isInitialized) {
            webView.removeCallbacks(shellRevealFallbackRunnable)
        }
    }

    private fun probePartnerShellReady() {
        if (!::webView.isInitialized || !pendingInitialReveal) return
        webView.evaluateJavascript(
            """
            (function () {
              try {
                if (!/VinorApp\//i.test(navigator.userAgent || '')) return;
                if (!window.VinorNativeShell || typeof VinorNativeShell.onAppReady !== 'function') return;
                var sk = document.getElementById('vinorPartnerSkeletonRoot');
                if (!sk) { VinorNativeShell.onAppReady(); return; }
                if (sk.getAttribute('data-vinor-sk-dismissed') === '1') VinorNativeShell.onAppReady();
              } catch (_) {}
            })();
            """.trimIndent(),
            null
        )
    }

    private fun loadEntryUrl(forceReload: Boolean = false) {
        if (!forceReload && !isOnline()) {
            if (VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
                revealExistingWebContent()
                return
            }
            val lastUrl = VinorWebOfflineSession.lastPartnerUrl(this)
            if (!lastUrl.isNullOrBlank() && VinorWebOfflineSession.isContentRevealed(this)) {
                beginOfflineLoad(lastUrl)
                return
            }
        }
        pendingInitialReveal = true
        partnerAppReady = false
        cancelShellRevealFallback()
        applyWebCacheMode()
        if (!isOnline()) {
            degradedOffline = true
            beginOfflineLoad(extractStartUrl(intent?.dataString))
            return
        }
        degradedOffline = false
        hideOffline()
        webView.loadUrl(extractStartUrl(intent?.dataString))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val isNotifyIntent = intent.getBooleanExtra(VinorNotifyHelper.EXTRA_OPEN_NOTIFICATIONS, false)
            || intent.action == VinorNotificationTray.ACTION_OPEN
            || !intent.getStringExtra(VinorNotificationTray.EXTRA_URL).isNullOrBlank()
        notifyHelper?.handleOpenIntent(intent)
        if (isNotifyIntent) return
        val target = extractStartUrl(intent.dataString)
        if (!isOnline()) {
            if (VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
                revealExistingWebContent()
                return
            }
            beginOfflineLoad(target)
            return
        }
        degradedOffline = false
        pendingInitialReveal = true
        partnerAppReady = false
        cancelShellRevealFallback()
        hideOffline()
        webView.loadUrl(target)
    }

    private fun extractStartUrl(deepLinkUrl: String?): String {
        if (deepLinkUrl.isNullOrBlank()) return defaultUrl
        return if (isVinorDomain(deepLinkUrl)) {
            VinorPartnerUrls.normalizeEntryUrl(deepLinkUrl)
        } else {
            defaultUrl
        }
    }

    /** بارگذاری URL پنل — پس از ورود یا deep link. */
    fun openPartnerUrl(url: String, clearHistory: Boolean = false) {
        if (!::webView.isInitialized) return
        val target = VinorPartnerUrls.normalizeEntryUrl(url)
        applyWebCacheMode()
        if (!isOnline()) {
            beginOfflineLoad(target)
            if (clearHistory) {
                webView.post { webView.clearHistory() }
            }
            return
        }
        degradedOffline = false
        pendingInitialReveal = true
        partnerAppReady = false
        cancelShellRevealFallback()
        hideOffline()
        webView.loadUrl(target)
        if (clearHistory) {
            webView.post { webView.clearHistory() }
        }
    }

    private fun notifyOtpPageIfNeeded(url: String?) {
        otpSmsHelper?.onUrlChanged(url)
        otpSmsHelper?.onPageContentReady()
    }

    private fun isVinorDomain(url: String): Boolean {
        return runCatching {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            host == "vinor.ir" || host.endsWith(".vinor.ir")
        }.getOrDefault(false)
    }

    private fun openExternal(url: String): Boolean {
        return try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Unable to open external url: $url", e)
            false
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun showLoader() {
        binding.loaderContainer.visibility = View.VISIBLE
        binding.splashView.root.visibility = View.VISIBLE
    }

    private fun hideLoader() {
        binding.loaderContainer.visibility = View.GONE
        binding.splashView.root.visibility = View.GONE
    }

    private fun dismissSystemSplash() {
        keepSystemSplash = false
    }

    private fun applyWebCacheMode() {
        if (!::webView.isInitialized) return
        webView.settings.cacheMode = if (isOnline()) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
    }

    private fun syncConnectivityToWeb() {
        if (!::webView.isInitialized) return
        VinorConnectivityBridge.syncToWeb(webView, isOnline())
    }

    private fun enterDegradedOffline() {
        degradedOffline = true
        userHasActiveSession = true
        applyWebCacheMode()
        pendingInitialReveal = false
        cancelShellRevealFallback()
        cancelOfflineRevealTimeout()
        binding.offlineOverlay.root.visibility = View.GONE
        if (::webView.isInitialized) {
            webView.removeCallbacks(revealAfterLoadRunnable)
            webView.visibility = View.VISIBLE
            val url = webView.url
            if (VinorWebOfflineSession.isPartnerBrowsableUrl(url)) {
                VinorWebOfflineSession.markSuccess(this, url)
            }
        }
        hideLoader()
        dismissSystemSplash()
        binding.progressBar.visibility = View.GONE
        if (::offlineUi.isInitialized) offlineUi.reset()
        syncConnectivityToWeb()
    }

    private fun onConnectivityRestored() {
        if (!isOnline()) return
        applyWebCacheMode()
        degradedOffline = false
        syncConnectivityToWeb()
        if (binding.offlineOverlay.root.visibility == View.VISIBLE) {
            loadEntryUrl(forceReload = true)
        }
    }

    private fun onConnectivityLost() {
        degradedOffline = true
        applyWebCacheMode()
        if (::webView.isInitialized) {
            webView.stopLoading()
        }
        if (canKeepCurrentWebContent()) {
            enterDegradedOffline()
        } else {
            syncConnectivityToWeb()
        }
    }

    private fun showOfflineOverlayOnly() {
        if (shouldPreserveOfflineSession()) {
            enterDegradedOffline()
            return
        }
        dismissSystemSplash()
        pendingInitialReveal = false
        cancelShellRevealFallback()
        if (::webView.isInitialized) {
            webView.removeCallbacks(revealAfterLoadRunnable)
            webView.visibility = View.GONE
        }
        hideLoader()
        binding.progressBar.visibility = View.GONE
        val overlay = binding.offlineOverlay.root
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        if (::offlineUi.isInitialized) offlineUi.reset()
    }

    private fun showOfflineHard() {
        if (shouldPreserveOfflineSession()) {
            if (::webView.isInitialized) webView.stopLoading()
            enterDegradedOffline()
            return
        }
        dismissSystemSplash()
        pendingInitialReveal = false
        cancelShellRevealFallback()
        degradedOffline = false
        if (::webView.isInitialized) {
            webView.removeCallbacks(revealAfterLoadRunnable)
            webView.visibility = View.GONE
            VinorWebViewErrorShield.clearToSilentPlaceholder(webView)
        }
        hideLoader()
        binding.progressBar.visibility = View.GONE
        val overlay = binding.offlineOverlay.root
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        if (::offlineUi.isInitialized) offlineUi.reset()
    }

    private fun hideOffline() {
        binding.offlineOverlay.root.visibility = View.GONE
        webView.visibility = View.INVISIBLE
        if (::offlineUi.isInitialized) offlineUi.reset()
    }

    private fun finishSuccessfulPageLoadIfClean() {
        if (!::webView.isInitialized) return
        val curUrl = webView.url
        cancelOfflineRevealTimeout()
        if (mainFrameLoadFailed) {
            mainFrameLoadFailed = false
            if (!isOnline()) {
                enterDegradedOffline()
                return
            }
            if (shouldPreserveOfflineSession()) {
                enterDegradedOffline()
                return
            }
            showOfflineOverlayOnly()
            return
        }
        if (VinorWebViewErrorShield.looksLikeChromeErrorPage(curUrl)) {
            if (!isOnline() || shouldPreserveOfflineSession()) {
                webView.stopLoading()
                if (webView.canGoBack()) webView.goBack()
                enterDegradedOffline()
                return
            }
            showOfflineHard()
            return
        }
        if (VinorWebViewErrorShield.isNeutralPlaceholderUrl(curUrl)) {
            if (!isOnline()) {
                if (shouldPreserveOfflineSession() || VinorWebOfflineSession.isContentRevealed(this)) {
                    if (webView.canGoBack()) webView.goBack()
                    enterDegradedOffline()
                } else {
                    showOfflineOverlayOnly()
                }
            }
            return
        }
        if (!isOnline()) {
            enterDegradedOffline()
        }
        if (pendingInitialReveal && isExpressPartnerUrl(curUrl) && !partnerAppReady) {
            if (!isOnline()) {
                partnerAppReady = true
            } else {
                scheduleShellRevealFallback()
                probePartnerShellReady()
                return
            }
        }
        if (pendingInitialReveal) {
            pendingInitialReveal = false
            cancelShellRevealFallback()
        }
        hideLoader()
        dismissSystemSplash()
        webView.visibility = View.VISIBLE
        userHasActiveSession = true
        VinorWebOfflineSession.markSuccess(this, curUrl)
    }

    private fun setupBackPressHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < backPressThreshold) {
                    finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(
                        this@MainActivity,
                        "برای خروج دوباره دکمه بازگشت را فشار دهید",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    // برای سازگاری با کدهای قدیمی فراخوانی‌کننده از BaseWebViewFragment.
    fun setBottomNavVisible(visible: Boolean) = Unit

    override fun onStart() {
        super.onStart()
        registerConnectivityLostCallback()
    }

    override fun onStop() {
        unregisterConnectivityLostCallback()
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            VinorLandDetailVolume.syncVolumeControlStream(this, webView.url)
            VinorAppUpdateChecker.check(this, webView)
            applyWebCacheMode()
            syncConnectivityToWeb()
            if (!isOnline() && canKeepCurrentWebContent()) {
                enterDegradedOffline()
            }
        }
        VinorFcmRegistrar.requestTokenAndRegister(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::webView.isInitialized && event.action == KeyEvent.ACTION_DOWN) {
            VinorLandDetailVolume.onVolumeKeyDown(this, webView, event.keyCode)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        speechBridge?.destroy()
        speechBridge = null
        notifyHelper?.destroy()
        notifyHelper = null
        notifyBridge = null
        if (::webView.isInitialized) {
            webView.removeCallbacks(revealAfterLoadRunnable)
            webView.removeCallbacks(shellRevealFallbackRunnable)
            webView.removeCallbacks(offlineRevealTimeoutRunnable)
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun registerConnectivityLostCallback() {
        if (connectivityCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                runOnUiThread {
                    if (!isOnline()) onConnectivityLost()
                }
            }

            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (isOnline()) onConnectivityRestored()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                runOnUiThread {
                    if (isOnline()) onConnectivityRestored()
                    else onConnectivityLost()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, connectivityCallback!!)
    }

    private fun unregisterConnectivityLostCallback() {
        val cb = connectivityCallback ?: return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        connectivityCallback = null
    }
}
