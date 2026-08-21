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
            // هرگز بدنهٔ باینری خالی برنگردان — رندرر کرش می‌کند
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && request.isRedirect && !cacheFirst) {
            return null
        }
        if (isServiceWorkerUrl(url) || isServiceWorkerScriptRequest(request)) {
            return if (cacheFirst) VinorWebResponses.forCacheMiss("application/javascript") else null
        }

        // سند اصلی را intercept نکن: SW + HTML مصنوعی رندرر را می‌کشد.
        // snapshot با loadDataWithBaseURL از MainActivity تزریق می‌شود.
        if (request.isForMainFrame) return null

        if (VinorCachePolicy.isStaticAssetPath(url)) {
            if (!cacheFirst) {
                VinorOfflineAssetStore.cacheAsync(context, url)
                return null
            }
            VinorOfflineAssetStore.asResponse(context, url)?.let { return it }
            VinorCacheLog.event("CACHE_MISS", "asset")
            return VinorWebResponses.forCacheMiss(VinorCachePolicy.guessMime(url))
        }
        if (cacheFirst && VinorCachePolicy.isSafeGetApi(url)) {
            VinorApiCacheStore.asResponse(context, url, allowExpired = true)?.let { return it }
            VinorCacheLog.event("CACHE_MISS", "api")
            return VinorWebResponses.jsonOffline()
        }
        if (cacheFirst && VinorCachePolicy.isPartnerPath(url)) {
            VinorOfflinePageStore.asHtmlResponse(context, url)?.let { return it }
            return VinorWebResponses.silentHtml()
        }
        if (cacheFirst) {
            val path = VinorCachePolicy.pathOf(url)
            if (path.contains("/api/") || path.endsWith("/data")) {
                return VinorWebResponses.jsonOffline()
            }
            return VinorWebResponses.forCacheMiss(VinorCachePolicy.guessMime(url))
        }
        return null
    }

    /**
     * API زیر M: shouldInterceptRequest فقط URL می‌دهد و main-frame مشخص نیست.
     * HTML صفحه را intercept نکن — فقط asset/API تا رندرر نکشد.
     */
    fun interceptLegacyUrl(
        context: Context,
        url: String?,
        cacheFirst: Boolean,
    ): WebResourceResponse? {
        return try {
            val u = url?.trim().orEmpty()
            if (u.isEmpty() || !VinorCachePolicy.isVinorHttps(u)) return null
            if (isServiceWorkerUrl(u)) {
                return if (cacheFirst) VinorWebResponses.forCacheMiss("application/javascript") else null
            }
            if (VinorCachePolicy.isStaticAssetPath(u)) {
                if (!cacheFirst) {
                    VinorOfflineAssetStore.cacheAsync(context.applicationContext, u)
                    return null
                }
                VinorOfflineAssetStore.asResponse(context.applicationContext, u)?.let { return it }
                return VinorWebResponses.forCacheMiss(VinorCachePolicy.guessMime(u))
            }
            if (cacheFirst && VinorCachePolicy.isSafeGetApi(u)) {
                VinorApiCacheStore.asResponse(context.applicationContext, u, allowExpired = true)?.let { return it }
                return VinorWebResponses.jsonOffline()
            }
            null
        } catch (_: Throwable) {
            if (cacheFirst) VinorWebResponses.forCacheMiss("application/javascript") else null
        }
    }

    private fun isServiceWorkerUrl(url: String): Boolean {
        val p = VinorCachePolicy.pathOf(url).lowercase()
        return p.endsWith("-sw.js") ||
            p.endsWith("/sw.js") ||
            p.contains("service-worker") ||
            p.contains("serviceworker")
    }

    private fun isServiceWorkerScriptRequest(request: WebResourceRequest): Boolean {
        val headers = request.requestHeaders ?: return false
        val sw = headers["Service-Worker"] ?: headers["service-worker"] ?: return false
        return sw.equals("script", ignoreCase = true)
    }
}
