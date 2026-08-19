package ir.vinor.app

import android.content.Context
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Intercept فقط برای GET استاتیک/API نمایشی و POST آفلاین.
 * POST معاملاتی را جعل موفقیت نمی‌کند.
 */
object VinorWebResourceInterceptor {

    fun intercept(
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
        if (!VinorCachePolicy.isVinorHttps(url) && !url.startsWith("/")) return null

        val method = request.method?.uppercase().orEmpty().ifBlank { "GET" }
        if (method == "GET") {
            return interceptGet(context, request, url, cacheFirst)
        }
        if (cacheFirst && method == "POST") {
            return interceptOfflinePost(context, url)
        }
        return null
    }

    private fun interceptGet(
        context: Context,
        request: WebResourceRequest,
        url: String,
        cacheFirst: Boolean,
    ): WebResourceResponse? {
        if (cacheFirst && request.isForMainFrame && VinorCachePolicy.isPartnerPath(url)) {
            val page = VinorOfflinePageStore.asHtmlResponse(context, url)
            if (page != null) return page
        }
        if (VinorCachePolicy.isStaticAssetPath(url)) {
            if (!cacheFirst) {
                VinorOfflineAssetStore.cacheAsync(context, url)
                return null
            }
            val asset = VinorOfflineAssetStore.asResponse(context, url)
            if (asset != null) return asset
            VinorCacheLog.event("CACHE_MISS", "asset")
            return null
        }
        if (VinorCachePolicy.isSafeGetApi(url)) {
            if (!cacheFirst) {
                return null
            }
            val api = VinorApiCacheStore.asResponse(context, url)
            if (api != null) return api
            return json(
                JSONObject()
                    .put("success", false)
                    .put("offline", true)
                    .put("message", "این بخش بدون اینترنت در دسترس نیست"),
                503,
            )
        }
        return null
    }

    private fun interceptOfflinePost(
        context: Context,
        url: String,
    ): WebResourceResponse? {
        if (!VinorCachePolicy.isPartnerPath(url) &&
            !url.contains("/express/partner/api/") &&
            !url.contains("/api/express/")
        ) {
            return null
        }
        if (VinorCachePolicy.isSensitivePath(url)) {
            VinorCacheLog.event("OFFLINE_ENTER", "blocked-sensitive-post")
            return json(
                JSONObject()
                    .put("ok", false)
                    .put("success", false)
                    .put("offline", true)
                    .put("message", "برای انجام این عملیات اینترنت لازم است"),
                503,
            )
        }
        if (VinorCachePolicy.isQueueablePost(url)) {
            VinorCacheLog.event("QUEUE_ADD", "intercept-no-body")
            return json(
                JSONObject()
                    .put("ok", false)
                    .put("success", false)
                    .put("offline", true)
                    .put("queued", false)
                    .put("canQueue", true)
                    .put("message", "بدون اینترنت ذخیره نشد؛ پس از اتصال دوباره تلاش کنید"),
                503,
            )
        }
        return json(
            JSONObject()
                .put("ok", false)
                .put("success", false)
                .put("offline", true)
                .put("message", "برای انجام این عملیات اینترنت لازم است"),
            503,
        )
    }

    private fun json(obj: JSONObject, status: Int): WebResourceResponse {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        val reason = if (status in 200..299) "OK" else "Unavailable"
        return if (Build.VERSION.SDK_INT >= 21) {
            WebResourceResponse(
                "application/json",
                "utf-8",
                status,
                reason,
                mapOf("Content-Type" to "application/json; charset=utf-8", "X-Vinor-Cache" to "OFFLINE"),
                ByteArrayInputStream(bytes),
            )
        } else {
            WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(bytes))
        }
    }
}
