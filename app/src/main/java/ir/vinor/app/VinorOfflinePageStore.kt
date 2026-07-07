package ir.vinor.app

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest

object VinorOfflinePageStore {
    private const val DIR_NAME = "offline_pages"

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun key(url: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun pageFile(context: Context, url: String): File =
        File(dir(context), "${key(url)}.html")

    fun save(context: Context, url: String?, html: String?) {
        val u = (url ?: "").trim()
        val h = (html ?: "").trim()
        if (u.isEmpty() || h.isEmpty()) return
        if (!VinorWebOfflineSession.isPartnerBrowsableUrl(u)) return
        runCatching {
            pageFile(context, u).writeText(h, Charsets.UTF_8)
            val noQuery = runCatching {
                val uri = URI(u)
                URI(uri.scheme, uri.authority, uri.path, null, null).toString()
            }.getOrNull().orEmpty()
            if (noQuery.isNotBlank() && noQuery != u) {
                pageFile(context, noQuery).writeText(h, Charsets.UTF_8)
            }
        }
    }

    fun read(context: Context, url: String?): String? {
        val u = (url ?: "").trim()
        if (u.isEmpty()) return null
        return runCatching {
            val exact = pageFile(context, u)
            if (exact.exists()) return@runCatching exact.readText(Charsets.UTF_8)
            val noQuery = runCatching {
                val uri = URI(u)
                URI(uri.scheme, uri.authority, uri.path, null, null).toString()
            }.getOrNull().orEmpty()
            if (noQuery.isNotBlank()) {
                val fallback = pageFile(context, noQuery)
                if (fallback.exists()) return@runCatching fallback.readText(Charsets.UTF_8)
            }
            null
        }.getOrNull()
    }

    fun asHtmlResponse(context: Context, url: String?): WebResourceResponse? {
        val html = read(context, url) ?: return null
        return WebResourceResponse(
            "text/html",
            "utf-8",
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        )
    }
}

