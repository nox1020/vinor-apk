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
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import ir.vinor.app.databinding.ActivityMainBinding
import org.json.JSONArray

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
    private val defaultUrl = VinorPartnerUrls.ENTRY
    private var otpSmsHelper: WebLoginOtpSmsHelper? = null
    private var speechBridge: VinorSpeechBridge? = null
    private var notifyHelper: VinorNotifyHelper? = null
    private var notifyBridge: VinorNotifyBridge? = null
    private var cacheBridge: VinorCacheBridge? = null
    private var keepSystemSplash = true
    private var pendingInitialReveal = true
    private var partnerAppReady = false
    private var degradedOffline = false
    /** کاربر حداقل یک‌بار محتوای پنل را روی صفحه دیده — در آفلاین هرگز overlay تمام‌صفحه نشان نده */
    private var userHasActiveSession = false
    private var webViewStateRestored = false
    private var offlineAssetWarmupDone = false
    private var lastHandledNetState: VinorNetState? = null
    private var recoveringRenderer = false
    private var webViewAlive = true
    private var launchedWithOfflineFallback = false
    private var swUnregisteredForOffline = false
    private var offlineLoadInProgress = false
    private var lastOfflineRestoreAt = 0L
    private var startLoadRetries = 0
    private val offlineRevealTimeoutMs = 4_500L
    private val offlineRevealTimeoutRunnable = Runnable {
        if (isOnline() || !isUiAlive()) return@Runnable
        if (isWebViewUsable() && currentPageIsKeepable()) {
            enterDegradedOffline()
            return@Runnable
        }
        if (isWebViewUsable() && canKeepCurrentWebContent()) {
            enterDegradedOffline()
            return@Runnable
        }
        presentOfflineOverlay()
    }
    private val shellRevealFallbackMs = 12_000L
    private val shellRevealFallbackRunnable = Runnable { onPartnerAppReady() }

    private fun canKeepCurrentWebContent(): Boolean =
        userHasActiveSession ||
            degradedOffline ||
            VinorWebOfflineSession.isContentRevealed(this) ||
            VinorOfflinePageStore.hasAny(this) ||
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
        webViewAlive = true
        speechBridge = VinorSpeechBridge(this) { if (::webView.isInitialized && webViewAlive) webView else null }.also {
            webView.addJavascriptInterface(it, VinorSpeechBridge.JS_INTERFACE_NAME)
        }
        notifyHelper = VinorNotifyHelper(this) { if (::webView.isInitialized && webViewAlive) webView else null }.also { helper ->
            helper.attach(this)
            helper.requestPermissionIfNeeded()
            helper.handleOpenIntent(intent, immediate = false)
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
            VinorConnectivityBridge { VinorNetworkStatus.current(this) },
            VinorConnectivityBridge.JS_INTERFACE_NAME
        )
        cacheBridge = VinorCacheBridge(this) { if (::webView.isInitialized && webViewAlive) webView else null }.also {
            webView.addJavascriptInterface(it, VinorCacheBridge.JS_INTERFACE_NAME)
        }
        webView.addJavascriptInterface(
            VinorAppUpdateBridge(this) { if (::webView.isInitialized && webViewAlive) webView else null },
            VinorAppUpdateBridge.JS_INTERFACE_NAME
        )
        webView.addJavascriptInterface(
            VinorDownloadBridge(this) { if (::webView.isInitialized && webViewAlive) webView else null },
            VinorDownloadBridge.JS_INTERFACE_NAME
        )
        webViewFileChooser = VinorWebViewFileChooser(this).also { it.register(this) }
        val otpHelper = WebLoginOtpSmsHelper(this) { if (::webView.isInitialized && webViewAlive) webView else null }
        otpSmsHelper = otpHelper
        webView.addJavascriptInterface(
            VinorOtpBridge(this) { otpSmsHelper },
            "VinorOtpBridge",
        )
        lifecycle.addObserver(otpHelper)
        setupRetryButton()
        setupBackPressHandler()
        setupWebView()
        VinorCacheSession.fingerprint(this)
        if (::webView.isInitialized) {
            VinorWebCacheManager.onAppStart(this, webView, isOnline())
        }
        userHasActiveSession = VinorWebOfflineSession.isContentRevealed(this)
        savedInstanceState?.let { bundle ->
            webViewStateRestored = runCatching { webView.restoreState(bundle) != null }.getOrDefault(false)
        }
        VinorAppLinksCerts.logForAssetLinks(this)
        bootstrapWebContent()

        binding.root.post {
            notifyOtpPageIfNeeded(currentWebUrl())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        runCatching {
            if (::webView.isInitialized) {
                webView.saveState(outState)
            }
        }
        super.onSaveInstanceState(outState)
    }

    private fun onPartnerLoggedOut() {
        runOnUiThread {
            if (!isUiAlive()) return@runOnUiThread
            VinorFcmRegistrar.unregisterCurrentToken(this)
            userHasActiveSession = false
            degradedOffline = false
            pendingInitialReveal = true
            partnerAppReady = false
            try {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies { _ ->
                    cm.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "cookie clear on logout failed", e)
            }
            if (::webView.isInitialized) {
                VinorWebCacheManager.onLogout(this, webView)
            } else {
                VinorWebOfflineSession.clear(this)
            }
        }
    }

    /**
     * بارگذاری اولیه: در آفلاین هرگز صفحهٔ زندهٔ WebView را بی‌دلیل reload نکن.
     */
    private fun bootstrapWebContent() {
        applyWebCacheMode()
        syncConnectivityToWeb()

        if (isOnline()) {
            notifyHelper?.consumePendingOpenUrl()?.let { notifyUrl ->
                pendingInitialReveal = true
                partnerAppReady = false
                hideOffline()
                webView.loadUrl(notifyUrl, VinorUserAgent.webViewLoadHeaders(this))
                return
            }
            if (webViewStateRestored && VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
                revealExistingWebContent()
                webView.post { loadEntryUrl(forceReload = true) }
                return
            }
            loadEntryUrl()
            return
        }

        degradedOffline = true
        launchedWithOfflineFallback = true

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
        if (isWebViewUsable()) {
            runCatching { webView.visibility = View.VISIBLE }
            VinorWebOfflineSession.markSuccess(this, currentWebUrl())
        }
        syncConnectivityToWeb()
    }

    private fun scheduleOfflineRevealTimeout() {
        if (!isWebViewUsable()) return
        runCatching {
            webView.removeCallbacks(offlineRevealTimeoutRunnable)
            webView.postDelayed(offlineRevealTimeoutRunnable, offlineRevealTimeoutMs)
        }
    }

    private fun cancelOfflineRevealTimeout() {
        if (isWebViewUsable()) {
            runCatching { webView.removeCallbacks(offlineRevealTimeoutRunnable) }
        }
    }

    private fun beginOfflineLoad(url: String) {
        if (!isUiAlive()) return
        if (!isWebViewUsable()) {
            showOverlayAfterDeadRenderer()
            return
        }
        if (offlineLoadInProgress) return
        if (currentPageIsKeepable() &&
            VinorOfflinePageStore.read(this, url).isNullOrBlank()
        ) {
            enterDegradedOffline()
            return
        }
        offlineLoadInProgress = true
        try {
            launchedWithOfflineFallback = true
            degradedOffline = true
            pendingInitialReveal = true
            partnerAppReady = false
            cancelShellRevealFallback()
            binding.offlineOverlay.root.visibility = View.GONE
            webView.visibility = View.INVISIBLE
            showLoader()
            syncConnectivityToWeb()
            scheduleOfflineRevealTimeout()
            if (renderOfflineSnapshot(url)) {
                return
            }
            val lastUrl = VinorWebOfflineSession.lastPartnerUrl(this)
            if (!lastUrl.isNullOrBlank() && lastUrl != url && renderOfflineSnapshot(lastUrl)) {
                return
            }
            if (renderLatestOfflineSnapshot()) {
                return
            }
            if (currentPageIsKeepable()) {
                enterDegradedOffline()
                return
            }
            presentOfflineOverlay()
        } finally {
            offlineLoadInProgress = false
        }
    }

    private fun setupRetryButton() {
        offlineUi = VinorOfflineUi(binding.offlineOverlay.root)
        offlineUi.bindRetry {
            if (isWebViewUsable()) {
                runCatching { webView.removeCallbacks(revealAfterLoadRunnable) }
            }
            applyWebCacheMode()
            if (!isOnline()) {
                degradedOffline = true
                if (currentPageIsKeepable()) {
                    revealExistingWebContent()
                } else {
                    val lastUrl = VinorWebOfflineSession.lastPartnerUrl(this)
                    if (!lastUrl.isNullOrBlank()) {
                        beginOfflineLoad(lastUrl)
                    } else {
                        loadEntryUrl()
                    }
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
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
        VinorWebViewSettings.apply(
            settings,
            userAgentSuffix = " VinorApp/WebViewOnly" +
                if (appVersion.isNotEmpty()) "/$appVersion" else ""
        )
        applyWebCacheMode()
        VinorWebViewFileChooser.configureWebViewSettings(settings)
        VinorWebViewSettings.configureCookies(webView)
        VinorWebViewSettings.applyDarkening(settings)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                val method = request.method?.uppercase().orEmpty().ifBlank { "GET" }
                return handleUrlLoading(view, url, method)
            }

            @Suppress("DEPRECATION")
            @Deprecated("API قدیمی برای زیر نسخه N")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url?.trim().orEmpty()
                if (target.isEmpty()) return false
                return handleUrlLoading(view, target, "GET")
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                return try {
                    val intercepted = VinorWebResourceInterceptor.intercept(
                        this@MainActivity,
                        request,
                        cacheFirst = VinorNetworkStatus.useCacheFirst(this@MainActivity),
                    )
                    if (intercepted != null) intercepted
                    else super.shouldInterceptRequest(view, request)
                } catch (t: Throwable) {
                    Log.w("MainActivity", "shouldInterceptRequest failed", t)
                    if (VinorNetworkStatus.useCacheFirst(this@MainActivity)) {
                        VinorWebResponses.forCacheMiss("application/javascript")
                    } else {
                        null
                    }
                }
            }

            @Suppress("DEPRECATION")
            @Deprecated("API قدیمی برای زیر نسخه M")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                return try {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        if (VinorWebViewErrorShield.looksLikeChromeErrorPage(url)) {
                            return VinorWebViewErrorShield.silentHtmlResponse()
                        }
                        return VinorWebResourceInterceptor.interceptLegacyUrl(
                            this@MainActivity,
                            url,
                            cacheFirst = VinorNetworkStatus.useCacheFirst(this@MainActivity),
                        )
                    }
                    super.shouldInterceptRequest(view, url)
                } catch (t: Throwable) {
                    Log.w("MainActivity", "shouldInterceptRequest(legacy) failed", t)
                    if (VinorNetworkStatus.useCacheFirst(this@MainActivity)) {
                        VinorWebResponses.forCacheMiss("application/javascript")
                    } else {
                        null
                    }
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
                Log.e(TAG, "WebView renderer gone crash=${detail?.didCrash()}")
                recoverFromRendererGone()
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (isWebViewUsable()) {
                    runCatching { webView.removeCallbacks(revealAfterLoadRunnable) }
                }
                if (VinorWebViewErrorShield.looksLikeChromeErrorPage(url)) {
                    if (shouldPreserveOfflineSession() || !isOnline()) {
                        view?.stopLoading()
                        restoreOfflineMainFrame(VinorWebOfflineSession.lastPartnerUrl(this@MainActivity))
                    } else {
                        showOfflineHard()
                    }
                    return
                }
                if (VinorWebViewErrorShield.isNeutralPlaceholderUrl(url)) return
                if (url?.startsWith("about:") == true) return
                VinorBridgeGate.rememberUrl(url)
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
                if (!isWebViewUsable()) return
                val resolved = url ?: currentWebUrl()
                VinorBridgeGate.rememberUrl(resolved)
                VinorLandDetailVolume.syncVolumeControlStream(
                    this@MainActivity,
                    resolved
                )
                VinorLandDetailVolume.kickGallery(webView)
                notifyOtpPageIfNeeded(resolved)
                if (!isOnline() && pendingInitialReveal && isExpressPartnerUrl(resolved)) {
                    partnerAppReady = true
                }
                webView.removeCallbacks(revealAfterLoadRunnable)
                probePartnerShellReady()
                VinorAppUpdateChecker.syncCachedToWebView(this@MainActivity, webView)
                VinorCacheSession.fingerprint(this@MainActivity)
                syncConnectivityToWeb()
                cacheCurrentPageSnapshot(resolved)
                if (isOnline() && !offlineAssetWarmupDone) {
                    offlineAssetWarmupDone = true
                    VinorOfflineAssetStore.prewarmDefaults(this@MainActivity)
                }
                maybeResumePendingDeepLinkAfterAuth(resolved)
                webView.post(revealAfterLoadRunnable)
            }

            @SuppressLint("NewApi")
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
                if (!isWebViewUsable()) return
                if (VinorWebViewErrorShield.isNeutralPlaceholderUrl(url)) return
                if (url?.startsWith("about:") == true) return
                VinorLandDetailVolume.kickGallery(webView)
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
                        runCatching { view?.stopLoading() }
                        if (isWebViewUsable()) {
                            webView.post {
                                if (isWebViewUsable()) {
                                    restoreOfflineMainFrame(request.url?.toString())
                                }
                            }
                        }
                        return
                    }
                    if (pendingInitialReveal && startLoadRetries < 2) {
                        startLoadRetries += 1
                        Log.w("MainActivity", "Main frame error on start; retry $startLoadRetries")
                        webView.post { loadEntryUrl(forceReload = true) }
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
                    runCatching { view?.stopLoading() }
                    if (isWebViewUsable()) {
                        webView.post {
                            if (isWebViewUsable()) restoreOfflineMainFrame(failingUrl)
                        }
                    }
                    return
                }
                if (pendingInitialReveal && startLoadRetries < 2) {
                    startLoadRetries += 1
                    Log.w("MainActivity", "Main frame error on start (legacy); retry $startLoadRetries")
                    webView.post { loadEntryUrl(forceReload = true) }
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

        webView.webChromeClient = webViewFileChooser.createChromeClient()
    }

    private fun isExpressPartnerUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("/express/partner")
    }

    fun onPartnerAppReady() {
        runOnUiThread {
            if (!isUiAlive()) return@runOnUiThread
            partnerAppReady = true
            cancelShellRevealFallback()
            maybeResumePendingDeepLinkAfterAuth(currentWebUrl())
            if (isWebViewUsable()) {
                runCatching {
                    webView.removeCallbacks(revealAfterLoadRunnable)
                    webView.post(revealAfterLoadRunnable)
                }
                VinorAppUpdateChecker.check(this, webView)
                VinorApiCacheStore.warmup(this)
                VinorOfflineAssetStore.prewarmDefaults(this)
            }
        }
    }

    private fun scheduleShellRevealFallback() {
        if (!isWebViewUsable()) return
        runCatching {
            webView.removeCallbacks(shellRevealFallbackRunnable)
            webView.postDelayed(shellRevealFallbackRunnable, shellRevealFallbackMs)
        }
    }

    private fun cancelShellRevealFallback() {
        if (isWebViewUsable()) {
            runCatching { webView.removeCallbacks(shellRevealFallbackRunnable) }
        }
    }

    private fun probePartnerShellReady() {
        if (!isWebViewUsable() || !pendingInitialReveal) return
        VinorSafe.evalJs(
            webView,
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
        )
    }

    private fun cacheCurrentPageSnapshot(url: String?) {
        val target = (url ?: "").trim()
        if (!VinorWebOfflineSession.isPartnerBrowsableUrl(target)) return
        VinorSafe.evalJs(
            webView,
            """
            (function(){
              try {
                var html = document.documentElement.outerHTML || '';
                if (!html || html.length < 200) return '';
                var assets = [];
                var seen = {};
                function add(u){
                  if (!u) return;
                  var s = String(u);
                  if (seen[s]) return;
                  seen[s] = 1;
                  assets.push(s);
                }
                var nodes = document.querySelectorAll('link[href],script[src],img[src],source[src],video[poster]');
                for (var i = 0; i < nodes.length && assets.length < 80; i++) {
                  var el = nodes[i];
                  add(el.href || el.src || el.poster || '');
                }
                return JSON.stringify({ html: html, assets: assets, t: Date.now() });
              } catch (_) { return ''; }
            })();
            """.trimIndent(),
        ) { raw ->
            if (!isUiAlive()) return@evalJs
            val decoded = decodeJsStringResult(raw)
            if (decoded.isNullOrBlank()) return@evalJs
            val html: String
            val assets: List<String>
            if (decoded.trimStart().startsWith("{")) {
                val obj = runCatching { org.json.JSONObject(decoded) }.getOrNull()
                html = obj?.optString("html").orEmpty()
                val arr = obj?.optJSONArray("assets")
                assets = buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) add(arr.optString(i))
                    }
                }
            } else {
                html = decoded
                assets = emptyList()
            }
            if (html.isNotBlank()) {
                VinorOfflinePageStore.save(this, target, html)
                VinorOfflineAssetStore.cacheMany(this, assets)
            }
        }
    }

    private fun renderOfflineSnapshot(url: String?): Boolean {
        if (!isWebViewUsable()) return false
        val target = (url ?: "").trim()
        val html = VinorOfflinePageStore.read(this, target) ?: return false
        val base = if (target.isNotBlank()) target else VinorPartnerUrls.ENTRY
        return runCatching {
            webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", base)
            true
        }.getOrDefault(false)
    }

    private fun renderLatestOfflineSnapshot(): Boolean {
        if (!isWebViewUsable()) return false
        val html = VinorOfflinePageStore.readLatest(this) ?: return false
        val base = VinorWebOfflineSession.lastPartnerUrl(this) ?: VinorPartnerUrls.ENTRY
        return runCatching {
            webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", base)
            true
        }.getOrDefault(false)
    }

    private fun handleUrlLoading(view: WebView?, url: String, method: String): Boolean {
        if (VinorWebViewErrorShield.looksLikeChromeErrorPage(url)) {
            view?.stopLoading()
            if (!isOnline() || shouldPreserveOfflineSession()) {
                restoreOfflineMainFrame(VinorWebOfflineSession.lastPartnerUrl(this))
            } else {
                showOfflineHard()
            }
            return true
        }
        if (url.startsWith("tel:") || url.startsWith("mailto:") ||
            url.startsWith("sms:") || url.startsWith("smsto:")
        ) {
            return openExternal(url)
        }
        if (UrlUtils.isApkDownloadUrl(url)) {
            val ver = VinorAppUpdateChecker.readCached(this).version
            VinorDownloadBridge.enqueueDownload(
                this,
                url,
                VinorDownloadBridge.apkDownloadFilename(url, ver),
                showToast = true,
            )
            return true
        }
        if (isVinorDomain(url)) {
            notifyOtpPageIfNeeded(url)
            if (VinorNetworkStatus.useCacheFirst(this)) {
                view?.stopLoading()
                if (method != "GET") {
                    if (VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
                        enterDegradedOffline()
                    } else {
                        restoreOfflineMainFrame(url)
                    }
                    return true
                }
                navigateOfflinePartner(url)
                return true
            }
            return false
        }
        return openExternal(url)
    }

    private fun navigateOfflinePartner(url: String) {
        val hasSnapshot = !VinorOfflinePageStore.read(this, url).isNullOrBlank()
        if (hasSnapshot) {
            beginOfflineLoad(url)
            return
        }
        if (VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
            enterDegradedOffline()
            return
        }
        restoreOfflineMainFrame(url)
    }

    private fun restoreOfflineMainFrame(failedUrl: String?) {
        if (currentPageIsKeepable()) {
            enterDegradedOffline()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastOfflineRestoreAt < 1_500L) {
            if (canKeepCurrentWebContent()) enterDegradedOffline() else presentOfflineOverlay()
            return
        }
        lastOfflineRestoreAt = now
        val candidates = listOf(
            failedUrl?.trim().orEmpty(),
            VinorWebOfflineSession.lastPartnerUrl(this).orEmpty(),
            defaultUrl,
        ).filter { it.isNotBlank() }.distinct()
        val snapshotUrl = candidates.firstOrNull { !VinorOfflinePageStore.read(this, it).isNullOrBlank() }
        beginOfflineLoad(snapshotUrl ?: candidates.firstOrNull() ?: defaultUrl)
    }

    private fun maybeUnregisterSwForOffline() {
        if (swUnregisteredForOffline || isOnline() || !isWebViewUsable()) return
        swUnregisteredForOffline = true
        VinorWebCacheManager.unregisterServiceWorkers(webView)
    }

    private fun decodeJsStringResult(raw: String?): String? {
        val src = raw?.trim().orEmpty()
        if (src.isEmpty() || src == "null" || src == "undefined") return null
        if (src.length > VinorCachePolicy.MAX_HTML_BYTES + 64_000) return null
        return runCatching {
            JSONArray("[$src]").optString(0, "")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun loadEntryUrl(forceReload: Boolean = false) {
        if (!forceReload && !isOnline()) {
            if (VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
                revealExistingWebContent()
                return
            }
            val target = resolveDeepLinkLoadUrl(intent?.dataString)
            if (!VinorOfflinePageStore.read(this, target).isNullOrBlank()) {
                beginOfflineLoad(target)
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
        launchedWithOfflineFallback = false
        if (consumeApkDownloadDeepLink(intent?.dataString)) {
            if (!isWebViewUsable()) return
            degradedOffline = false
            hideOffline()
            runCatching { webView.loadUrl(defaultUrl, VinorUserAgent.webViewLoadHeaders(this)) }
            return
        }
        if (!isOnline()) {
            degradedOffline = true
            beginOfflineLoad(resolveDeepLinkLoadUrl(intent?.dataString))
            return
        }
        if (!isWebViewUsable()) return
        degradedOffline = false
        hideOffline()
        runCatching {
            webView.loadUrl(resolveDeepLinkLoadUrl(intent?.dataString), VinorUserAgent.webViewLoadHeaders(this))
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val isNotifyIntent = intent.getBooleanExtra(VinorNotifyHelper.EXTRA_OPEN_NOTIFICATIONS, false)
            || intent.action == VinorNotificationTray.ACTION_OPEN
            || !intent.getStringExtra(VinorNotificationTray.EXTRA_URL).isNullOrBlank()
        notifyHelper?.handleOpenIntent(intent)
        if (isNotifyIntent) return
        if (consumeApkDownloadDeepLink(intent.dataString)) {
            if (!isOnline()) {
                if (VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
                    revealExistingWebContent()
                    return
                }
                beginOfflineLoad(defaultUrl)
                return
            }
            if (!isWebViewUsable()) return
            degradedOffline = false
            pendingInitialReveal = true
            partnerAppReady = false
            cancelShellRevealFallback()
            hideOffline()
            runCatching { webView.loadUrl(defaultUrl, VinorUserAgent.webViewLoadHeaders(this)) }
            return
        }
        val target = resolveDeepLinkLoadUrl(intent.dataString)
        if (!isOnline()) {
            if (VinorWebOfflineSession.webViewHasBrowsablePage(webView)) {
                revealExistingWebContent()
                return
            }
            beginOfflineLoad(target)
            return
        }
        if (!isWebViewUsable()) return
        degradedOffline = false
        pendingInitialReveal = true
        partnerAppReady = false
        cancelShellRevealFallback()
        hideOffline()
        runCatching { webView.loadUrl(target, VinorUserAgent.webViewLoadHeaders(this)) }
    }

    /**
     * Deep Link → [DeepLinkRouter]؛ در صورت نیاز به لاگین، صفحه ورود + نگهداری لینک اولیه.
     */
    private fun consumeApkDownloadDeepLink(deepLinkUrl: String?): Boolean {
        if (deepLinkUrl.isNullOrBlank()) return false
        val route = DeepLinkRouter.resolve(deepLinkUrl)
        val isApk = UrlUtils.isApkDownloadUrl(deepLinkUrl) ||
            route.type == DeepLinkRouter.DestinationType.APK_DOWNLOAD
        if (!isApk) return false
        // download-app و مسیرهای غیر-/d را به لینک کوتاه دانلود واقعی ببر
        val downloadUrl = when {
            UrlUtils.isApkDownloadUrl(deepLinkUrl) -> deepLinkUrl
            UrlUtils.isApkDownloadUrl(route.targetUrl) -> route.targetUrl
            else -> "https://vinor.ir/d"
        }
        VinorDownloadBridge.enqueueDownload(
            this,
            downloadUrl,
            VinorDownloadBridge.apkDownloadFilename(
                downloadUrl,
                VinorAppUpdateChecker.readCached(this).version,
            ),
            showToast = true,
        )
        return true
    }

    private fun resolveDeepLinkLoadUrl(deepLinkUrl: String?): String {
        if (deepLinkUrl.isNullOrBlank()) {
            Log.i(TAG, "deeplink: empty → default entry")
            return defaultUrl
        }
        if (UrlUtils.isApkDownloadUrl(deepLinkUrl) ||
            DeepLinkRouter.resolve(deepLinkUrl).type == DeepLinkRouter.DestinationType.APK_DOWNLOAD
        ) {
            Log.i(TAG, "deeplink: apk download → default entry")
            return defaultUrl
        }
        val route = DeepLinkRouter.resolve(deepLinkUrl)
        if (route.requiresAuth && !DeepLinkPendingStore.hasPartnerSessionCookie(this)) {
            DeepLinkPendingStore.save(this, route.targetUrl)
            val login = DeepLinkRouter.loginUrlFor(route.targetUrl)
            Log.i(TAG, "deeplink: auth required → login then ${route.targetUrl}")
            return login
        }
        return route.targetUrl
    }

    /** پس از ورود موفق، لینک معلق را باز کن (یک‌بار). */
    private fun maybeResumePendingDeepLinkAfterAuth(currentUrl: String?) {
        val pending = DeepLinkPendingStore.peek(this) ?: return
        if (!DeepLinkPendingStore.hasPartnerSessionCookie(this)) return
        val cur = (currentUrl ?: "").lowercase()
        if (cur.contains("/express/partner/login")) return
        // اگر همین حالا روی مقصد معلق هستیم، فقط پاک کن
        if (cur.contains(pending.lowercase().removePrefix("https://vinor.ir").removePrefix("https://www.vinor.ir"))) {
            DeepLinkPendingStore.clear(this)
            return
        }
        val target = DeepLinkPendingStore.consume(this) ?: return
        Log.i(TAG, "deeplink: resume after login → $target")
        openPartnerUrl(target)
    }

    /** بارگذاری URL پنل — پس از ورود یا deep link. */
    fun openPartnerUrl(url: String, clearHistory: Boolean = false) {
        if (!isWebViewUsable()) return
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
        runCatching { webView.loadUrl(target, VinorUserAgent.webViewLoadHeaders(this)) }
        if (clearHistory) {
            webView.post { runCatching { webView.clearHistory() } }
        }
    }

    /** درخواست مجوز نوتیف — بعد از لاگین یا از تنظیمات */
    fun requestNotificationPermission() {
        notifyHelper?.requestPermissionIfNeeded()
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
            val uri = Uri.parse(url)
            val lower = url.lowercase()
            val intent = when {
                lower.startsWith("tel:") ->
                    android.content.Intent(android.content.Intent.ACTION_DIAL, uri)
                lower.startsWith("mailto:") ->
                    android.content.Intent(android.content.Intent.ACTION_SENDTO, uri)
                else ->
                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Unable to open external url: $url", e)
            false
        }
    }

    private fun isOnline(): Boolean = VinorNetworkStatus.isOnline(this)

    private fun isUiAlive(): Boolean = VinorSafe.isActivityAlive(this) && ::binding.isInitialized

    private fun isWebViewUsable(): Boolean {
        if (!webViewAlive || recoveringRenderer) return false
        if (!::webView.isInitialized) return false
        return VinorSafe.isWebViewUsable(webView)
    }

    private fun currentWebUrl(): String? =
        if (isWebViewUsable()) VinorSafe.webUrl(webView) else null

    private fun networkState(): VinorNetState = VinorNetworkStatus.current(this)

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
        if (!isWebViewUsable()) return
        try {
            // Interceptor owns cache-first. LOAD_CACHE_ONLY + empty intercept can kill Chromium.
            webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        } catch (t: Throwable) {
            Log.w("MainActivity", "applyWebCacheMode failed", t)
        }
    }

    private fun syncConnectivityToWeb() {
        if (!isWebViewUsable()) return
        VinorConnectivityBridge.syncToWeb(
            webView,
            networkState(),
            VinorWebOfflineSession.lastCachedAt(this),
        )
    }

    private fun enterDegradedOffline() {
        if (!isUiAlive()) return
        if (!isWebViewUsable()) {
            showOverlayAfterDeadRenderer()
            return
        }
        degradedOffline = true
        userHasActiveSession = true
        applyWebCacheMode()
        pendingInitialReveal = false
        cancelShellRevealFallback()
        cancelOfflineRevealTimeout()
        binding.offlineOverlay.root.visibility = View.GONE
        runCatching {
            webView.removeCallbacks(revealAfterLoadRunnable)
            webView.visibility = View.VISIBLE
        }
        val url = currentWebUrl()
        if (VinorWebOfflineSession.isPartnerBrowsableUrl(url)) {
            VinorWebOfflineSession.markSuccess(this, url)
        }
        hideLoader()
        dismissSystemSplash()
        if (::offlineUi.isInitialized) {
            offlineUi.reset()
            offlineUi.bindLastSaved(VinorWebOfflineSession.lastCachedAt(this))
        }
        syncConnectivityToWeb()
        maybeUnregisterSwForOffline()
    }

    private fun onConnectivityRestored() {
        if (!isOnline()) return
        VinorCacheLog.event("OFFLINE_EXIT")
        applyWebCacheMode()
        degradedOffline = false
        swUnregisteredForOffline = false
        syncConnectivityToWeb()
        VinorOfflineQueue.onNetworkRestored(this) { queue ->
            if (isWebViewUsable()) {
                VinorCacheBridge.dispatchSync(
                    webView,
                    queue,
                    VinorWebOfflineSession.lastCachedAt(this),
                )
            }
        }
        VinorApiCacheStore.warmup(this)
        if (!isWebViewUsable()) return
        if (binding.offlineOverlay.root.visibility == View.VISIBLE || launchedWithOfflineFallback) {
            launchedWithOfflineFallback = false
            loadEntryUrl(forceReload = true)
        }
    }

    private fun onConnectivityLost() {
        VinorCacheLog.event("OFFLINE_ENTER")
        degradedOffline = true
        applyWebCacheMode()
        if (isWebViewUsable()) {
            try {
                webView.stopLoading()
            } catch (t: Throwable) {
                Log.w("MainActivity", "stopLoading failed", t)
            }
            val url = currentWebUrl()
            if (VinorWebOfflineSession.isPartnerBrowsableUrl(url)) {
                cacheCurrentPageSnapshot(url)
            }
        }
        if (canKeepCurrentWebContent() && currentPageIsKeepable()) {
            enterDegradedOffline()
            return
        }
        val last = VinorWebOfflineSession.lastPartnerUrl(this) ?: defaultUrl
        if (VinorOfflinePageStore.hasAny(this) || VinorWebOfflineSession.isContentRevealed(this)) {
            beginOfflineLoad(last)
            return
        }
        showOfflineOverlayOnly()
    }

    private fun dispatchNetworkChange() {
        val state = networkState()
        if (state == lastHandledNetState) return
        val prev = lastHandledNetState
        lastHandledNetState = state
        when (state) {
            VinorNetState.ONLINE, VinorNetState.LIMITED -> {
                if (prev == VinorNetState.OFFLINE) {
                    onConnectivityRestored()
                } else {
                    applyWebCacheMode()
                    syncConnectivityToWeb()
                }
            }
            VinorNetState.OFFLINE -> {
                if (prev != VinorNetState.OFFLINE) onConnectivityLost()
            }
        }
    }

    private fun recoverFromRendererGone() {
        if (recoveringRenderer || !isUiAlive()) return
        rendererCrashStreak += 1
        recoveringRenderer = true
        runOnUiThread {
            try {
                if (::webView.isInitialized) {
                    runCatching {
                        webView.removeCallbacks(revealAfterLoadRunnable)
                        webView.removeCallbacks(shellRevealFallbackRunnable)
                        webView.removeCallbacks(offlineRevealTimeoutRunnable)
                    }
                }
                runCatching { speechBridge?.destroy() }
                webViewAlive = false
                WebViewProvider.destroyCached()
                if (rendererCrashStreak > 2) {
                    recoveringRenderer = false
                    showOverlayAfterDeadRenderer()
                    return@runOnUiThread
                }
                if (isUiAlive()) {
                    recreate()
                } else {
                    recoveringRenderer = false
                }
            } catch (t: Throwable) {
                recoveringRenderer = false
                Log.e(TAG, "renderer recover failed", t)
            }
        }
    }

    private fun currentPageIsKeepable(): Boolean {
        if (!isWebViewUsable()) return false
        val url = currentWebUrl()
        return VinorWebOfflineSession.isPartnerBrowsableUrl(url) &&
            !VinorWebViewErrorShield.looksLikeChromeErrorPage(url)
    }

    private fun showOverlayAfterDeadRenderer() {
        if (!isUiAlive()) return
        dismissSystemSplash()
        pendingInitialReveal = false
        cancelShellRevealFallback()
        cancelOfflineRevealTimeout()
        hideLoader()
        val overlay = binding.offlineOverlay.root
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        if (::offlineUi.isInitialized) {
            offlineUi.reset()
            offlineUi.bindLastSaved(VinorWebOfflineSession.lastCachedAt(this))
        }
    }

    private fun showOfflineOverlayOnly() {
        if (currentPageIsKeepable()) {
            enterDegradedOffline()
            return
        }
        if (!offlineLoadInProgress &&
            (VinorOfflinePageStore.hasAny(this) || VinorWebOfflineSession.isContentRevealed(this))
        ) {
            val last = VinorWebOfflineSession.lastPartnerUrl(this) ?: defaultUrl
            beginOfflineLoad(last)
            return
        }
        presentOfflineOverlay()
    }

    private fun presentOfflineOverlay() {
        if (!isUiAlive()) return
        if (!isWebViewUsable()) {
            showOverlayAfterDeadRenderer()
            return
        }
        if (currentPageIsKeepable()) {
            enterDegradedOffline()
            return
        }
        dismissSystemSplash()
        pendingInitialReveal = false
        cancelShellRevealFallback()
        cancelOfflineRevealTimeout()
        runCatching {
            webView.removeCallbacks(revealAfterLoadRunnable)
            webView.visibility = View.GONE
        }
        hideLoader()
        val overlay = binding.offlineOverlay.root
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        if (::offlineUi.isInitialized) {
            offlineUi.reset()
            offlineUi.bindLastSaved(VinorWebOfflineSession.lastCachedAt(this))
        }
    }

    private fun showOfflineHard() {
        if (currentPageIsKeepable() && !isOnline()) {
            if (isWebViewUsable()) runCatching { webView.stopLoading() }
            enterDegradedOffline()
            return
        }
        if (!isOnline() &&
            (VinorOfflinePageStore.hasAny(this) || VinorWebOfflineSession.isContentRevealed(this))
        ) {
            restoreOfflineMainFrame(VinorWebOfflineSession.lastPartnerUrl(this))
            return
        }
        dismissSystemSplash()
        pendingInitialReveal = false
        cancelShellRevealFallback()
        degradedOffline = false
        if (isWebViewUsable()) {
            runCatching {
                webView.removeCallbacks(revealAfterLoadRunnable)
                webView.visibility = View.GONE
            }
            VinorWebViewErrorShield.clearToSilentPlaceholder(webView)
        }
        hideLoader()
        val overlay = binding.offlineOverlay.root
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        if (::offlineUi.isInitialized) offlineUi.reset()
    }

    private fun hideOffline() {
        if (!isUiAlive()) return
        binding.offlineOverlay.root.visibility = View.GONE
        if (isWebViewUsable()) {
            runCatching { webView.visibility = View.INVISIBLE }
        }
        if (::offlineUi.isInitialized) offlineUi.reset()
    }

    private fun finishSuccessfulPageLoadIfClean() {
        if (!isWebViewUsable()) return
        val curUrl = currentWebUrl()
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
                runCatching { webView.stopLoading() }
                restoreOfflineMainFrame(VinorWebOfflineSession.lastPartnerUrl(this))
                return
            }
            showOfflineHard()
            return
        }
        if (VinorWebViewErrorShield.isNeutralPlaceholderUrl(curUrl)) {
            if (!isOnline()) {
                if (shouldPreserveOfflineSession() || VinorWebOfflineSession.isContentRevealed(this)) {
                    runCatching { if (webView.canGoBack()) webView.goBack() }
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
        runCatching { webView.visibility = View.VISIBLE }
        userHasActiveSession = true
        startLoadRetries = 0
        rendererCrashStreak = 0
        VinorWebOfflineSession.markSuccess(this, curUrl)
        if (isOnline() && isExpressPartnerUrl(curUrl)) {
            VinorWebCacheManager.warmupPartnerCaches(webView)
        }
    }

    private fun setupBackPressHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isWebViewUsable() && runCatching { webView.canGoBack() }.getOrDefault(false)) {
                    runCatching { webView.goBack() }
                    return
                }
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    // برای سازگاری با کدهای قدیمی فراخوانی‌کننده از BaseWebViewFragment.
    fun setBottomNavVisible(visible: Boolean) = Unit

    override fun onStart() {
        super.onStart()
        lastHandledNetState = networkState()
        registerConnectivityLostCallback()
    }

    override fun onStop() {
        unregisterConnectivityLostCallback()
        // لغو callbackهای معوق تا پروسه پس‌زمینه بیدار نماند
        if (isWebViewUsable()) {
            runCatching {
                webView.removeCallbacks(revealAfterLoadRunnable)
                webView.removeCallbacks(shellRevealFallbackRunnable)
                webView.removeCallbacks(offlineRevealTimeoutRunnable)
            }
        }
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        if (isWebViewUsable()) {
            cacheCurrentPageSnapshot(currentWebUrl())
            runCatching { webView.onPause() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isWebViewUsable()) {
            runCatching { webView.onResume() }
            VinorLandDetailVolume.syncVolumeControlStream(this, currentWebUrl())
            VinorLandDetailVolume.kickGallery(webView)
            VinorAppUpdateChecker.check(this, webView)
            applyWebCacheMode()
            syncConnectivityToWeb()
            if (!isOnline()) {
                if (currentPageIsKeepable()) {
                    enterDegradedOffline()
                } else if (canKeepCurrentWebContent()) {
                    restoreOfflineMainFrame(currentWebUrl())
                }
            }
        }
        VinorFcmRegistrar.requestTokenAndRegister(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isWebViewUsable() && event.action == KeyEvent.ACTION_DOWN) {
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
        cacheBridge = null
        if (::webView.isInitialized && webViewAlive && !recoveringRenderer) {
            try {
                webView.removeCallbacks(revealAfterLoadRunnable)
                webView.removeCallbacks(shellRevealFallbackRunnable)
                webView.removeCallbacks(offlineRevealTimeoutRunnable)
            } catch (_: Throwable) {
            }
            WebViewProvider.detach(this)
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun registerConnectivityLostCallback() {
        if (connectivityCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                runOnUiThread { if (isUiAlive()) dispatchNetworkChange() }
            }

            override fun onAvailable(network: Network) {
                runOnUiThread { if (isUiAlive()) dispatchNetworkChange() }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                runOnUiThread { if (isUiAlive()) dispatchNetworkChange() }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, connectivityCallback!!)
        } catch (t: Throwable) {
            Log.w(TAG, "registerNetworkCallback failed", t)
            connectivityCallback = null
        }
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

    companion object {
        private const val TAG = "MainActivity"
        private var rendererCrashStreak = 0
    }
}
