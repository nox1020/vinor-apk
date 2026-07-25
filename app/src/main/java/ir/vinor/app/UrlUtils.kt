package ir.vinor.app

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

object UrlUtils {
    private const val VINOR_HOST = "vinor.ir"
    private val HOME_PATHS = setOf("", "/", "/home", "/search", "/profile")

    fun normalize(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            var uri = URI(trimmed)
            // Force https and drop fragments/queries
            val scheme = "https"
            var host = uri.host ?: ""
            host = host.lowercase(Locale.US).removePrefix("www.")
            val path = (uri.path ?: "").removeSuffix("/")
            val normPath = if (path.isEmpty()) "/" else path
            URI(scheme, uri.userInfo, host, -1, normPath, null, null).toString()
        } catch (_: URISyntaxException) {
            trimmed
        }
    }

    fun isVinorDomain(url: String): Boolean {
        return try {
            val host = normalizeHost(URI(url).host ?: return false)
            host == VINOR_HOST || host.endsWith(".vinor.ir")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * لینک کوتاه دانلود APK (`/d`) یا فایل `.apk` روی دامنه وینور —
     * نباید با App Links داخل اپ «باز» شود؛ باید دانلود شود.
     */
    fun isApkDownloadUrl(url: String): Boolean {
        val raw = url.trim()
        if (raw.isEmpty()) return false
        return try {
            val uri = if ("://" in raw || raw.startsWith("/")) {
                URI(if (raw.startsWith("/")) "https://$VINOR_HOST$raw" else raw)
            } else {
                URI(raw)
            }
            val host = normalizeHost(uri.host ?: return looksLikeApkPath(raw))
            if (host != VINOR_HOST && !host.endsWith(".vinor.ir")) {
                return raw.lowercase(Locale.US).endsWith(".apk")
            }
            looksLikeApkPath(uri.path ?: "")
        } catch (_: Exception) {
            looksLikeApkPath(raw)
        }
    }

    private fun looksLikeApkPath(pathOrUrl: String): Boolean {
        val p = pathOrUrl.trim().lowercase(Locale.US)
        if (p.isEmpty()) return false
        val pathOnly = p.substringBefore('?').substringBefore('#')
        if (pathOnly == "/d" || pathOnly.endsWith("/d")) return true
        if (pathOnly.endsWith(".apk")) return true
        if (pathOnly.contains("/uploads/apk/")) return true
        return false
    }

    private fun normalizeHost(host: String): String =
        host.lowercase(Locale.US).removePrefix("www.")

    fun isHomePage(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = normalizeHost(uri.host ?: return false)
            if (host != VINOR_HOST && !host.endsWith(".vinor.ir")) return false
            val path = (uri.path ?: "").removeSuffix("/")
            val normPath = if (path.isEmpty()) "/" else path
            HOME_PATHS.contains(normPath)
        } catch (_: Exception) {
            false
        }
    }
}

