package ir.vinor.app

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/** تنظیمات مشترک WebView — گالری/ویدیو جزئیات فایل و بارگذاری تصاویر در APK */
object VinorWebViewSettings {

    fun apply(settings: WebSettings, userAgentSuffix: String) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = false
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.textZoom = 100
        settings.setSupportMultipleWindows(false)
        settings.allowContentAccess = true
        settings.allowFileAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        if (userAgentSuffix.isNotBlank() && !settings.userAgentString.contains("VinorApp/")) {
            settings.userAgentString = settings.userAgentString + userAgentSuffix
        }
    }

    fun configureCookies(webView: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // First-party session only — reduces cross-site tracking / bridge abuse surface
            setAcceptThirdPartyCookies(webView, false)
        }
    }
}
