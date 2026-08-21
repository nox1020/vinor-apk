package ir.vinor.app

import android.content.Context
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object VinorOfflineAssetStore {
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vinor-asset-cache").apply { isDaemon = true }
    }
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    private fun dir(context: Context): File {
        val d = File(
            context.filesDir,
            "${VinorCachePolicy.ROOT_DIR}/v${VinorCachePolicy.SCHEMA_VERSION}/assets/${VinorCacheSession.PUBLIC}",
        )
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun key(url: String): String {
        val n = VinorCachePolicy.normalizeUrl(url)
        return MessageDigest.getInstance("SHA-256").digest(n.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun normalizedNoQuery(url: String): String {
        return runCatching {
            val uri = URI(url.trim())
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }.getOrDefault(url.trim())
    }

    fun cacheAsync(context: Context, url: String?) {
        val u = (url ?: "").trim()
        if (u.isEmpty() || !VinorCachePolicy.isVinorHttps(u)) return
        if (!VinorCachePolicy.isStaticAssetPath(u)) return
        if (inFlight.putIfAbsent(u, true) != null) return
        io.execute {
            try {
                fetchAndStore(context.applicationContext, u)
            } catch (_: Throwable) {
            } finally {
                inFlight.remove(u)
            }
        }
    }

    fun prewarmDefaults(context: Context) {
        val defaults = listOf(
            "https://vinor.ir/static/icons/icon-192.png",
            "https://vinor.ir/static/icons/icon-512.png",
            "https://vinor.ir/static/favicon-32x32.png",
            "https://vinor.ir/static/vendor/fontawesome/css/all.min.css",
            "https://vinor.ir/static/vendor/fontawesome/webfonts/fa-solid-900.woff2",
            "https://vinor.ir/static/fonts/vazirmatn.css",
            "https://vinor.ir/static/fonts/vazirmatn-regular.woff2",
            "https://vinor.ir/static/fonts/vazirmatn-medium.woff2",
            "https://vinor.ir/static/fonts/vazirmatn-bold.woff2",
            "https://vinor.ir/static/vendor/tailwind/tailwindcdn-3.4.17.js",
            "https://vinor.ir/static/vendor/alpine/alpine-3.14.1.min.js",
            "https://vinor.ir/static/express-partner/express-partner-auth.css",
            "https://vinor.ir/static/express-partner/partner-avatar-cache.js",
            "https://vinor.ir/static/express-partner/express-nav.js",
            "https://vinor.ir/static/express-partner/dashboard-chrome.js",
            "https://vinor.ir/static/express-partner/dashboard-search.js",
            "https://vinor.ir/static/express-partner/land-detail.js",
            "https://vinor.ir/static/express-partner/ai-chat.js",
            "https://vinor.ir/static/express-partner/postchi.js",
            "https://vinor.ir/static/express-partner/express-pull-refresh.js",
            "https://vinor.ir/static/express-partner/premium-card-media.js",
            "https://vinor.ir/static/express-partner/skeleton-shell.html",
        )
        defaults.forEach { cacheAsync(context, it) }
    }

    fun cacheMany(context: Context, urls: List<String>) {
        urls.take(80).forEach { cacheAsync(context, it) }
    }

    private fun fetchAndStore(context: Context, url: String) {
        var conn: HttpURLConnection? = null
        try {
            val opened = (URL(url).openConnection() as? HttpURLConnection) ?: return
            conn = opened
            opened.instanceFollowRedirects = true
            opened.connectTimeout = 8000
            opened.readTimeout = 12000
            opened.setRequestProperty("User-Agent", VinorUserAgent.forHttpRequest(context))
            VinorUserAgent.webViewLoadHeaders(context).forEach { (k, v) ->
                opened.setRequestProperty(k, v)
            }
            opened.connect()
            if (opened.responseCode !in 200..299) return
            val ct = (opened.contentType ?: "").lowercase()
            val etag = opened.getHeaderField("ETag").orEmpty()
            val lastMod = opened.getHeaderField("Last-Modified").orEmpty()
            if (!VinorCachePolicy.isCacheableAssetContentType(ct) &&
                !VinorCachePolicy.isStaticAssetPath(url)
            ) {
                return
            }
            val body = opened.inputStream.use { it.readBytes() }
            if (body.isEmpty() || body.size > VinorCachePolicy.MAX_ASSET_BYTES) return
            write(context, url, ct.ifBlank { VinorCachePolicy.guessMime(url) }, body, etag, lastMod)
            val noQuery = normalizedNoQuery(url)
            if (noQuery != url) write(context, noQuery, ct, body, etag, lastMod)
            trim(context)
        } catch (_: Throwable) {
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun write(
        context: Context,
        url: String,
        contentType: String,
        data: ByteArray,
        etag: String,
        lastMod: String,
    ) {
        val d = dir(context)
        val id = key(url)
        val bin = File(d, "$id.bin")
        val meta = File(d, "$id.json")
        if (!VinorCacheIo.writeAtomic(bin, data)) return
        val json = JSONObject()
            .put("url", VinorCachePolicy.normalizeUrl(url))
            .put("ct", contentType)
            .put("createdAt", System.currentTimeMillis())
            .put("lastAccessedAt", System.currentTimeMillis())
            .put("size", data.size)
            .put("etag", etag)
            .put("lastModified", lastMod)
            .put("sha256", sha256(data))
        VinorCacheIo.writeAtomicText(meta, json.toString())
        VinorCacheLog.event("CACHE_WRITE", "asset")
    }

    fun asResponse(context: Context, url: String?): WebResourceResponse? {
        val u = (url ?: "").trim()
        if (u.isEmpty()) return null
        val hit = read(context, u) ?: read(context, normalizedNoQuery(u)) ?: return null
        val age = System.currentTimeMillis() - hit.createdAt
        if (age > VinorCachePolicy.ASSET_TTL_MS && hit.createdAt > 0L) {
            // کهنه: همچنان سرو می‌کنیم تا UI نشکند؛ LRU بعداً حذف می‌کند
            VinorCacheLog.event("CACHE_HIT", "asset stale")
        } else {
            VinorCacheLog.event("CACHE_HIT", "asset")
        }
        val ct = hit.contentType.ifBlank { VinorCachePolicy.guessMime(u) }
        return VinorWebResponses.of(ct, VinorCachePolicy.interceptCharset(ct), hit.bytes)
    }

    private data class AssetHit(
        val contentType: String,
        val bytes: ByteArray,
        val createdAt: Long,
    )

    private fun read(context: Context, url: String): AssetHit? {
        val d = dir(context)
        val id = key(url)
        val bin = File(d, "$id.bin")
        val bytes = VinorCacheIo.readBytes(bin) ?: return null
        if (bytes.isEmpty()) {
            VinorCacheIo.deleteQuiet(bin)
            VinorCacheIo.deleteQuiet(File(d, "$id.json"))
            return null
        }
        val meta = VinorCacheIo.readJson(File(d, "$id.json"))
        File(d, "$id.json").setLastModified(System.currentTimeMillis())
        bin.setLastModified(System.currentTimeMillis())
        return AssetHit(
            contentType = meta?.optString("ct").orEmpty(),
            bytes = bytes,
            createdAt = meta?.optLong("createdAt") ?: 0L,
        )
    }

    fun clear(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }

    /** استاتیک عمومی بعد از logout نگه داشته می‌شود. فقط فایل خراب/خالی را بدون خواندن کل بایت‌ها پاک کن. */
    fun clearPublicIfCorrupted(context: Context) {
        val d = dir(context)
        d.listFiles { f -> f.name.endsWith(".bin") }?.forEach { bin ->
            if (!bin.isFile || bin.length() <= 0L) {
                val id = bin.name.removeSuffix(".bin")
                VinorCacheIo.deleteQuiet(bin)
                VinorCacheIo.deleteQuiet(File(d, "$id.json"))
            }
        }
    }

    private fun trim(context: Context) {
        val d = dir(context)
        val files = d.listFiles { f -> f.name.endsWith(".bin") }?.toList().orEmpty()
        val remove = VinorCacheIo.lruTrim(
            files,
            VinorCachePolicy.MAX_ASSET_FILES,
            VinorCachePolicy.MAX_ASSET_TOTAL_BYTES,
        )
        remove.forEach { bin ->
            val id = bin.name.removeSuffix(".bin")
            VinorCacheIo.deleteQuiet(bin)
            VinorCacheIo.deleteQuiet(File(d, "$id.json"))
            VinorCacheLog.event("CACHE_DELETE", "asset lru")
        }
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
