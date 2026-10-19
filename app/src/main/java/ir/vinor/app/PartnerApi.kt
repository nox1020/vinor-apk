package ir.vinor.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
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
import java.util.Locale
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
    private val expiryLock = Any()
    @Volatile private var appCtx: Context? = null
    @Volatile private var loggedIn: Boolean = false
    @Volatile private var cookieSnapshot: String = ""
    @Volatile private var expiryQueued = false
    @Volatile private var sessionExpiredListener: SessionExpiredListener? = null

    fun interface SessionExpiredListener {
        fun onSessionExpired()
    }

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

    /** ورود قطعی (user_phone)، نه کوکی موقت OTP. مهمان هم vinor_session می‌گیرد. */
    fun hasSession(): Boolean = loggedIn && hasVinorCookie()

    fun setSessionExpiredListener(listener: SessionExpiredListener?) {
        sessionExpiredListener = listener
    }

    /** نشست قبلی باطل شده (۴۰۱ یا پاسخ مهمان در حالی که کلاینت لاگین می‌دانست). */
    fun isSessionExpired(obj: JSONObject): Boolean = obj.optBoolean("session_expired")

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

    /**
     * OkHttp redirects an expired partner session to the HTML login page. Treat that
     * final response as 401 before callers try to parse the HTML as a successful JSON response.
     */
    fun responsePayload(
        text: String,
        status: Int,
        requestPath: String,
        finalPath: String,
    ): JSONObject {
        val requestedLogin = requestPath.startsWith("/express/partner/login")
        val redirectedToLogin = !requestedLogin && finalPath.startsWith("/express/partner/login")
        if (status == 401 || redirectedToLogin) {
            return JSONObject()
                .put("ok", false)
                .put("success", false)
                .put("error", "unauthorized")
                .put("http_status", 401)
        }
        val obj = try {
            JSONObject(text.ifBlank { "{}" })
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "پاسخ نامعتبر")
        }
        if (!obj.has("http_status")) obj.put("http_status", status)
        return obj
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

    fun forget(path: String, context: Context?) {
        if (context == null) return
        VinorApiCacheStore.forget(context.applicationContext, absoluteUrl(path))
    }

    fun remember(path: String, obj: JSONObject, context: Context?) {
        if (context == null || obj.optBoolean("from_cache")) return
        if (obj.optBoolean("non_json") || obj.optBoolean("session_expired")) return
        val code = obj.optInt("http_status", 200)
        if (code == 401 || code == 403) return
        if (code != 0 && code !in 200..299) return
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        if (!isCacheableJson(bytes)) return
        VinorApiCacheStore.put(
            context.applicationContext,
            absoluteUrl(path),
            bytes,
            "application/json",
        )
    }

    /**
     * آنلاین: اول نتیجهٔ شبکه (و وضعیت ورود) مشخص می‌شود، بعد bind.
     * کش خصوصی قبل از آن رسم نمی‌شود. آفلاین یا خطای شبکه/۵xx همان کش را نشان می‌دهد.
     */
    suspend fun loadDisplay(
        path: String,
        context: Context,
        bind: (JSONObject) -> Unit,
    ): JSONObject {
        if (VinorNetworkStatus.useCacheFirst(context)) {
            val stale = withContext(Dispatchers.IO) { peekJson(path, context) }
            if (stale != null) bind(stale)
            return stale ?: offlineMiss()
        }
        val fresh = withContext(Dispatchers.IO) { get(path, context) }
        if (isSessionExpired(fresh) || fresh.optInt("http_status") == 401 || fresh.optInt("http_status") == 403) {
            return fresh
        }
        bind(fresh)
        return fresh
    }

    fun get(path: String, context: Context? = null): JSONObject {
        val ctx = context ?: appCtx
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) {
            return peekJson(path, ctx) ?: offlineMiss()
        }
        return try {
            val req = requestBuilder(path, context).get().build()
            val obj = execute(req)
            val code = obj.optInt("http_status")
            if (obj.optBoolean("session_expired") || code == 401) {
                return authRequiredResult(obj, 401)
            }
            if (code == 403) {
                return authRequiredResult(obj, 403)
            }
            if (obj.optBoolean("non_json")) {
                return obj
            }
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

    data class PageBody(val code: Int, val url: String, val body: String)

    /** HTML بدون ذخیره. برای صفحه‌هایی که شماره و نام مشتری دارند. */
    fun getPage(path: String, context: Context? = null): PageBody {
        val url = if (path.startsWith("http")) path else "$BASE$path"
        val ctx = context ?: appCtx
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) {
            return PageBody(0, url, "")
        }
        return try {
            val b = Request.Builder().url(url).addHeader("Accept", "text/html")
            val cookie = cookieHeader()
            if (cookie.isNotBlank()) b.addHeader("Cookie", cookie)
            if (context != null) {
                b.addHeader("User-Agent", VinorUserAgent.forHttpRequest(context))
                VinorUserAgent.webViewLoadHeaders(context).forEach { (k, v) -> b.addHeader(k, v) }
            }
            client.newCall(b.get().build()).execute().use { resp ->
                saveCookies(resp)
                PageBody(resp.code, resp.request.url.toString(), resp.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            PageBody(0, url, "")
        }
    }

    /**
     * POST صفحه‌ای که CSRF و Referer خودش را می‌خواهد. در صف آفلاین نمی‌رود.
     */
    fun postPage(
        path: String,
        body: JSONObject,
        csrf: String,
        refererPath: String,
        context: Context? = null,
    ): JSONObject {
        val ctx = context ?: appCtx
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) {
            return JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("http_status", 0)
                .put("message", "برای انجام این عملیات اینترنت لازم است.")
        }
        return try {
            var token = csrf.trim()
            var resp = executePage(path, body, token, refererPath, context)
            if (PartnerCustomers.isCsrfExpired(resp)) {
                val next = resp.optString("csrf_token").trim()
                if (next.isNotBlank() && next != token) {
                    token = next
                    resp = executePage(path, body, token, refererPath, context)
                }
            }
            resp
        } catch (_: Exception) {
            JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("http_status", 0)
                .put("message", "برای انجام این عملیات اینترنت لازم است.")
        }
    }

    private fun executePage(
        path: String,
        body: JSONObject,
        csrf: String,
        refererPath: String,
        context: Context?,
    ): JSONObject {
        val builder = requestBuilder(path, context)
            .post(body.toString().toRequestBody(jsonType))
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Origin", PartnerCustomers.ORIGIN)
            .addHeader("Referer", PartnerCustomers.ORIGIN + refererPath)
        if (csrf.isNotBlank()) builder.addHeader("X-CSRFToken", csrf)
        return execute(builder.build())
    }

    /**
     * POST فرم وب (بازدید ملک). در صف آفلاین نمی‌رود و موفقیت جعلی نمی‌سازد.
     */
    fun postForm(
        path: String,
        fields: Map<String, String>,
        csrf: String,
        refererPath: String,
        context: Context? = null,
    ): JSONObject {
        val ctx = context ?: appCtx
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) {
            return JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("http_status", 0)
                .put("message", "برای انجام این عملیات اینترنت لازم است.")
        }
        return try {
            var token = csrf.trim()
            var resp = executeForm(path, fields, token, refererPath, context)
            if (PartnerCustomers.isCsrfExpired(resp)) {
                val next = resp.optString("csrf_token").trim()
                if (next.isNotBlank() && next != token) {
                    token = next
                    resp = executeForm(path, fields, token, refererPath, context)
                }
            }
            resp
        } catch (_: Exception) {
            JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("http_status", 0)
                .put("message", "برای انجام این عملیات اینترنت لازم است.")
        }
    }

    private fun executeForm(
        path: String,
        fields: Map<String, String>,
        csrf: String,
        refererPath: String,
        context: Context?,
    ): JSONObject {
        val form = FormBody.Builder()
        if (csrf.isNotBlank()) form.add("csrf_token", csrf)
        for ((key, value) in fields) {
            if (key == "csrf_token") continue
            form.add(key, value)
        }
        val referer = if (refererPath.startsWith("http")) refererPath else PartnerCustomers.ORIGIN + refererPath
        val builder = requestBuilder(path, context)
            .post(form.build())
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Origin", PartnerCustomers.ORIGIN)
            .addHeader("Referer", referer)
        if (csrf.isNotBlank()) builder.addHeader("X-CSRFToken", csrf)
        return execute(builder.build())
    }

    /**
     * POST فرم صندوق پیگیری. ریدایرکت دنبال نمی‌شود تا کوکی فلش قبل از خواندن HTML ذخیره شود.
     */
    fun postInboxForm(
        path: String,
        fields: Map<String, String>,
        csrf: String,
        refererPath: String,
        context: Context? = null,
    ): JSONObject {
        val ctx = context ?: appCtx
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) {
            return JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("http_status", 0)
                .put("message", "برای انجام این عملیات اینترنت لازم است.")
        }
        return try {
            var token = csrf.trim()
            var resp = executeInbox(path, fields, token, refererPath, context)
            if (PartnerCustomers.isCsrfExpired(resp)) {
                val next = resp.optString("csrf_token").trim()
                if (next.isNotBlank() && next != token) {
                    token = next
                    resp = executeInbox(path, fields, token, refererPath, context)
                }
            }
            resp
        } catch (_: Exception) {
            JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("http_status", 0)
                .put("message", "برای انجام این عملیات اینترنت لازم است.")
        }
    }

    private val redirectless by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun executeInbox(
        path: String,
        fields: Map<String, String>,
        csrf: String,
        refererPath: String,
        context: Context?,
    ): JSONObject {
        val form = FormBody.Builder()
        if (csrf.isNotBlank()) form.add("csrf_token", csrf)
        for ((key, value) in fields) {
            if (key == "csrf_token") continue
            form.add(key, value)
        }
        val referer = if (refererPath.startsWith("http")) refererPath else PartnerCustomers.ORIGIN + refererPath
        val builder = requestBuilder(path, context)
            .post(form.build())
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Origin", PartnerCustomers.ORIGIN)
            .addHeader("Referer", referer)
        if (csrf.isNotBlank()) builder.addHeader("X-CSRFToken", csrf)
        redirectless.newCall(builder.build()).execute().use { resp ->
            saveCookies(resp)
            val status = resp.code
            val postedPath = resp.request.url.encodedPath
            val location = resp.header("Location").orEmpty().trim()
            if (status in 300..399 && location.isNotBlank()) {
                val next = resp.request.url.resolve(location)
                if (next == null || !vinorHost(next.host)) {
                    return JSONObject().put("ok", false).put("http_status", status).put("error", "پاسخ نامعتبر")
                }
                if (next.encodedPath.startsWith("/express/partner/login")) {
                    return inboxUnauthorized()
                }
                client.newCall(htmlGet(next.toString(), context)).execute().use { page ->
                    saveCookies(page)
                    val finalPath = page.request.url.encodedPath
                    if (finalPath.startsWith("/express/partner/login") || page.code == 401) {
                        return inboxUnauthorized()
                    }
                    return FollowRequests.inboxResponse(
                        page.code,
                        page.body?.string().orEmpty(),
                        finalPath,
                        redirected = true,
                    )
                }
            }
            if (status == 401 || postedPath.startsWith("/express/partner/login")) return inboxUnauthorized()
            return FollowRequests.inboxResponse(
                status,
                resp.body?.string().orEmpty(),
                postedPath,
                redirected = false,
            )
        }
    }

    private fun htmlGet(url: String, context: Context?): Request {
        val builder = Request.Builder().url(url).addHeader("Accept", "text/html")
        val cookie = cookieHeader()
        if (cookie.isNotBlank()) builder.addHeader("Cookie", cookie)
        if (context != null) {
            builder.addHeader("User-Agent", VinorUserAgent.forHttpRequest(context))
            VinorUserAgent.webViewLoadHeaders(context).forEach { (key, value) ->
                builder.addHeader(key, value)
            }
        }
        return builder.get().build()
    }

    private fun vinorHost(host: String): Boolean {
        val name = host.trim().lowercase(Locale.US)
        return name == "vinor.ir" || name.endsWith(".vinor.ir")
    }

    private fun inboxUnauthorized(): JSONObject {
        val obj = JSONObject()
            .put("ok", false)
            .put("success", false)
            .put("error", "unauthorized")
            .put("http_status", 401)
        if (hasSession()) {
            obj.put("session_expired", true)
            expireSession(notifyUi = true)
        }
        return obj
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

    /**
     * POST مالی کیف پول. در صف آفلاین نمی‌رود و موفقیت جعلی نمی‌سازد.
     * سرور این مسیرها را فقط با X-CSRFToken به‌اضافه Referer صفحهٔ کمیسیون می‌پذیرد.
     */
    fun postFinance(path: String, body: JSONObject, csrf: String, context: Context? = null): JSONObject {
        val ctx = context ?: appCtx
        if (ctx != null && VinorNetworkStatus.useCacheFirst(ctx)) {
            return JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("http_status", 0)
                .put("message", "برای انجام این عملیات اینترنت لازم است.")
        }
        return try {
            var token = csrf.trim()
            var resp = executeFinance(path, body, token, context)
            if (WalletWithdraw.isCsrfExpired(resp)) {
                val next = WalletWithdraw.csrfOf(resp)
                if (next.isNotBlank() && next != token) {
                    token = next
                    resp = executeFinance(path, body, token, context)
                }
            }
            resp
        } catch (_: Exception) {
            JSONObject()
                .put("ok", false)
                .put("offline", true)
                .put("http_status", 0)
                .put("message", "برای انجام این عملیات اینترنت لازم است.")
        }
    }

    private fun executeFinance(path: String, body: JSONObject, csrf: String, context: Context?): JSONObject {
        val builder = requestBuilder(path, context)
            .post(body.toString().toRequestBody(jsonType))
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Origin", WalletWithdraw.ORIGIN)
            .addHeader("Referer", WalletWithdraw.ORIGIN + WalletWithdraw.REFERER_PATH)
        if (csrf.isNotBlank()) builder.addHeader("X-CSRFToken", csrf)
        return execute(builder.build())
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
        ProfileFragment.clearRemembered()
        synchronized(cookieLock) { cookieSnapshot = "" }
        val wipeUserCache = {
            appCtx?.let { ctx ->
                VinorApiCacheStore.clearUser(ctx)
                VinorAvatarCache.clear(ctx)
                VinorCacheSession.fingerprint(ctx)
            }
        }
        val apply: () -> Unit = {
            runCatching {
                val mgr = CookieManager.getInstance()
                mgr.removeAllCookies {
                    runCatching {
                        mgr.flush()
                        synchronized(cookieLock) {
                            cookieSnapshot = runCatching { mgr.getCookie(BASE) ?: "" }.getOrDefault("")
                        }
                    }
                    wipeUserCache()
                    done()
                }
            }.onFailure {
                wipeUserCache()
                done()
            }
            Unit
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

    private fun offlineMiss(): JSONObject = JSONObject()
        .put("ok", false)
        .put("offline", true)
        .put("from_cache", false)
        .put("error", "آفلاین است و نسخه‌ای ذخیره نشده")

    private fun authRequiredResult(server: JSONObject, code: Int): JSONObject {
        val err = server.optString("error")
        val error = when {
            code == 403 -> err.ifBlank { "forbidden" }
            err == "unauthorized" || err == "auth_required" -> err
            else -> "auth_required"
        }
        return JSONObject()
            .put("ok", false)
            .put("success", false)
            .put("error", error)
            .put("http_status", code)
            .put("from_cache", false)
            .put("auth_required", code == 401 || server.optBoolean("session_expired"))
            .put("session_expired", server.optBoolean("session_expired"))
    }

    /** HTML، بدنهٔ خالی، و پورتال اسیر JSON نیستند و نباید کش شوند. */
    internal fun isCacheableJson(body: ByteArray): Boolean {
        if (body.isEmpty()) return false
        val text = runCatching { body.toString(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
        if (text.isEmpty() || text[0] != '{') return false
        if (text.startsWith("<!") || text.startsWith("<html", ignoreCase = true)) return false
        return runCatching { JSONObject(text) }.isSuccess
    }

    private fun requestPath(url: String): String {
        val path = VinorCachePolicy.pathOf(url).lowercase(Locale.US)
        return if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path
    }

    private fun isCredentialPath(url: String): Boolean {
        val path = requestPath(url)
        return path.endsWith("/login-request") ||
            path.endsWith("/api/verify") ||
            path.endsWith("/otp/resend") ||
            path.endsWith("/api/logout")
    }

    /**
     * فقط وقتی کلاینت خودش را لاگین می‌داند.
     * پروفایل ۲۰۰ با me_phone خالی، یا home/data با is_logged_in:false.
     */
    private fun isSilentLogout(url: String, obj: JSONObject): Boolean {
        if (obj.optBoolean("non_json")) return false
        if (obj.optInt("http_status") !in 200..299) return false
        val path = requestPath(url)
        if (path == "/express/partner/profile/data") {
            return obj.optString("me_phone").isBlank()
        }
        if (path == "/express/partner/home/data") {
            return obj.has("is_logged_in") && !obj.optBoolean("is_logged_in")
        }
        return false
    }

    private fun evictPrivateCache() {
        val ctx = appCtx ?: return
        runCatching { VinorApiCacheStore.clearUser(ctx) }
    }

    private fun expireSession(notifyUi: Boolean) {
        setLoggedIn(false)
        evictPrivateCache()
        runCatching { clearCookies { } }
        if (!notifyUi) return
        val post = synchronized(expiryLock) {
            if (expiryQueued) {
                false
            } else {
                expiryQueued = true
                true
            }
        }
        if (!post) return
        VinorSafe.onMain {
            try {
                sessionExpiredListener?.onSessionExpired()
            } finally {
                synchronized(expiryLock) { expiryQueued = false }
            }
        }
    }

    private fun execute(req: Request, http: OkHttpClient = client): JSONObject {
        http.newCall(req).execute().use { resp ->
            val hadSession = hasSession()
            saveCookies(resp)
            val text = resp.body?.string().orEmpty()
            val obj = responsePayload(
                text = text,
                status = resp.code,
                requestPath = req.url.encodedPath,
                finalPath = resp.request.url.encodedPath,
            )
            if (obj.optString("error") == "پاسخ نامعتبر") obj.put("non_json", true)
            val url = req.url.toString()
            val code = obj.optInt("http_status")
            if (code == 403) evictPrivateCache()
            val hard401 = code == 401 && !isCredentialPath(url)
            val silent = hadSession && isSilentLogout(url, obj)
            if (hard401 || silent) {
                if (hadSession || silent) obj.put("session_expired", true)
                expireSession(notifyUi = obj.optBoolean("session_expired"))
            } else if (!obj.optBoolean("non_json")) {
                if (code == 401 || obj.optString("error") == "unauthorized") setLoggedIn(false)
                ingestAuth(obj)
            }
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
