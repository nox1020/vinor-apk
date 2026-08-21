package ir.vinor.app

import android.content.Context
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

object VinorOfflinePageStore {

    fun save(context: Context, url: String?, html: String?) {
        val u = (url ?: "").trim()
        var h = (html ?: "").trim()
        if (u.isEmpty() || h.isEmpty()) return
        if (!VinorWebOfflineSession.isPartnerBrowsableUrl(u)) return
        if (h.toByteArray(Charsets.UTF_8).size > VinorCachePolicy.MAX_HTML_BYTES) {
            VinorCacheLog.event("CACHE_DELETE", "snapshot too large")
            return
        }
        if (!htmlLooksHealthy(h)) return
        h = ensureBaseHref(h, u)
        h = appendOfflineMeta(h, System.currentTimeMillis())
        val dir = dir(context, forWrite = true)
        val id = key(u)
        val htmlFile = File(dir, "$id.html")
        val metaFile = File(dir, "$id.json")
        if (!VinorCacheIo.writeAtomicText(htmlFile, h)) return
        val meta = JSONObject()
            .put("url", VinorCachePolicy.normalizeUrl(u))
            .put("createdAt", System.currentTimeMillis())
            .put("lastAccessedAt", System.currentTimeMillis())
            .put("size", h.length)
            .put("session", VinorCacheSession.fingerprint(context))
        VinorCacheIo.writeAtomicText(metaFile, meta.toString())
        val noQuery = stripQuery(u)
        if (noQuery.isNotBlank() && noQuery != u) {
            val id2 = key(noQuery)
            VinorCacheIo.writeAtomicText(File(dir, "$id2.html"), h)
            VinorCacheIo.writeAtomicText(File(dir, "$id2.json"), meta.put("url", noQuery).toString())
        }
        VinorWebOfflineSession.markCachedAt(context, System.currentTimeMillis())
        VinorCacheLog.event("CACHE_WRITE", "page")
        trim(dir)
    }

    fun read(context: Context, url: String?): String? {
        val u = (url ?: "").trim()
        if (u.isEmpty()) return null
        val dir = dir(context)
        val exact = File(dir, "${key(u)}.html")
        val html = VinorCacheIo.readText(exact)
        if (!html.isNullOrBlank()) {
            if (!htmlLooksHealthy(html)) {
                VinorCacheLog.event("CACHE_DELETE", "page corrupt")
                deletePair(dir, key(u))
                return null
            }
            touch(File(dir, "${key(u)}.json"))
            VinorCacheLog.event("CACHE_HIT", "page")
            return html
        }
        val noQuery = stripQuery(u)
        if (noQuery.isNotBlank() && noQuery != u) {
            return read(context, noQuery)
        }
        VinorCacheLog.event("CACHE_MISS", "page")
        return null
    }

    fun asHtmlResponse(context: Context, url: String?): WebResourceResponse? {
        val html = read(context, url) ?: return null
        return VinorWebResponses.html(html.toByteArray(Charsets.UTF_8))
    }

    fun readLatest(context: Context): String? {
        return runCatching {
            val latest = dir(context)
                .listFiles { f -> f.name.endsWith(".html") }
                ?.maxByOrNull { it.lastModified() }
                ?: return@runCatching null
            val html = VinorCacheIo.readText(latest)
            if (html.isNullOrBlank() || !htmlLooksHealthy(html)) null else html
        }.getOrNull()
    }

    fun lastSavedAt(context: Context): Long =
        VinorWebOfflineSession.lastCachedAt(context)

    fun hasAny(context: Context): Boolean = runCatching {
        dir(context).listFiles { f ->
            f.isFile && f.name.endsWith(".html") && f.length() >= 200L
        }?.isNotEmpty() == true
    }.getOrDefault(false)

    fun clearUser(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }

    fun migrateLegacy(context: Context) {
        val old = File(context.filesDir, "offline_pages")
        if (!old.isDirectory) return
        val dest = dir(context)
        old.listFiles { f -> f.name.endsWith(".html") }?.forEach { src ->
            val target = File(dest, src.name)
            if (!target.exists()) {
                runCatching { src.copyTo(target, overwrite = false) }
            }
        }
    }

    private fun dir(context: Context, forWrite: Boolean = false): File {
        val fp = if (forWrite) {
            VinorCacheSession.scopeForWrite(context)
        } else {
            VinorCacheSession.scopeForRead(context)
        }
        val d = File(
            context.filesDir,
            "${VinorCachePolicy.ROOT_DIR}/v${VinorCachePolicy.SCHEMA_VERSION}/pages/$fp",
        )
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun key(url: String): String {
        val n = VinorCachePolicy.normalizeUrl(url)
        return MessageDigest.getInstance("SHA-256").digest(n.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun stripQuery(url: String): String = runCatching {
        val uri = URI(url)
        URI(uri.scheme, uri.authority, uri.path, null, null).toString()
    }.getOrDefault(url)

    private fun htmlLooksHealthy(html: String): Boolean {
        val lower = html.lowercase()
        if (lower.contains("chrome-error://")) return false
        if (lower.contains("err_internet_disconnected")) return false
        if (lower.contains("err_name_not_resolved")) return false
        if (lower.contains("webpage not available")) return false
        if (!lower.contains("<html") && !lower.contains("<body")) return false
        return html.length >= 200
    }

    private fun ensureBaseHref(html: String, url: String): String {
        if (html.contains("<base", ignoreCase = true)) return html
        val base = stripQuery(url).ifBlank { VinorPartnerUrls.ENTRY }
        val tag = """<base href="$base">"""
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (head != null) {
            html.replaceRange(head.range.last + 1, head.range.last + 1, tag)
        } else {
            "<head>$tag</head>$html"
        }
    }

    private fun appendOfflineMeta(html: String, ts: Long): String {
        if (html.contains("__vinorOfflineSnapshot")) return html
        val snippet =
            """<script>window.__vinorOfflineSnapshot=1;window.__vinorCacheMeta={lastCachedAt:$ts};</script>"""
        val idx = html.lastIndexOf("</body>", ignoreCase = true)
        return if (idx >= 0) html.substring(0, idx) + snippet + html.substring(idx) else html + snippet
    }

    private fun touch(meta: File) {
        runCatching {
            val obj = VinorCacheIo.readJson(meta) ?: return
            obj.put("lastAccessedAt", System.currentTimeMillis())
            VinorCacheIo.writeAtomicText(meta, obj.toString())
            meta.setLastModified(System.currentTimeMillis())
        }
    }

    private fun deletePair(dir: File, id: String) {
        VinorCacheIo.deleteQuiet(File(dir, "$id.html"))
        VinorCacheIo.deleteQuiet(File(dir, "$id.json"))
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".html") }?.toList().orEmpty()
        val remove = VinorCacheIo.lruTrim(
            files,
            VinorCachePolicy.MAX_SNAPSHOTS,
            VinorCachePolicy.MAX_SNAPSHOT_TOTAL_BYTES,
        )
        remove.forEach { html ->
            val id = html.name.removeSuffix(".html")
            deletePair(dir, id)
            VinorCacheLog.event("CACHE_DELETE", "page lru")
        }
    }
}
