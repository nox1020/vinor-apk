package ir.vinor.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import ir.vinor.app.databinding.ActivityMainBinding

/**
 * نسخه کاملا WebView:
 * - تمام ناوبری توسط وب‌سایت انجام می‌شود
 * - هیچ BottomNavigation/Fragment نیتیو استفاده نمی‌شود
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private var lastBackPressTime: Long = 0
    private val backPressThreshold = 2000L
    private val defaultUrl = "https://vinor.ir/express/partner/dashboard"

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView
        setupRetryButton()
        setupBackPressHandler()
        setupWebView()
        loadEntryUrl()
    }

    private fun setupRetryButton() {
        binding.retryButton.setOnClickListener { loadEntryUrl() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.userAgentString = settings.userAgentString + " VinorApp/WebViewOnly"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            settings.forceDark = WebSettings.FORCE_DARK_ON
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return when {
                    // لینک‌های داخلی vinor داخل WebView باز می‌مانند.
                    isVinorDomain(url) -> false
                    // دانلود APK و لینک‌های خارجی به مرورگر سیستم سپرده می‌شوند.
                    else -> openExternal(url)
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showLoader()
                hideOffline()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                hideLoader()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }
        }
    }

    private fun loadEntryUrl() {
        if (!isOnline()) {
            showOffline()
            return
        }
        hideOffline()
        webView.loadUrl(extractStartUrl(intent?.dataString))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        webView.loadUrl(extractStartUrl(intent.dataString))
    }

    private fun extractStartUrl(deepLinkUrl: String?): String {
        if (deepLinkUrl.isNullOrBlank()) return defaultUrl
        return if (isVinorDomain(deepLinkUrl)) deepLinkUrl else defaultUrl
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
        binding.skeletonView.visibility = View.VISIBLE
    }

    private fun hideLoader() {
        binding.loaderContainer.visibility = View.GONE
        binding.skeletonView.visibility = View.GONE
    }

    private fun showOffline() {
        binding.offlineContainer.visibility = View.VISIBLE
        webView.visibility = View.GONE
    }

    private fun hideOffline() {
        binding.offlineContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
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

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
