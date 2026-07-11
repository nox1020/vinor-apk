package ir.vinor.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import ir.vinor.app.databinding.FragmentWebviewBinding

/**
 * Base Fragment برای تمام تب‌های WebView
 * شامل: Loader, Offline handling, Back navigation
 */
abstract class BaseWebViewFragment : Fragment() {

    protected lateinit var binding: FragmentWebviewBinding
    private lateinit var offlineUi: VinorOfflineUi
    protected lateinit var webView: WebView
    abstract val targetUrl: String
    abstract val fragmentTag: String // برای لاگ
    
    // URL داینامیک - می‌تواند از منوی API تنظیم شود
    var dynamicUrl: String? = null
    
    // Public getters for MainActivity access
    fun getCurrentUrl(): String? {
        return if (::webView.isInitialized) webView.url else null
    }

    private var canGoBack = false
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    /** اگر onPageFinished زودتر از onReceivedError برود، بدون این پرچم لودر برداشته می‌شود و خطای Chromium دیده می‌شود. */
    private var mainFrameLoadFailed = false
    private var webViewFileChooser: VinorWebViewFileChooser? = null

    private val revealAfterLoadDelayMs = 120L
    private val revealAfterLoadRunnable = Runnable { finishSuccessfulPageLoadIfClean() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentWebviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webViewFileChooser = VinorWebViewFileChooser(this).also { it.register(this) }
        setupWebView()
        setupRetryButton()
        loadUrl()
    }

    private fun setupRetryButton() {
        offlineUi = VinorOfflineUi(binding.offlineOverlay.root)
        offlineUi.bindRetry {
            if (::webView.isInitialized) webView.removeCallbacks(revealAfterLoadRunnable)
            if (!isOnline()) {
                binding.offlineOverlay.root.postDelayed({ offlineUi.setChecking(false) }, 480L)
                showOfflineOverlayOnly()
                return@bindRetry
            }
            retryLoad()
        }
    }

    private fun setupWebView() {
        webView = binding.webView

        // تنظیمات امنیتی و عملکردی WebView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = false
        // setAppCacheEnabled deprecated و حذف شده - از cacheMode استفاده می‌کنیم
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        VinorWebViewFileChooser.configureWebViewSettings(settings)
        val appVersion = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
        settings.userAgentString = settings.userAgentString + " VinorApp/Android" +
            if (appVersion.isNotEmpty()) "/$appVersion" else ""
        
        // پشتیبانی از تم تاریک در WebView
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            settings.forceDark = WebSettings.FORCE_DARK_ON
        }

        // WebViewClient برای مدیریت navigation و errors
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                if (VinorWebViewErrorShield.looksLikeChromeErrorPage(url)) {
                    showOffline()
                    return true
                }

                // تماس / ایمیل / پیامک — مستقیم با Intent سیستم (نه داخل WebView)
                if (openExternalAppUrl(url)) {
                    return true
                }

                // امکان محدود کردن تب به یک URL (مثلاً فقط صفحه وینور)
                shouldOverrideUrlLoadingForFragment(url)?.let { override ->
                    if (override) {
                        try {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        } catch (e: Exception) {
                            Log.e(fragmentTag, "Error opening link: $url", e)
                        }
                        return true
                    }
                    return false
                }

                // اگر لینک دانلود APK است، با مرورگر/مدیر دانلود سیستم باز شود
                try {
                    if (url.lowercase().endsWith(".apk")) {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        startActivity(intent)
                        return true
                    }
                } catch (e: Exception) {
                    Log.e(fragmentTag, "Error opening APK link: $url", e)
                }

                // در همهٔ موارد (به‌جز APK)، لینک‌ها داخل همین WebView لود شوند
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (VinorWebViewErrorShield.shouldInterceptChromeErrorMainFrame(request)) {
                    return VinorWebViewErrorShield.silentHtmlResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION")
            @Deprecated("نسخه قدیمی API برای دستگاه‌های قدیمی‌تر از N")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
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
                    showOffline()
                    return
                }
                if (VinorWebViewErrorShield.isNeutralPlaceholderUrl(url)) return

                mainFrameLoadFailed = false
                canGoBack = view?.canGoBack() == true
                Log.d(fragmentTag, "Page started: $url")

                /* اگر بلافاصله hideOffline شود، WebView صفحهٔ خطای سیستمی (متن انگلیسی offline) را نشان می‌دهد */
                if (!isOnline()) {
                    showOffline()
                    return
                }
                hideOffline()
                showLoader()

                val urlStr = url ?: ""
                if (urlStr.contains("/express/partner/dashboard") ||
                    urlStr.contains("/express/partner/training") ||
                    urlStr.contains("/express/partner/login") ||
                    urlStr.contains("/express/partner/verify")) {
                    hideFooterMenu()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(fragmentTag, "Page finished (defer reveal): $url")
                if (view == null || !::webView.isInitialized) return
                webView.removeCallbacks(revealAfterLoadRunnable)
                webView.postDelayed(revealAfterLoadRunnable, revealAfterLoadDelayMs)
            }

            @SuppressLint("NewApi")
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    mainFrameLoadFailed = true
                    Log.w(fragmentTag, "Main frame load error: ${error?.errorCode} ${error?.description}")
                    VinorWebViewErrorShield.clearToSilentPlaceholder(view)
                    showOffline()
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
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    return
                }
                Log.w(fragmentTag, "Main frame load error (legacy): $errorCode $description")
                mainFrameLoadFailed = true
                VinorWebViewErrorShield.clearToSilentPlaceholder(view)
                showOffline()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return
                val code = errorResponse?.statusCode ?: return
                if (code == 404) {
                    Log.w(fragmentTag, "404 error for: ${request.url}")
                    return
                }
                if (code == 408 || code == 429 || code in 500..599) {
                    Log.w(fragmentTag, "HTTP $code — showing offline overlay")
                    mainFrameLoadFailed = true
                    VinorWebViewErrorShield.clearToSilentPlaceholder(view)
                    showOffline()
                }
            }
        }

        val fileChooser = webViewFileChooser
        webView.setDownloadListener { url, _, contentDisposition, _, _ ->
            val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, null)
            VinorDownloadBridge.enqueueDownload(requireContext(), url, filename, showToast = true)
        }
        webView.webChromeClient = fileChooser?.createChromeClient { newProgress ->
            binding.progressBar.progress = newProgress
            binding.progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
        } ?: WebChromeClient()
    }

    /**
     * آدرس نهایی برای بارگذاری. در فرزندان (مثل Dashboard) می‌توان همیشه فقط targetUrl را برگرداند.
     */
    protected open fun getUrlToLoad(): String = dynamicUrl ?: targetUrl

    private fun loadUrl() {
        if (!isOnline()) {
            showOfflineOverlayOnly()
            return
        }
        hideOffline()
        val urlToLoad = getUrlToLoad()
        Log.d(fragmentTag, "Loading URL: $urlToLoad")
        if (::webView.isInitialized) {
            webView.loadUrl(urlToLoad)
        }
    }
    
    /**
     * امکان کنترل بارگذاری لینک در همین WebView یا باز کردن در مرورگر.
     * @return true = در مرورگر باز شود، false = در WebView لود شود، null = رفتار پیش‌فرض
     */
    protected open fun shouldOverrideUrlLoadingForFragment(url: String): Boolean? = null

    /** tel / mailto / sms را با اپ سیستم باز می‌کند. */
    protected fun openExternalAppUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("tel:") &&
            !lower.startsWith("mailto:") &&
            !lower.startsWith("sms:") &&
            !lower.startsWith("smsto:")
        ) {
            return false
        }
        return try {
            val uri = android.net.Uri.parse(url)
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
            Log.e(fragmentTag, "Unable to open external app url: $url", e)
            false
        }
    }

    /** برای استفاده در فرزندان: آیا WebView مقداردهی شده است. */
    protected fun isWebViewInitialized(): Boolean = ::webView.isInitialized

    /**
     * بارگذاری مجدد با URL جدید (برای تغییر داینامیک منو)
     * open است تا بتوان در Fragmentهای فرزند override کرد
     */
    open fun reloadWithUrl(url: String) {
        dynamicUrl = url
        if (::webView.isInitialized) {
            if (!isOnline()) {
                showOfflineOverlayOnly()
                return
            }
            hideOffline()
            Log.d(fragmentTag, "Reloading with new URL: $url")
            webView.loadUrl(url)
        }
    }

    /**
     * بررسی اتصال به اینترنت
     */
    protected fun isOnline(): Boolean {
        val connectivityManager =
            requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * نمایش Loader/Skeleton
     */
    private fun showLoader() {
        binding.loaderContainer.visibility = View.VISIBLE
        binding.skeletonView.root.visibility = View.VISIBLE
    }

    /**
     * مخفی کردن Loader
     */
    private fun hideLoader() {
        binding.loaderContainer.visibility = View.GONE
        binding.skeletonView.root.visibility = View.GONE
    }

    /**
     * فقط اورلی «تلاش مجدد» و مخفی‌کردن WebView؛ بدون loadData روی WebView.
     * تا با هر بار Retry در حالت قطع نت، زنجیرهٔ onPageFinished دوباره اورلی را به‌هم نزند.
     */
    private fun showOfflineOverlayOnly() {
        if (::webView.isInitialized) {
            webView.removeCallbacks(revealAfterLoadRunnable)
            binding.webView.visibility = View.GONE
        }
        hideLoader()
        binding.progressBar.visibility = View.GONE
        val overlay = binding.offlineOverlay.root
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        if (::offlineUi.isInitialized) offlineUi.reset()
    }

    /**
     * نمایش صفحه Offline + پاک‌سازی محتوای خطادار WebView
     */
    private fun showOffline() {
        if (::webView.isInitialized) {
            webView.removeCallbacks(revealAfterLoadRunnable)
            binding.webView.visibility = View.GONE
            VinorWebViewErrorShield.clearToSilentPlaceholder(webView)
        }
        hideLoader()
        binding.progressBar.visibility = View.GONE
        val overlay = binding.offlineOverlay.root
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        if (::offlineUi.isInitialized) offlineUi.reset()
    }

    /**
     * مخفی کردن صفحه Offline
     */
    private fun hideOffline() {
        binding.offlineOverlay.root.visibility = View.GONE
        /* تا زمان موفقیت واقعی لود INVISIBLE تا صفحهٔ خطای Chromium («offline») حتی یک فریم دیده نشود */
        binding.webView.visibility = View.INVISIBLE
        if (::offlineUi.isInitialized) offlineUi.reset()
    }

    private fun finishSuccessfulPageLoadIfClean() {
        if (view == null || !::webView.isInitialized) return
        if (mainFrameLoadFailed) {
            mainFrameLoadFailed = false
            showOfflineOverlayOnly()
            return
        }
        if (!isOnline()) {
            showOfflineOverlayOnly()
            return
        }
        val curUrl = webView.url
        if (VinorWebViewErrorShield.looksLikeChromeErrorPage(curUrl)) {
            showOffline()
            return
        }
        if (VinorWebViewErrorShield.isNeutralPlaceholderUrl(curUrl)) {
            if (!isOnline()) showOfflineOverlayOnly()
            return
        }
        hideLoader()
        binding.webView.visibility = View.VISIBLE
        canGoBack = webView.canGoBack()
        Log.d(fragmentTag, "Page revealed OK: $curUrl")

        val urlStr = curUrl ?: ""
        val isFileDetail =
            urlStr.contains("/express/partner/lands/") ||
                urlStr.contains("/express/partner/land/")
        (activity as? MainActivity)?.setBottomNavVisible(!isFileDetail)

        if (urlStr.contains("/express/partner/dashboard") ||
            urlStr.contains("/express/partner/training") ||
            urlStr.contains("/express/partner/login") ||
            urlStr.contains("/express/partner/verify")) {
            hideFooterMenu()
        }
        enableDarkMode()
    }

    /**
     * Retry برای بارگذاری مجدد
     */
    fun retryLoad() {
        loadUrl()
    }

    /**
     * Reload صفحه فعلی WebView
     * برای استفاده در Fragmentهای فرزند
     */
    protected fun reloadWebView() {
        if (::webView.isInitialized) {
            webView.reload()
        }
    }

    /**
     * مخفی کردن منوی فوتر سایت با JavaScript
     * منوی فوتر با ID bottomNavMenu و کلاس fixed inset-x-0 bottom-0 است
     * در صفحات جزئیات فایل (همکاران و عمومی) فوتر باید حتماً مخفی باشد
     */
    private fun hideFooterMenu() {
        if (!::webView.isInitialized) return
        
        // JavaScript برای مخفی کردن منوی فوتر
        val hideFooterScript = """
            (function() {
                // Selectorهای مختلف برای منوی فوتر
                var selectors = [
                    '#bottomNavMenu',                    // منوی اصلی فوتر
                    'nav#bottomNavMenu',                // nav با ID bottomNavMenu
                    'nav[id="bottomNavMenu"]',          // nav با ID bottomNavMenu (exact)
                    '.fixed.inset-x-0.bottom-0',        // منوی فوتر و نوار پایین صفحه جزئیات
                    '.fixed.bottom-0.inset-x-0',        // نوار پایین (ترتیب دیگر کلاس)
                    'nav.fixed.inset-x-0.bottom-0',     // nav با کلاس‌های منوی فوتر
                    'div.fixed.inset-x-0.bottom-0',     // div نوار پایین (صفحه جزئیات فایل)
                    'div.fixed.bottom-0.inset-x-0',
                    '[id*="bottomNav"]',                // هر element با ID شامل bottomNav
                    '[class*="bottom-nav"]',            // هر element با کلاس شامل bottom-nav
                    '[class*="bottomNav"]',              // هر element با کلاس شامل bottomNav
                    'footer nav',
                    'footer .menu',
                    'footer .footer-menu',
                    '.footer-menu',
                    '.bottom-nav',
                    '#footer-menu',
                    'nav.footer-menu',
                    '.app-footer',
                    'footer .bottom-navigation',
                    '[class*="footer"] [class*="menu"]',
                    '[class*="footer"] nav',
                    'footer [class*="nav"]',
                    // Selectorهای اضافی برای صفحات جزئیات فایل
                    'nav[class*="fixed"][class*="bottom"]',
                    '[data-tour="bottom-nav"]',          // منوی فوتر با data-tour
                    'nav[data-tour="bottom-nav"]'       // nav با data-tour
                ];
                
                function hideElements() {
                    var hiddenCount = 0;
                    selectors.forEach(function(selector) {
                        try {
                            var elements = document.querySelectorAll(selector);
                            elements.forEach(function(el) {
                                if (el) {
                                    // چک کردن که آیا element منوی فوتر یا نوار پایین صفحه است (از جمله صفحه جزئیات فایل)
                                    var isFooterNav = el.id === 'bottomNavMenu' || 
                                                     el.classList.contains('bottom-nav') ||
                                                     el.classList.contains('bottomNavMenu') ||
                                                     (el.tagName === 'NAV' && el.classList.contains('fixed') && el.classList.contains('bottom-0')) ||
                                                     (el.classList.contains('fixed') && el.classList.contains('bottom-0')) ||
                                                     el.getAttribute('data-tour') === 'bottom-nav';
                                    
                                    if (isFooterNav || selector.includes('bottomNav') || selector.includes('bottom-nav') || selector.includes('fixed')) {
                                        el.style.display = 'none';
                                        el.style.visibility = 'hidden';
                                        el.style.height = '0';
                                        el.style.overflow = 'hidden';
                                        el.style.margin = '0';
                                        el.style.padding = '0';
                                        el.style.opacity = '0';
                                        el.style.pointerEvents = 'none';
                                        hiddenCount++;
                                    }
                                }
                            });
                        } catch(e) {
                            console.error('Error hiding footer:', e);
                        }
                    });
                    if (hiddenCount > 0) {
                        console.log('Hidden ' + hiddenCount + ' footer elements');
                    }
                }
                
                // اجرای فوری
                hideElements();
                
                // اجرای مجدد بعد از لود کامل DOM
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', hideElements);
                }
                
                // اجرای مجدد بعد از لود کامل صفحه
                window.addEventListener('load', hideElements);
                
                // اجرای مجدد بعد از تغییرات DOM (برای SPA)
                setTimeout(hideElements, 100);
                setTimeout(hideElements, 300);
                setTimeout(hideElements, 500);
                setTimeout(hideElements, 1000);
                setTimeout(hideElements, 2000);
                
                // Observer برای تغییرات DOM (برای SPA)
                if (window.MutationObserver) {
                    var observer = new MutationObserver(function(mutations) {
                        var shouldHide = false;
                        mutations.forEach(function(mutation) {
                            if (mutation.addedNodes && mutation.addedNodes.length > 0) {
                                for (var i = 0; i < mutation.addedNodes.length; i++) {
                                    var node = mutation.addedNodes[i];
                                    if (node.nodeType === 1) { // Element node
                                        if (node.id === 'bottomNavMenu' || 
                                            node.classList.contains('bottom-nav') ||
                                            node.querySelector && (node.querySelector('#bottomNavMenu') || node.querySelector('.bottom-nav'))) {
                                            shouldHide = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        });
                        if (shouldHide) {
                            hideElements();
                        }
                    });
                    observer.observe(document.body, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['class', 'id', 'style']
                    });
                }
            })();
        """.trimIndent()
        
        try {
            webView.evaluateJavascript(hideFooterScript, null)
            Log.d(fragmentTag, "Footer menu hidden")
        } catch (e: Exception) {
            Log.e(fragmentTag, "Error hiding footer menu", e)
        }
    }

    /**
     * فعال کردن تم تاریک در صفحه وب با JavaScript
     */
    private fun enableDarkMode() {
        if (!::webView.isInitialized) return
        
        // JavaScript برای فعال کردن تم تاریک
        val darkModeScript = """
            (function() {
                // اضافه کردن کلاس dark به html element
                if (document.documentElement) {
                    document.documentElement.classList.add('dark');
                }
                
                // اضافه کردن attribute data-theme
                if (document.documentElement) {
                    document.documentElement.setAttribute('data-theme', 'dark');
                }
                
                // اضافه کردن meta tag برای تم تاریک
                var metaTheme = document.querySelector('meta[name="color-scheme"]');
                if (!metaTheme) {
                    metaTheme = document.createElement('meta');
                    metaTheme.name = 'color-scheme';
                    metaTheme.content = 'dark';
                    document.head.appendChild(metaTheme);
                } else {
                    metaTheme.content = 'dark';
                }
                
                // اضافه کردن style برای اطمینان از تم تاریک
                var styleId = 'vinor-dark-mode-style';
                if (!document.getElementById(styleId)) {
                    var style = document.createElement('style');
                    style.id = styleId;
                    style.textContent = `
                        html { color-scheme: dark !important; }
                        body { background-color: #111827 !important; color: #F9FAFB !important; }
                    `;
                    document.head.appendChild(style);
                }
            })();
        """.trimIndent()
        
        try {
            webView.evaluateJavascript(darkModeScript, null)
            Log.d(fragmentTag, "Dark mode enabled")
        } catch (e: Exception) {
            Log.e(fragmentTag, "Error enabling dark mode", e)
        }
    }

    /**
     * بررسی اینکه آیا WebView می‌تواند به عقب برگردد
     */
    fun canGoBackInWebView(): Boolean {
        return if (::webView.isInitialized) {
            webView.canGoBack()
        } else {
            false
        }
    }

    /**
     * برگشت در WebView history
     */
    fun goBackInWebView(): Boolean {
        return if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            true
        } else {
            false
        }
    }

    /**
     * بررسی اینکه آیا در ریشه تب هستیم (URL اصلی)
     */
    fun isAtRoot(): Boolean {
        if (!::webView.isInitialized) return true
        val currentUrl = webView.url ?: ""
        if (currentUrl.isEmpty()) return true
        
        // بررسی اینکه آیا URL فعلی همان targetUrl است یا به آن ختم می‌شود
        val cleanTarget = targetUrl.replace("https://", "").replace("http://", "")
        val cleanCurrent = currentUrl.replace("https://", "").replace("http://", "")
        
        return currentUrl == targetUrl || 
               currentUrl.endsWith(targetUrl) ||
               cleanCurrent == cleanTarget ||
               cleanCurrent.startsWith(cleanTarget + "/")
    }

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
        if (::webView.isInitialized) {
            webView.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerConnectivityLostCallback() {
        if (connectivityCallback != null) return
        val cm =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                view?.post {
                    if (!isAdded) return@post
                    if (!isOnline()) showOfflineOverlayOnly()
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
        val ctx = context ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        connectivityCallback = null
    }

    override fun onDestroyView() {
        if (::webView.isInitialized) {
            webView.removeCallbacks(revealAfterLoadRunnable)
            webView.destroy()
        }
        super.onDestroyView()
    }
}

