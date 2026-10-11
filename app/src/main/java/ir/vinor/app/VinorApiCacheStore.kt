package ir.vinor.app

import android.content.Context
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** کش GETهای نمایشی همکار — نه POST و نه مسیرهای مالی. */
object VinorApiCacheStore {

    private data class MemEntry(val bytes: ByteArray, val ct: String, val createdAt: Long)

    private val memLock = Any()
    private val cacheEpoch = AtomicInteger(0)
    private val mem = object : LinkedHashMap<String, MemEntry>(VinorCachePolicy.MAX_API_ENTRIES + 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemEntry>?): Boolean =
            size > VinorCachePolicy.MAX_API_ENTRIES
    }

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vinor-api-cache").apply { isDaemon = true }
    }

    private fun root(context: Context, forWrite: Boolean = false): File {
        val fp = if (forWrite) {
            VinorCacheSession.scopeForWrite(context)
        } else {
            VinorCacheSession.scopeForRead(context)
        }
        return directory(context, fp)
    }

    /** حافظهٔ RAM با اثر انگشت حساب جدا می‌شود تا تاریخچهٔ چت حساب قبلی نماند. */
    internal fun sessionMemoryKey(session: String, normalizedUrl: String): String {
        val fp = session.trim().ifBlank { VinorCacheSession.ANON }
        return "$fp\u0000$normalizedUrl"
    }

    private fun directory(context: Context, session: String): File {
        val fp = session.trim().ifBlank { VinorCacheSession.ANON }
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
        val n = VinorCachePolicy.normalizeUrl(url)
        val ct = contentType.ifBlank { "application/json" }
        val copy = body.copyOf()
        val now = System.currentTimeMillis()
        val session = VinorCacheSession.scopeForRead(context)
        val ticket = cacheEpoch.get()
        synchronized(memLock) { mem[sessionMemoryKey(session, n)] = MemEntry(copy, ct, now) }
        io.execute {
            runCatching {
                if (ticket != cacheEpoch.get()) return@runCatching
                if (VinorCacheSession.scopeForRead(context) != session) return@runCatching
                val dir = directory(context, session)
                val id = key(url)
                val bin = File(dir, "$id.bin")
                val meta = File(dir, "$id.json")
                if (!VinorCacheIo.writeAtomic(bin, copy)) return@runCatching
                val json = JSONObject()
                    .put("url", n)
                    .put("ct", ct)
                    .put("createdAt", now)
                    .put("lastAccessedAt", now)
                    .put("size", copy.size)
                    .put("session", session)
                VinorCacheIo.writeAtomicText(meta, json.toString())
                VinorCacheLog.event("CACHE_WRITE", "api size=${copy.size}")
                trim(dir)
            }
        }
    }

    fun asResponse(context: Context, url: String?, allowExpired: Boolean = false): WebResourceResponse? {
        val hit = load(context, url, allowExpired) ?: return null
        return jsonResponse(hit.first, hit.second)
    }

    fun readJson(context: Context, url: String?, allowExpired: Boolean = true): JSONObject? {
        val hit = load(context, url, allowExpired) ?: return null
        return runCatching { JSONObject(String(hit.first, Charsets.UTF_8)) }.getOrNull()
    }

    /** آخرین پاسخ نمایشی را دور می‌ریزد تا موجودی و پورسانت بعد از معامله کهنه نماند. */
    fun forget(context: Context, url: String) {
        val raw = url.trim()
        if (raw.isEmpty() || !VinorCachePolicy.isSafeGetApi(raw)) return
        val normalized = VinorCachePolicy.normalizeUrl(raw)
        val session = VinorCacheSession.scopeForRead(context)
        synchronized(memLock) { mem.remove(sessionMemoryKey(session, normalized)) }
        val dir = directory(context, session)
        val id = key(raw)
        VinorCacheIo.deleteQuiet(File(dir, "$id.bin"))
        VinorCacheIo.deleteQuiet(File(dir, "$id.json"))
    }

    fun isFresh(context: Context, url: String?): Boolean {
        val raw = (url ?: "").trim()
        if (!VinorCachePolicy.isSafeGetApi(raw)) return false
        val n = VinorCachePolicy.normalizeUrl(raw)
        val memId = sessionMemoryKey(VinorCacheSession.scopeForRead(context), n)
        val memCreated = synchronized(memLock) { mem[memId]?.createdAt }
        if (memCreated != null) {
            val age = System.currentTimeMillis() - memCreated
            return age in 0 until VinorCachePolicy.API_STALE_MS
        }
        val dir = root(context)
        val id = key(raw)
        val bin = File(dir, "$id.bin")
        if (!bin.exists() || bin.length() <= 0L) return false
        val created = VinorCacheIo.readJson(File(dir, "$id.json"))?.optLong("createdAt") ?: return false
        val age = System.currentTimeMillis() - created
        return age in 0 until VinorCachePolicy.API_STALE_MS
    }

    private fun load(
        context: Context,
        url: String?,
        allowExpired: Boolean,
    ): Pair<ByteArray, String>? {
        val raw = (url ?: "").trim()
        if (!VinorCachePolicy.isSafeGetApi(raw)) return null
        val n = VinorCachePolicy.normalizeUrl(raw)
        val memId = sessionMemoryKey(VinorCacheSession.scopeForRead(context), n)
        val memHit = synchronized(memLock) { mem[memId] }
        if (memHit != null) {
            val age = System.currentTimeMillis() - memHit.createdAt
            if (age > VinorCachePolicy.API_KEEP_MS && !allowExpired) {
                synchronized(memLock) { mem.remove(memId) }
            } else {
                VinorCacheLog.event("CACHE_HIT", "api mem stale=${age > VinorCachePolicy.API_STALE_MS}")
                return memHit.bytes to memHit.ct
            }
        }
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
        val created = obj?.optLong("createdAt") ?: System.currentTimeMillis()
        synchronized(memLock) { mem[memId] = MemEntry(bytes, ct, created) }
        VinorCacheLog.event("CACHE_HIT", "api stale=${age > VinorCachePolicy.API_STALE_MS}")
        return bytes to ct
    }

    private fun missNoQuery(context: Context, url: String, allowExpired: Boolean): Pair<ByteArray, String>? {
        val noQuery = runCatching {
            val uri = URI(url)
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }.getOrNull() ?: return null
        if (noQuery == url) return null
        return load(context, noQuery, allowExpired)
    }

    fun warmup(context: Context) {
        VinorSafe.onMain {
            VinorCacheSession.fingerprint(context)
            val cookie = VinorSafe.cookieOrEmpty(VinorPartnerUrls.BASE)
            val urls = mutableListOf(
                "${VinorPartnerUrls.BASE}/express/partner/home/data",
            )
            if (cookie.length >= 12) {
                urls.addAll(
                    listOf(
                        "${VinorPartnerUrls.BASE}/express/partner/api/notifications",
                        "${VinorPartnerUrls.BASE}/express/partner/api/notes",
                        "${VinorPartnerUrls.BASE}/express/partner/api/public-lands",
                        "${VinorPartnerUrls.BASE}/express/partner/profile/data",
                        "${VinorPartnerUrls.BASE}/express/partner/routine/data",
                        "${VinorPartnerUrls.BASE}/express/partner/commissions/data",
                        "${VinorPartnerUrls.BASE}/express/partner/ai/history",
                    ),
                )
            }
            io.execute {
                urls.forEach { fetchAndStore(context.applicationContext, it, cookie) }
            }
        }
    }

    private fun fetchAndStore(context: Context, url: String, cookie: String) {
        var conn: HttpURLConnection? = null
        try {
            val opened = (URL(url).openConnection() as? HttpURLConnection) ?: return
            conn = opened
            opened.requestMethod = "GET"
            opened.connectTimeout = 10_000
            opened.readTimeout = 12_000
            opened.instanceFollowRedirects = true
            opened.setRequestProperty("Accept", "application/json")
            opened.setRequestProperty("Cookie", cookie)
            opened.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            opened.setRequestProperty("User-Agent", VinorUserAgent.forHttpRequest(context))
            val code = opened.responseCode
            val ct = opened.contentType ?: "application/json"
            val stream = if (code in 200..299) opened.inputStream else opened.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code in 200..299 && body.isNotEmpty()) {
                put(context, url, body, ct)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun clearUser(context: Context) {
        cacheEpoch.incrementAndGet()
        synchronized(memLock) { mem.clear() }
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
