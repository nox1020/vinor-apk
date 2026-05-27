package ir.vinor.app

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

object WebViewProvider {
    @Volatile
    private var cachedWebView: WebView? = null

    fun prewarm(context: Context) {
        if (cachedWebView != null) return
        synchronized(this) {
            if (cachedWebView == null) {
                cachedWebView = createWebView(context.applicationContext).also { warmupInstance(context, it) }
            }
        }
    }

    fun attach(activity: Activity, container: ViewGroup): WebView {
        val webView = obtain(activity)
        val ctxWrapper = webView.context as MutableContextWrapper
        ctxWrapper.baseContext = activity
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return webView
    }

    private fun obtain(context: Context): WebView {
        prewarm(context)
        return cachedWebView ?: createWebView(context.applicationContext).also { cachedWebView = it }
    }

    private fun createWebView(appContext: Context): WebView {
        val ctx = MutableContextWrapper(appContext)
        return WebView(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                allowContentAccess = true
                allowFileAccess = false
                textZoom = 100
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setSupportMultipleWindows(false)
                runCatching { javaClass.getMethod("setAppCacheEnabled", Boolean::class.java).invoke(this, true) }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
                WebSettingsCompat.setOffscreenPreRaster(settings, true)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
            }
        }
    }

    private fun warmupInstance(context: Context, webView: WebView) {
        // فقط راه‌اندازی موتور Chromium — بدون درخواست شبکه که با loadUrl داشبورد تداخل کند
        runCatching { webView.loadUrl("about:blank") }
    }
}


