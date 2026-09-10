package ir.vinor.app

import android.content.Context
import android.webkit.CookieManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** HTTP JSON همکار با کوکی نشست — بدون WebView. */
object PartnerApi {
    const val BASE = VinorPartnerUrls.BASE
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun cookieHeader(): String = CookieManager.getInstance().getCookie(BASE) ?: ""

    fun hasSession(): Boolean {
        val cookie = cookieHeader()
        return cookie.contains("vinor_session") || cookie.contains("session=")
    }

    fun get(path: String, context: Context? = null): JSONObject {
        val req = requestBuilder(path, context).get().build()
        return execute(req)
    }

    fun getHtml(path: String, context: Context? = null): String {
        val url = if (path.startsWith("http")) path else "$BASE$path"
        val b = Request.Builder()
            .url(url)
            .addHeader("Accept", "text/html")
        val cookie = cookieHeader()
        if (cookie.isNotBlank()) b.addHeader("Cookie", cookie)
        if (context != null) {
            b.addHeader("User-Agent", VinorUserAgent.forHttpRequest(context))
            VinorUserAgent.webViewLoadHeaders(context).forEach { (k, v) -> b.addHeader(k, v) }
        }
        client.newCall(b.get().build()).execute().use { resp ->
            saveCookies(resp)
            if (!resp.isSuccessful) return ""
            return resp.body?.string().orEmpty()
        }
    }

    fun postJson(path: String, body: JSONObject, context: Context? = null): JSONObject {
        val req = requestBuilder(path, context)
            .post(body.toString().toRequestBody(jsonType))
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .build()
        return execute(req)
    }

    fun bytes(url: String, context: Context? = null): ByteArray? {
        val abs = if (url.startsWith("http")) url else if (url.startsWith("/")) "$BASE$url" else "$BASE/$url"
        val b = Request.Builder().url(abs).addHeader("Accept", "*/*")
        val cookie = cookieHeader()
        if (cookie.isNotBlank()) b.addHeader("Cookie", cookie)
        if (context != null) {
            b.addHeader("User-Agent", VinorUserAgent.forHttpRequest(context))
        }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()
        }
    }

    private fun requestBuilder(path: String, context: Context?): Request.Builder {
        val url = if (path.startsWith("http")) path else "$BASE$path"
        val b = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
        val cookie = cookieHeader()
        if (cookie.isNotBlank()) b.addHeader("Cookie", cookie)
        if (context != null) {
            b.addHeader("User-Agent", VinorUserAgent.forHttpRequest(context))
            VinorUserAgent.webViewLoadHeaders(context).forEach { (k, v) -> b.addHeader(k, v) }
        }
        return b
    }

    private fun execute(req: Request): JSONObject {
        client.newCall(req).execute().use { resp ->
            saveCookies(resp)
            val text = resp.body?.string().orEmpty()
            val obj = try {
                JSONObject(text.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject().put("ok", false).put("error", "پاسخ نامعتبر")
            }
            if (!obj.has("http_status")) obj.put("http_status", resp.code)
            return obj
        }
    }

    fun saveCookies(resp: Response) {
        val mgr = CookieManager.getInstance()
        mgr.setAcceptCookie(true)
        resp.headers("Set-Cookie").forEach { raw ->
            val part = raw.substringBefore(';')
            if (part.contains('=')) mgr.setCookie(BASE, part)
        }
        mgr.flush()
    }
}
