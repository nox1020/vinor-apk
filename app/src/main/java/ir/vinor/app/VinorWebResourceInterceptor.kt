package ir.vinor.app

import android.content.Context
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * Intercept فقط GET: صفحه/asset/API نمایشی از کش.
 * روی ترد Chromium اجرا می‌شود — بدون CookieManager، بدون POST، بدون mapOf غیرقابل‌تغییر.
 */
object VinorWebResourceInterceptor {

    fun intercept(
        context: Context,
        request: WebResourceRequest?,
        cacheFirst: Boolean,
    ): WebResourceResponse? {
        return try {
            interceptUnsafe(context.applicationContext, request, cacheFirst)
        } catch (t: Throwable) {
            VinorCacheLog.event("CACHE_MISS", "intercept-error")
            null
        }
    }

    private fun interceptUnsafe(
        context: Context,
        request: WebResourceRequest?,
        cacheFirst: Boolean,
    ): WebResourceResponse? {
        if (request == null) return null
        val url = request.url?.toString()?.trim().orEmpty()
        if (url.isEmpty()) return null
        if (VinorWebViewErrorShield.shouldInterceptChromeErrorMainFrame(request)) {
            return VinorWebViewErrorShield.silentHtmlResponse()
        }
        if (!VinorCachePolicy.isVinorHttps(url)) return null

        val method = request.method?.uppercase().orEmpty().ifBlank { "GET" }
        if (method != "GET") return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && request.isRedirect) return null
        if (isServiceWorkerUrl(url)) return null

        if (cacheFirst && request.isForMainFrame && VinorCachePolicy.isPartnerPath(url)) {
            VinorOfflinePageStore.asHtmlResponse(context, url)?.let { return it }
        }
        if (VinorCachePolicy.isStaticAssetPath(url)) {
            if (!cacheFirst) {
                VinorOfflineAssetStore.cacheAsync(context, url)
                return null
            }
            VinorOfflineAssetStore.asResponse(context, url)?.let { return it }
            VinorCacheLog.event("CACHE_MISS", "asset")
            return null
        }
        if (cacheFirst && VinorCachePolicy.isSafeGetApi(url)) {
            VinorApiCacheStore.asResponse(context, url)?.let { return it }
            VinorCacheLog.event("CACHE_MISS", "api")
            return null
        }
        return null
    }

    private fun isServiceWorkerUrl(url: String): Boolean {
        val p = VinorCachePolicy.pathOf(url).lowercase()
        return p.endsWith("-sw.js") ||
            p.endsWith("/sw.js") ||
            p.contains("service-worker") ||
            p.contains("serviceworker")
    }
}
