package ir.vinor.app

import java.net.URI

/**
 * مسیرهای پنل همکار — پیش‌فرض ورود: /express/partner/ (هدایت نقش‌محور سمت سرور).
 */
object VinorPartnerUrls {
    const val BASE = "https://vinor.ir"
    const val ENTRY = "$BASE/express/partner/"
    const val DASHBOARD = "$BASE/express/partner/dashboard"
    const val COMMISSIONS = "$BASE/express/partner/commissions"

    private val BARE_ENTRY_PATHS = setOf(
        "/express/partner",
        "/express/partner/",
        "/express/partner/dashboard",
    )

    fun toAbsolute(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ENTRY
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return if (trimmed.startsWith("/")) "$BASE$trimmed" else "$BASE/$trimmed"
    }

    fun isBareDashboard(url: String): Boolean {
        return try {
            val uri = URI(toAbsolute(url))
            val path = (uri.path ?: "").removeSuffix("/").ifEmpty { "/" }
            val norm = if (path == "/express/partner") "/express/partner" else path
            norm == "/express/partner/dashboard" && uri.query.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * باز کردن اپ / deep link: داشبورد خالی → ریشه پنل (سرور همکار را به پورسانت می‌فرستد).
     */
    fun normalizeEntryUrl(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return ENTRY
        if (!UrlUtils.isVinorDomain(toAbsolute(trimmed))) return ENTRY
        return try {
            val absolute = toAbsolute(trimmed)
            val uri = URI(absolute)
            val path = (uri.path ?: "").removeSuffix("/").ifEmpty { "/" }
            val normPath = when (path) {
                "/express/partner" -> "/express/partner"
                else -> path
            }
            if (normPath in BARE_ENTRY_PATHS && uri.query.isNullOrBlank()) ENTRY else absolute
        } catch (_: Exception) {
            if (isBareDashboard(trimmed)) ENTRY else toAbsolute(trimmed)
        }
    }

    /** بعد از verify: از redirect سرور استفاده کن؛ داشبورد عمومی → ریشه پنل. */
    fun resolvePostLoginUrl(redirect: String?): String {
        val raw = redirect?.trim().orEmpty()
        if (raw.isEmpty()) return ENTRY
        val absolute = toAbsolute(raw)
        return if (isBareDashboard(absolute)) ENTRY else absolute
    }
}
