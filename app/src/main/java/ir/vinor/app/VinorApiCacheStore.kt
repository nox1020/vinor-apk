package ir.vinor.app

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** کش GETهای نمایشی همکار — نه POST و نه مسیرهای مالی. */
object VinorApiCacheStore {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vinor-api-cache").apply { isDaemon = true }
    }

    private fun root(context: Context, forWrite: Boolean = false): File {
        val fp = if (forWrite) {
            VinorCacheSession.scopeForWrite(context)
        } else {
            VinorCacheSession.scopeForRead(context)
        }
        val d = File(
            context.filesDir,
            "${VinorCachePolicy.ROOT_DIR}/v${VinorCachePolicy.SCHEMA_VERSION}/api/$fp",
        )
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun key(url: String): String {
        val n = VinorCachePolicy.normalizeUrl(url)
        return MessageDigest.getInstance("SHA-256").digest(n.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun put(context: Context, url: String, body: ByteArray, contentType: String) {
        if (!VinorCachePolicy.isSafeGetApi(url)) return
        if (body.isEmpty() || body.size > VinorCachePolicy.MAX_API_BODY_BYTES) return
        io.execute {
            runCatching {
                val dir = root(context, forWrite = false)
                val id = key(url)
                val bin = File(dir, "$id.bin")
                val meta = File(dir, "$id.json")
                if (!VinorCacheIo.writeAtomic(bin, body)) return@runCatching
                val json = JSONObject()
                    .put("url", VinorCachePolicy.normalizeUrl(url))
                    .put("ct", contentType.ifBlank { "application/json" })
                    .put("createdAt", System.currentTimeMillis())
                    .put("lastAccessedAt", System.currentTimeMillis())
                    .put("size", body.size)
                    .put("session", VinorCacheSession.scopeForRead(context))
                VinorCacheIo.writeAtomicText(meta, json.toString())
                VinorCacheLog.event("CACHE_WRITE", "api size=${body.size}")
                trim(dir)
            }
        }
    }

    fun asResponse(context: Context, url: String?, allowExpired: Boolean = false): WebResourceResponse? {
        val raw = (url ?: "").trim()
        if (!VinorCachePolicy.isSafeGetApi(raw)) return null
        val dir = root(context)
        val id = key(raw)
        val bin = File(dir, "$id.bin")
        val meta = File(dir, "$id.json")
        val bytes = VinorCacheIo.readBytes(bin) ?: return missNoQuery(context, raw, allowExpired)
        val obj = VinorCacheIo.readJson(meta)
        val age = System.currentTimeMillis() - (obj?.optLong("createdAt") ?: 0L)
        if (age > VinorCachePolicy.API_KEEP_MS && !allowExpired) {
            VinorCacheLog.event("CACHE_DELETE", "api expired")
            VinorCacheIo.deleteQuiet(bin)
            VinorCacheIo.deleteQuiet(meta)
            return null
        }
        touch(meta)
        val ct = obj?.optString("ct").orEmpty().ifBlank { "application/json; charset=utf-8" }
        VinorCacheLog.event("CACHE_HIT", "api stale=${age > VinorCachePolicy.API_STALE_MS}")
        return jsonResponse(bytes, ct)
    }

    private fun missNoQuery(context: Context, url: String, allowExpired: Boolean): WebResourceResponse? {
        val noQuery = runCatching {
            val uri = URI(url)
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }.getOrNull() ?: return null
        if (noQuery == url) return null
        return asResponse(context, noQuery, allowExpired)
    }

    fun warmup(context: Context) {
        VinorCacheSession.fingerprint(context)
        val cookie = runCatching {
            CookieManager.getInstance().getCookie(VinorPartnerUrls.BASE)?.trim().orEmpty()
        }.getOrDefault("")
        if (cookie.length < 12) return
        val urls = listOf(
            "${VinorPartnerUrls.BASE}/express/partner/api/notifications",
            "${VinorPartnerUrls.BASE}/express/partner/api/notes",
            "${VinorPartnerUrls.BASE}/express/partner/api/public-lands",
            "${VinorPartnerUrls.BASE}/express/partner/profile/data",
            "${VinorPartnerUrls.BASE}/express/partner/routine/data",
            "${VinorPartnerUrls.BASE}/express/partner/commissions/data",
        )
        io.execute {
            urls.forEach { fetchAndStore(context, it, cookie) }
        }
    }

    private fun fetchAndStore(context: Context, url: String, cookie: String) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cookie", cookie)
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("User-Agent", VinorUserAgent.forHttpRequest(context))
            }
            val code = conn.responseCode
            val ct = conn.contentType ?: "application/json"
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            conn.disconnect()
            if (code in 200..299 && body.isNotEmpty()) {
                put(context, url, body, ct)
            }
        }
    }

    fun clearUser(context: Context) {
        runCatching { root(context).deleteRecursively() }
    }

    private fun jsonResponse(bytes: ByteArray, ct: String): WebResourceResponse {
        return VinorWebResponses.of(
            ct.substringBefore(';').ifBlank { "application/json" },
            "utf-8",
            bytes,
        )
    }

    private fun touch(meta: File) {
        runCatching {
            val obj = VinorCacheIo.readJson(meta) ?: return
            obj.put("lastAccessedAt", System.currentTimeMillis())
            VinorCacheIo.writeAtomicText(meta, obj.toString())
            meta.setLastModified(System.currentTimeMillis())
        }
    }

    private fun trim(dir: File) {
        val bins = dir.listFiles { f -> f.name.endsWith(".bin") }?.toList().orEmpty()
        val remove = VinorCacheIo.lruTrim(
            bins,
            VinorCachePolicy.MAX_API_ENTRIES,
            VinorCachePolicy.MAX_API_TOTAL_BYTES,
        )
        remove.forEach { bin ->
            val id = bin.name.removeSuffix(".bin")
            VinorCacheIo.deleteQuiet(bin)
            VinorCacheIo.deleteQuiet(File(dir, "$id.json"))
        }
    }
}
