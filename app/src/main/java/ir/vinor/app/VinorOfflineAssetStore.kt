package ir.vinor.app

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object VinorOfflineAssetStore {
    private const val DIR_NAME = "offline_assets"
    private const val MAX_ENTRIES = 700
    private const val MAX_BYTES = 6 * 1024 * 1024
    private val io = Executors.newSingleThreadExecutor()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun key(url: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun binFile(context: Context, url: String) = File(dir(context), "${key(url)}.bin")
    private fun metaFile(context: Context, url: String) = File(dir(context), "${key(url)}.meta")

    private fun normalizedNoQuery(url: String): String {
        return runCatching {
            val uri = URI(url.trim())
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }.getOrDefault(url.trim())
    }

    fun cacheAsync(context: Context, url: String?) {
        val u = (url ?: "").trim()
        if (u.isEmpty()) return
        if (inFlight.putIfAbsent(u, true) != null) return
        io.execute {
            try {
                fetchAndStore(context, u)
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
            "https://vinor.ir/static/fonts/vazirmatn.css"
        )
        defaults.forEach { cacheAsync(context, it) }
    }

    private fun fetchAndStore(context: Context, url: String) {
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", VinorUserAgent.forHttpRequest(context))
        VinorUserAgent.webViewLoadHeaders(context).forEach { (k, v) ->
            conn.setRequestProperty(k, v)
        }
        runCatching {
            val ck = CookieManager.getInstance().getCookie(url)
            if (!ck.isNullOrBlank()) conn.setRequestProperty("Cookie", ck)
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return
        }
        val ct = (conn.contentType ?: "").lowercase()
        if (!isCacheableContentType(ct)) {
            conn.disconnect()
            return
        }
        val body = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        if (body.isEmpty() || body.size > MAX_BYTES) return
        write(context, url, ct, body)
        val noQuery = normalizedNoQuery(url)
        if (noQuery != url) write(context, noQuery, ct, body)
        trim(context)
    }

    private fun isCacheableContentType(ct: String): Boolean {
        return ct.startsWith("image/") ||
            ct.contains("text/css") ||
            ct.contains("javascript") ||
            ct.startsWith("font/")
    }

    private fun write(context: Context, url: String, contentType: String, data: ByteArray) {
        runCatching {
            val bin = binFile(context, url)
            val meta = metaFile(context, url)
            bin.writeBytes(data)
            meta.writeText(contentType, Charsets.UTF_8)
        }
    }

    fun asResponse(context: Context, url: String?): WebResourceResponse? {
        val u = (url ?: "").trim()
        if (u.isEmpty()) return null
        val read = read(context, u) ?: read(context, normalizedNoQuery(u)) ?: return null
        return WebResourceResponse(
            read.first.ifBlank { guessMime(u) },
            "utf-8",
            ByteArrayInputStream(read.second)
        )
    }

    private fun read(context: Context, url: String): Pair<String, ByteArray>? {
        return runCatching {
            val bin = binFile(context, url)
            if (!bin.exists()) return@runCatching null
            val meta = metaFile(context, url)
            val ct = if (meta.exists()) meta.readText(Charsets.UTF_8).trim() else ""
            Pair(ct, bin.readBytes())
        }.getOrNull()
    }

    private fun guessMime(url: String): String {
        val p = url.lowercase()
        return when {
            p.endsWith(".png") -> "image/png"
            p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
            p.endsWith(".webp") -> "image/webp"
            p.endsWith(".gif") -> "image/gif"
            p.endsWith(".svg") -> "image/svg+xml"
            p.endsWith(".css") -> "text/css"
            p.endsWith(".js") -> "application/javascript"
            p.endsWith(".woff2") -> "font/woff2"
            p.endsWith(".woff") -> "font/woff"
            else -> "application/octet-stream"
        }
    }

    fun clear(context: Context) {
        runCatching {
            dir(context).listFiles()?.forEach { it.delete() }
        }
    }

    private fun trim(context: Context) {
        runCatching {
            val files = dir(context).listFiles { f -> f.name.endsWith(".bin") }?.toList() ?: return
            if (files.size <= MAX_ENTRIES) return
            val remove = files.sortedBy { it.lastModified() }.take(files.size - MAX_ENTRIES)
            remove.forEach { bin ->
                val base = bin.name.removeSuffix(".bin")
                bin.delete()
                File(dir(context), "$base.meta").delete()
            }
        }
    }
}

