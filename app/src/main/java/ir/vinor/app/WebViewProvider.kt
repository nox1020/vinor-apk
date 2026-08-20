package ir.vinor.app

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
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

    /**
     * Detach from Activity to avoid leaking Activity via MutableContextWrapper.
     * Keeps the WebView instance warm for next attach.
     */
    fun detach(activity: Activity) {
        val webView = cachedWebView ?: return
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
        } catch (_: Exception) {
        }
        val ctxWrapper = webView.context as? MutableContextWrapper ?: return
        try {
            ctxWrapper.baseContext = activity.applicationContext
        } catch (_: Exception) {
        }
    }

    fun peek(): WebView? = cachedWebView

    /** پس از مرگ رندرر Chromium، نمونهٔ مرده را دور بریز. */
    fun destroyCached() {
        synchronized(this) {
            val old = cachedWebView
            cachedWebView = null
            if (old == null) return
            try {
                (old.parent as? ViewGroup)?.removeView(old)
            } catch (_: Exception) {
            }
            try {
                old.stopLoading()
            } catch (_: Exception) {
            }
            try {
                old.destroy()
            } catch (_: Exception) {
            }
        }
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
                VinorWebViewSettings.apply(
                    this,
                    userAgentSuffix = ""
                )
                // Online-first; MainActivity flips to CACHE_ELSE_NETWORK when offline
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
                WebSettingsCompat.setOffscreenPreRaster(settings, true)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
            }
        }
    }

    private fun warmupInstance(context: Context, webView: WebView) {
        // فقط موتور Chromium را گرم کن — loadUrl صفحهٔ قبلی را پاک می‌کند
    }
}
