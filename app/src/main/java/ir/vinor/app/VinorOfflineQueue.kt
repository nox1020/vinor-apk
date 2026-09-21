package ir.vinor.app

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * صف عملیات آفلاین غیرحساس.
 * موفقیت جعلی برنمی‌گردد. پس از آنلاین شدن ابتدا نیتیو flush می‌شود
 * تا بدون وابستگی به JS و بدون ارسال دوباره، روی دیسک ack شود.
 */
object VinorOfflineQueue {

    private const val TAG = "VinorOfflineQueue"

    @Volatile
    private var lastSyncAt = 0L
    private val flushing = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vinor-offline-queue").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun file(context: Context): File {
        val d = File(
            context.filesDir,
            "${VinorCachePolicy.ROOT_DIR}/v${VinorCachePolicy.SCHEMA_VERSION}/queue",
        )
        if (!d.exists()) d.mkdirs()
        val fp = VinorCacheSession.scopeForRead(context)
        return File(d, "$fp.json")
    }

    fun enqueue(
        context: Context,
        type: String,
        url: String,
        method: String,
        body: String?,
    ): JSONObject {
        val now = System.currentTimeMillis()
        if (VinorCachePolicy.isSensitivePath(url) || VinorCachePolicy.isSensitivePath(type)) {
            VinorCacheLog.event("QUEUE_ADD", "rejected sensitive")
            return JSONObject()
                .put("ok", false)
                .put("queued", false)
                .put("offline", true)
                .put("message", "برای انجام این عملیات اینترنت لازم است")
        }
        if (!VinorCachePolicy.isQueueablePost(url)) {
            VinorCacheLog.event("QUEUE_ADD", "rejected not-queueable")
            return JSONObject()
                .put("ok", false)
                .put("queued", false)
                .put("offline", true)
                .put("message", "برای انجام این عملیات اینترنت لازم است")
        }
        val normalized = VinorCachePolicy.normalizeUrl(url)
        val payload = body ?: ""
        synchronized(this) {
            val items = load(context)
            if (items.length() >= VinorCachePolicy.MAX_QUEUE_ITEMS) {
                return JSONObject()
                    .put("ok", false)
                    .put("queued", false)
                    .put("offline", true)
                    .put("message", "صف آفلاین پر است؛ پس از اتصال دوباره تلاش کنید")
            }
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("status") != "pending") continue
                if (it.optString("url") == normalized && it.optString("payload") == payload) {
                    VinorCacheLog.event("QUEUE_ADD", "deduped")
                    return JSONObject()
                        .put("ok", false)
                        .put("queued", true)
                        .put("offline", true)
                        .put("duplicate", true)
                        .put("message", "پس از اتصال اینترنت ارسال می‌شود")
                }
            }
            val item = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", type)
                .put("url", normalized)
                .put("method", method.ifBlank { "POST" })
                .put("payload", payload)
                .put("createdAt", now)
                .put("retryCount", 0)
                .put("nextRetryAt", now)
                .put("status", "pending")
                .put("lastError", "")
            items.put(item)
            persist(context, items)
        }
        VinorCacheLog.event("QUEUE_ADD", "type=$type")
        return JSONObject()
            .put("ok", false)
            .put("queued", true)
            .put("offline", true)
            .put("message", "پس از اتصال اینترنت ارسال می‌شود")
    }

    fun snapshotJson(context: Context): JSONArray {
        prune(context)
        return load(context)
    }

    fun ack(context: Context, id: String) {
        synchronized(this) {
            val items = load(context)
            val next = JSONArray()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("id") != id) next.put(it)
            }
            persist(context, next)
        }
        VinorCacheLog.event("SYNC_SUCCESS", "queue-ack")
    }

    fun fail(context: Context, id: String, error: String) {
        synchronized(this) {
            val items = load(context)
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("id") != id) continue
                val retries = it.optInt("retryCount", 0) + 1
                it.put("retryCount", retries)
                it.put("lastError", error.take(80))
                it.put("nextRetryAt", System.currentTimeMillis() + VinorCachePolicy.queueBackoffMs(retries))
                it.put("status", if (retries >= VinorCachePolicy.QUEUE_MAX_RETRY) "failed" else "pending")
            }
            persist(context, items)
        }
        VinorCacheLog.event("QUEUE_RETRY", "err")
    }

    /**
     * Flush نیتیو سپس باقی‌مانده را به وب می‌دهد تا UI به‌روز شود.
     * اگر کوکی جلسه نباشد، همان صف برای replay در WebView می‌ماند.
     */
    fun flushWhenOnline(context: Context, after: (JSONArray) -> Unit) {
        if (!VinorNetworkStatus.isOnline(context)) {
            after(JSONArray())
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < 2_000L) {
            after(runCatching { snapshotJson(context) }.getOrDefault(JSONArray()))
            return
        }
        if (!flushing.compareAndSet(false, true)) {
            after(runCatching { snapshotJson(context) }.getOrDefault(JSONArray()))
            return
        }
        lastSyncAt = now
        val app = context.applicationContext
        VinorSafe.onMain {
            val cookie = VinorSafe.cookieOrEmpty(VinorPartnerUrls.BASE)
            io.execute {
                try {
                    prune(app)
                    if (cookie.length >= 12) {
                        flushOnce(app, cookie)
                    }
                    val remaining = snapshotJson(app)
                    if (remaining.length() > 0) {
                        VinorCacheLog.event("SYNC_START", "n=${remaining.length()}")
                    }
                    VinorSafe.onMain { after(remaining) }
                } catch (t: Throwable) {
                    Log.w(TAG, "flush failed", t)
                    VinorSafe.onMain { after(JSONArray()) }
                } finally {
                    flushing.set(false)
                }
            }
        }
    }

    fun onNetworkRestored(context: Context, dispatch: (JSONArray) -> Unit) {
        flushWhenOnline(context, dispatch)
    }

    fun clearUser(context: Context) {
        synchronized(this) {
            VinorCacheIo.deleteQuiet(file(context))
        }
    }

    private fun flushOnce(context: Context, cookie: String) {
        val pending = synchronized(this) {
            val now = System.currentTimeMillis()
            val items = load(context)
            buildList {
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    if (it.optString("status") != "pending") continue
                    if (it.optLong("nextRetryAt", 0L) > now) continue
                    add(it)
                }
            }
        }
        for (item in pending) {
            val id = item.optString("id")
            val url = item.optString("url")
            if (id.isBlank() || url.isBlank()) continue
            if (!VinorCachePolicy.isQueueablePost(url)) {
                fail(context, id, "not-queueable")
                continue
            }
            val method = item.optString("method", "POST").ifBlank { "POST" }
            val payload = item.optString("payload")
            val outcome = postOnce(url, method, payload, cookie)
            when (outcome) {
                PostOutcome.OK -> ack(context, id)
                PostOutcome.UNAUTHORIZED -> {
                    VinorCacheLog.event("SYNC_STOP", "401")
                    return
                }
                PostOutcome.RETRY -> fail(context, id, "network")
                PostOutcome.DROP -> fail(context, id, "http")
            }
        }
    }

    private enum class PostOutcome { OK, RETRY, DROP, UNAUTHORIZED }

    private fun postOnce(url: String, method: String, payload: String, cookie: String): PostOutcome {
        return try {
            val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(url)
                .method(method.uppercase(), if (method.equals("GET", true)) null else body)
                .addHeader("Cookie", cookie)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("User-Agent", "VinorApp/OfflineQueue")
                .build()
            http.newCall(req).execute().use { resp ->
                when (resp.code) {
                    in 200..299, 409 -> PostOutcome.OK
                    401, 403 -> PostOutcome.UNAUTHORIZED
                    408, 429, in 500..599 -> PostOutcome.RETRY
                    else -> PostOutcome.DROP
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "post failed: ${t.javaClass.simpleName}")
            PostOutcome.RETRY
        }
    }

    private fun prune(context: Context) {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val items = load(context)
            val next = JSONArray()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val age = now - it.optLong("createdAt", 0L)
                if (age > VinorCachePolicy.QUEUE_TTL_MS) continue
                if (it.optString("status") == "failed") continue
                next.put(it)
            }
            persist(context, next)
        }
    }

    private fun load(context: Context): JSONArray {
        val raw = VinorCacheIo.readText(file(context)) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun persist(context: Context, items: JSONArray) {
        VinorCacheIo.writeAtomicText(file(context), items.toString())
    }
}
