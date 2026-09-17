package ir.vinor.app

import java.net.URI
import java.util.Locale

/**
 * سیاست واحد Cache / Offline برای پوستهٔ WebView.
 *
 * منبع تصمیم:
 * - HTML پنل همکار در بک‌اند `Cache-Control: no-store` است → HTTP cache مرورگر HTML را نگه نمی‌دارد.
 * - استاتیک hashed در Flask غالباً `max-age=31536000, immutable` است.
 * - APIهای همکار عمدتاً `no-store`اند → فقط «آخرین پاسخ معتبر» برای نمایش آفلاین، نه freshness.
 * - عملیات مالی/معامله هرگز موفقیت جعلی نمی‌گیرند.
 */
object VinorCachePolicy {

    const val SCHEMA_VERSION = 2
    const val ROOT_DIR = "vinor_cache"

    const val MAX_HTML_BYTES = 1_500_000
    const val MAX_SNAPSHOTS = 24
    const val MAX_SNAPSHOT_TOTAL_BYTES = 12L * 1024 * 1024

    const val MAX_ASSET_BYTES = 4 * 1024 * 1024
    const val MAX_ASSET_FILES = 800
    const val MAX_ASSET_TOTAL_BYTES = 80L * 1024 * 1024

    const val MAX_API_BODY_BYTES = 512 * 1024
    const val MAX_API_ENTRIES = 80
    const val MAX_API_TOTAL_BYTES = 4L * 1024 * 1024

    const val MAX_QUEUE_ITEMS = 40

    /** هشدار کهنگی UI — snapshot را حذف نمی‌کند. */
    const val SNAPSHOT_STALE_MS = 6L * 60 * 60 * 1000

    /** استاتیک عمومی: مطابق max-age بلند بک‌اند، سقف ایمنی ۷ روز. */
    const val ASSET_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** نمایش API: کهنه بعد از ۳۰ دقیقه؛ نگه داشتن تا ۲۴ ساعت برای آفلاین. */
    const val API_STALE_MS = 30L * 60 * 1000
    const val API_KEEP_MS = 24L * 60 * 60 * 1000

    const val QUEUE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val QUEUE_MAX_RETRY = 6

    fun normalizeUrl(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val uri = URI(trimmed)
            val host = (uri.host ?: "").lowercase(Locale.US).removePrefix("www.")
            val path = (uri.path ?: "/").ifBlank { "/" }
            val q = uri.rawQuery
            val base = "https://$host$path"
            if (q.isNullOrBlank()) base else "$base?$q"
        }.getOrDefault(trimmed)
    }

    fun pathOf(url: String): String {
        return runCatching {
            val p = URI(url.trim()).path ?: "/"
            if (p.length > 1 && p.endsWith("/")) p.dropLast(1) else p
        }.getOrDefault("/")
    }

    fun isVinorHttps(url: String?): Boolean =
        UrlUtils.isTrustedVinorHttps((url ?: "").trim())

    fun isPartnerPath(url: String?): Boolean {
        val p = pathOf(url ?: return false).lowercase(Locale.US)
        return p == "/express/partner" || p.startsWith("/express/partner/")
    }

    fun isStaticAssetPath(url: String?): Boolean {
        val p = pathOf(url ?: return false).lowercase(Locale.US)
        return p.startsWith("/static/") ||
            p.startsWith("/uploads/") ||
            p.startsWith("/media/") ||
            p.startsWith("/fonts/")
    }

    fun isCacheableAssetContentType(ct: String): Boolean {
        val c = ct.lowercase(Locale.US)
        return c.startsWith("image/") ||
            c.contains("text/css") ||
            c.contains("javascript") ||
            c.contains("ecmascript") ||
            c.startsWith("font/") ||
            c.contains("woff") ||
            c.contains("svg") ||
            c.contains("octet-stream")
    }

    fun isSafeGetApi(url: String?): Boolean {
        val raw = url ?: return false
        if (!isVinorHttps(raw)) return false
        val p = pathOf(raw).lowercase(Locale.US)
        if (isSensitivePath(p)) return false
        return SAFE_GET_EXACT.contains(p) || SAFE_GET_PREFIX.any { p.startsWith(it) }
    }

    fun isSensitivePath(pathOrUrl: String): Boolean {
        val p = pathOf(if (pathOrUrl.startsWith("http")) pathOrUrl else "https://vinor.ir$pathOrUrl")
            .lowercase(Locale.US)
        if (p.endsWith("/data") && (p.contains("/commissions") || p.contains("/routine") || p.contains("/profile"))) {
            return false
        }
        if (SENSITIVE_EXACT.contains(p)) return true
        return SENSITIVE_SUBSTRINGS.any { p.contains(it) }
    }

    fun isQueueablePost(url: String?): Boolean {
        val raw = url ?: return false
        if (!isVinorHttps(raw)) return false
        val p = pathOf(raw).lowercase(Locale.US)
        if (isSensitivePath(p)) return false
        return QUEUEABLE_POST_EXACT.contains(p) || QUEUEABLE_POST_PREFIX.any { p.startsWith(it) }
    }

    /**
     * charset برای WebResourceResponse.
     * فونت/عکس با encoding=utf-8 در Chromium رندرر را می‌کشد؛
     * encoding=null هم روی بعضی WebViewها NPE می‌دهد → برای باینری رشتهٔ خالی.
     */
    fun isBinaryMime(mime: String?): Boolean {
        val m = (mime ?: "").substringBefore(';').trim().lowercase(Locale.US)
        if (m.contains("svg")) return false
        return m.startsWith("image/") ||
            m.startsWith("font/") ||
            m.startsWith("audio/") ||
            m.startsWith("video/") ||
            m.contains("woff") ||
            m.contains("octet-stream") ||
            m.contains("wasm")
    }

    fun interceptCharset(mime: String?, requested: String? = null): String {
        if (isBinaryMime(mime)) return ""
        return requested?.trim().orEmpty().ifBlank { "utf-8" }
    }

    fun guessMime(url: String): String {
        val p = url.lowercase(Locale.US)
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
            p.endsWith(".ttf") -> "font/ttf"
            p.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private val SAFE_GET_EXACT = setOf(
        "/express/partner/api/notes",
        "/express/partner/api/notifications",
        "/express/partner/api/notifications/unread-count",
        "/express/partner/api/public-lands",
        "/express/partner/api/postchi/messages",
        "/express/partner/profile/data",
        "/express/partner/profile/edit/data",
        "/express/partner/routine/data",
        "/express/partner/routine/steps/detail",
        "/express/partner/commissions/data",
        "/express/partner/dashboard/data",
        "/express/partner/home/data",
        "/express/partner/ai/history",
        "/express/partner/api/training/data",
        "/express/partner/api/levels/data",
        "/express/partner/api/invite/data",
        "/express/partner/api/apply/data",
        "/express/partner/api/requests/data",
        "/express/partner/api/listings/data",
        "/express/partner/api/legal/data",
        "/api/menu",
    )

    private val SAFE_GET_PREFIX = listOf(
        "/express/partner/api/notes",
        "/express/partner/api/notifications",
        "/express/partner/api/public-lands",
        "/express/partner/api/training",
        "/express/partner/api/levels",
        "/express/partner/api/invite",
        "/express/partner/api/apply",
        "/express/partner/api/requests",
        "/express/partner/api/listings",
        "/express/partner/api/legal",
        "/api/express/",
    )

    private val QUEUEABLE_POST_EXACT = setOf(
        "/express/partner/api/notes",
        "/express/partner/api/notifications/read-all",
    )

    private val QUEUEABLE_POST_PREFIX = listOf(
        "/express/partner/api/notes/",
        "/express/partner/api/notifications/",
    )

    private val SENSITIVE_EXACT = setOf(
        "/express/partner/api/login-request",
        "/express/partner/api/verify",
        "/express/partner/otp/resend",
        "/express/partner/api/logout",
        "/express/partner/api/profile/update",
        "/express/partner/api/profile/avatar",
        "/api/fcm/register",
        "/api/fcm/unregister",
        "/api/express/app-version",
        "/api/app/version",
        "/api/fcm/mobile-config",
    )

    private val SENSITIVE_SUBSTRINGS = listOf(
        "/deal",
        "/pay",
        "/payment",
        "/visit",
        "/verify",
        "/otp",
        "/login",
        "/logout",
        "/wallet",
        "/settlement",
        "/finance",
        "/ai/chat",
        "/api/profile/update",
        "/api/profile/avatar",
    )
}
