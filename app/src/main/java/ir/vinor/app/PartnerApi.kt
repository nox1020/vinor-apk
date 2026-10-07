package ir.vinor.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** HTTP JSON همکار با کوکی نشست — بدون WebView. */
object PartnerApi {
    const val BASE = VinorPartnerUrls.BASE
    private const val PREFS = "vinor_auth"
    private const val KEY_LOGGED_IN = "logged_in"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val uploadClient = client.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
    private val cookieLock = Any()
    @Volatile private var appCtx: Context? = null
    @Volatile private var loggedIn: Boolean = false
    @Volatile private var cookieSnapshot: String = ""

    fun attach(context: Context) {
        val app = context.applicationContext
        appCtx = app
        loggedIn = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOGGED_IN, false)
        refreshCookieSnapshot()
        if (loggedIn && !hasVinorCookie()) setLoggedIn(false)
    }

    fun cookieHeader(): String {
        if (VinorSafe.isMainThread()) {
            val live = runCatching {
                CookieManager.getInstance().getCookie(BASE) ?: ""
            }.getOrDefault("")
            synchronized(cookieLock) { cookieSnapshot = live }
            return live
        }
        synchronized(cookieLock) {
            if (cookieSnapshot.isNotBlank()) return cookieSnapshot
        }
        var live = ""
        val latch = CountDownLatch(1)
        VinorSafe.onMain {
            live = runCatching {
                CookieManager.getInstance().getCookie(BASE) ?: ""
            }.getOrDefault("")
            synchronized(cookieLock) { cookieSnapshot = live }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return live
    }

    fun hasVinorCookie(): Boolean {
        val cookie = cookieHeader()
        return cookie.contains("vinor_session")
    }

    /** ورود قطعی (user_phone)، نه کوکی موقت OTP. */
    fun hasSession(): Boolean = loggedIn && hasVinorCookie()

    fun markLoggedIn() {
        setLoggedIn(true)
        val ctx = appCtx
        if (ctx != null) {
            VinorSafe.onMain {
                VinorCacheSession.fingerprint(ctx)
                VinorApiCacheStore.warmup(ctx)
            }
        }
    }

    fun markLoggedOut() = setLoggedIn(false)

    fun ingestAuth(obj: JSONObject) {
        when {
            obj.has("is_logged_in") ->
                setLoggedIn(obj.optBoolean("is_logged_in") || obj.optString("me_phone").isNotBlank())
            obj.has("me_phone") && obj.has("me_name") ->
                setLoggedIn(obj.optString("me_phone").isNotBlank())
        }
    }

    fun asciiDigits(raw: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        val ar = "٠١٢٣٤٥٦٧٨٩"
        val out = StringBuilder()
        for (ch in raw) {
            val i = fa.indexOf(ch)
            if (i >= 0) {
                out.append(i)
                continue
            }
            val j = ar.indexOf(ch)
            if (j >= 0) {
                out.append(j)
                continue
            }
            if (ch in '0'..'9') out.append(ch)
        }
        return out.toString()
    }

    fun normalizePhone(input: String): String {
        val digits = asciiDigits(input)
        return when {
            digits.startsWith("0098") && digits.length >= 14 -> "0" + digits.drop(4).take(10)
            digits.startsWith("98") && digits.length >= 12 -> "0" + digits.drop(2).take(10)
            digits.length == 11 && digits.startsWith("0") -> digits
            digits.length == 10 && digits.startsWith("9") -> "0$digits"
            else -> digits.take(11)
        }
    }

    fun absoluteUrl(path: String): String =
        if (path.startsWith("http")) path else "$BASE$path"

    fun peekJson(path: String, context: Context?): JSONObject? {
        if (context == null) return null
        val obj = VinorApiCacheStore.readJson(context.applicationContext, absoluteUrl(path), allowExpired = true)
            ?: return null
        obj.put("from_cache", true)
        return obj
    }

    fun isFresh(path: String, context: Context?): Boolean {
        if (context == null) return false
        return VinorApiCacheStore.isFresh(context.applicationContext, absoluteUrl(path))
    }

    fun remember(path: String, obj: JSONObject, context: Context?) {
        if (context == null || obj.optBoolean("from_cache")) return
        val code = obj.optInt("http_status", 200)
        if (code != 0 && code !in 200..299) return
        VinorApiCacheStore.put(
            context.applicationContext,
            absoluteUrl(path),
            obj.toString().toByteArray(Charsets.UTF_8),
            "application/json",
        )
    }

    /** اول کش را روی Main می‌دهد؛ اگر تازه باشد شبکه را نمی‌زند. */
    suspend fun loadDisplay(
        path: String,
        context: Context,
        bind: (JSONObject) -> Unit,
    ): JSONObject {
        val stale = withContext(Dispatchers.IO) { peekJson(path, context) }
        if (stale != null) bind(stale)
        if (stale != null && withContext(Dispatchers.IO) { isFresh(path, context) }) {
            return stale
        }
        val fresh = withContext(Dispatchers.IO) { get(path, context) }
        if (!fresh.optBoolean("from_cache") || stale == null) bind(fresh)
        return fresh
    }

    fun get(path: String, context: Context? = null): JSONObject {
        val ctx = context ?: appCtx
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) {
            return peekJson(path, ctx) ?: JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("from_cache", false)
                .put("error", "آفلاین است و نسخه‌ای ذخیره نشده")
        }
        return try {
            val req = requestBuilder(path, context).get().build()
            val obj = execute(req)
            val code = obj.optInt("http_status")
            if (code in 200..299) {
                remember(path, obj, context)
                obj
            } else if (!ChatSessionGate.cacheMayHideAuthFailure(path, code)) {
                obj
            } else {
                peekJson(path, context) ?: obj
            }
        } catch (e: Exception) {
            peekJson(path, context) ?: throw e
        }
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
        return try {
            client.newCall(b.get().build()).execute().use { resp ->
                saveCookies(resp)
                if (!resp.isSuccessful) {
                    return context?.let { VinorOfflinePageStore.read(it.applicationContext, url) }.orEmpty()
                }
                val html = resp.body?.string().orEmpty()
                if (html.isNotBlank() && context != null) {
                    VinorOfflinePageStore.save(context.applicationContext, url, html)
                }
                html
            }
        } catch (_: Exception) {
            context?.let { VinorOfflinePageStore.read(it.applicationContext, url) }.orEmpty()
        }
    }

    fun patchJson(path: String, body: JSONObject, context: Context? = null): JSONObject {
        val ctx = context ?: appCtx
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) {
            return JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("http_status", 0)
                .put("message", "برای انجام این عملیات اینترنت لازم است")
        }
        val req = requestBuilder(path, context)
            .patch(body.toString().toRequestBody(jsonType))
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .build()
        return execute(req)
    }

    fun postMultipart(path: String, body: MultipartBody, context: Context? = null): JSONObject {
        val ctx = context ?: appCtx
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) {
            return JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("http_status", 0)
                .put("message", "برای ارسال مدارک اینترنت لازم است")
        }
        val req = requestBuilder(path, context).post(body).build()
        return execute(req, uploadClient)
    }

    fun postJson(path: String, body: JSONObject, context: Context? = null): JSONObject {
        val ctx = context ?: appCtx
        val url = absoluteUrl(path)
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) {
            return enqueueOrOffline(ctx, url, body)
        }
        return try {
            val req = requestBuilder(path, context)
                .post(body.toString().toRequestBody(jsonType))
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .build()
            execute(req)
        } catch (e: Exception) {
            if (ctx != null) enqueueOrOffline(ctx, url, body) else throw e
        }
    }

    private fun enqueueOrOffline(ctx: Context, url: String, body: JSONObject): JSONObject {
        if (VinorCachePolicy.isQueueablePost(url)) {
            return VinorOfflineQueue.enqueue(ctx, "post", url, "POST", body.toString())
        }
        return JSONObject()
            .put("ok", false)
            .put("queued", false)
            .put("offline", true)
            .put("http_status", 0)
            .put("error", "برای انجام این عملیات اینترنت لازم است")
    }

    fun postUpload(
        path: String,
        uri: Uri,
        kind: String,
        fileName: String,
        mime: String,
        context: Context,
    ): JSONObject {
        val resolver = context.contentResolver
        val type = mime.ifBlank { resolver.getType(uri) ?: "application/octet-stream" }
        val fileBody = object : RequestBody() {
            override fun contentType() = type.toMediaTypeOrNull()
            override fun contentLength(): Long {
                return resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            }
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input ->
                    sink.writeAll(input.source())
                } ?: error("خواندن فایل ناموفق بود.")
            }
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("kind", kind)
            .addFormDataPart("file", fileName.ifBlank { "file" }, fileBody)
            .build()
        val req = requestBuilder(path, context).post(body).build()
        return execute(req, uploadClient)
    }

    fun bytes(url: String, context: Context? = null): ByteArray? {
        val abs = if (url.startsWith("http")) url else if (url.startsWith("/")) "$BASE$url" else "$BASE/$url"
        val disk = context?.let { VinorImageDiskCache.read(it.applicationContext, abs) }
        if (disk != null) return disk
        val ctx = context ?: appCtx
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) return null
        return try {
            val b = Request.Builder().url(abs).addHeader("Accept", "*/*")
            val cookie = cookieHeader()
            if (cookie.isNotBlank()) b.addHeader("Cookie", cookie)
            if (context != null) {
                b.addHeader("User-Agent", VinorUserAgent.forHttpRequest(context))
            }
            client.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.bytes() ?: return null
                if (body.isNotEmpty() && context != null) {
                    VinorImageDiskCache.write(context.applicationContext, abs, body)
                }
                body
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveCookies(resp: Response) {
        val cookies = resp.headers("Set-Cookie")
        if (cookies.isEmpty()) return
        applyCookiesOnMain(cookies)
    }

    fun clearCookies(done: () -> Unit) {
        setLoggedIn(false)
        synchronized(cookieLock) { cookieSnapshot = "" }
        val apply = {
            val mgr = CookieManager.getInstance()
            mgr.removeAllCookies {
                mgr.flush()
                synchronized(cookieLock) {
                    cookieSnapshot = runCatching { mgr.getCookie(BASE) ?: "" }.getOrDefault("")
                }
                appCtx?.let { ctx ->
                    VinorApiCacheStore.clearUser(ctx)
                    VinorCacheSession.fingerprint(ctx)
                }
                done()
            }
        }
        if (VinorSafe.isMainThread()) apply() else VinorSafe.onMain(apply)
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

    private fun execute(req: Request, http: OkHttpClient = client): JSONObject {
        http.newCall(req).execute().use { resp ->
            saveCookies(resp)
            val text = resp.body?.string().orEmpty()
            val obj = try {
                JSONObject(text.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject().put("ok", false).put("error", "پاسخ نامعتبر")
            }
            if (!obj.has("http_status")) obj.put("http_status", resp.code)
            ingestAuth(obj)
            return obj
        }
    }

    private fun applyCookiesOnMain(cookies: List<String>) {
        val apply = {
            val mgr = CookieManager.getInstance()
            mgr.setAcceptCookie(true)
            cookies.forEach { raw ->
                if (raw.contains('=')) mgr.setCookie(BASE, raw)
            }
            mgr.flush()
            synchronized(cookieLock) {
                cookieSnapshot = runCatching { mgr.getCookie(BASE) ?: "" }.getOrDefault("")
            }
        }
        if (VinorSafe.isMainThread()) {
            apply()
            return
        }
        val latch = CountDownLatch(1)
        VinorSafe.onMain {
            try {
                apply()
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    private fun refreshCookieSnapshot() {
        if (!VinorSafe.isMainThread()) return
        val live = runCatching {
            CookieManager.getInstance().getCookie(BASE) ?: ""
        }.getOrDefault("")
        synchronized(cookieLock) { cookieSnapshot = live }
    }

    private fun setLoggedIn(value: Boolean) {
        loggedIn = value
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_LOGGED_IN, value)
            ?.apply()
    }
}
