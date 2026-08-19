package ir.vinor.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * صف عملیات آفلاین غیرحساس.
 * موفقیت جعلی برنمی‌گردد؛ بعد از آنلاین شدن وب replay می‌کند.
 */
object VinorOfflineQueue {

    @Volatile
    private var lastSyncAt = 0L

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
        synchronized(this) {
            val items = load(context)
            if (items.length() >= VinorCachePolicy.MAX_QUEUE_ITEMS) {
                return JSONObject()
                    .put("ok", false)
                    .put("queued", false)
                    .put("offline", true)
                    .put("message", "صف آفلاین پر است؛ پس از اتصال دوباره تلاش کنید")
            }
            val item = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", type)
                .put("url", VinorCachePolicy.normalizeUrl(url))
                .put("method", method.ifBlank { "POST" })
                .put("payload", body ?: "")
                .put("createdAt", now)
                .put("retryCount", 0)
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
                it.put("status", if (retries >= VinorCachePolicy.QUEUE_MAX_RETRY) "failed" else "pending")
            }
            persist(context, items)
        }
        VinorCacheLog.event("QUEUE_RETRY", "err")
    }

    fun onNetworkRestored(context: Context, dispatch: (JSONArray) -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < 2_000L) return
        lastSyncAt = now
        prune(context)
        val items = snapshotJson(context)
        if (items.length() == 0) return
        VinorCacheLog.event("SYNC_START", "n=${items.length()}")
        dispatch(items)
    }

    fun clearUser(context: Context) {
        synchronized(this) {
            VinorCacheIo.deleteQuiet(file(context))
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
