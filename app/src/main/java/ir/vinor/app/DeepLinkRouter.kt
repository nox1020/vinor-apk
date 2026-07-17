package ir.vinor.app

import android.util.Log
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Router مرکزی Deep Link / Android App Links.
 * فقط مقصد و نیاز به لاگین را مشخص می‌کند — ناوبری را [MainActivity] انجام می‌دهد.
 */
object DeepLinkRouter {

    private const val TAG = "DeepLinkRouter"
    private const val HOST = "vinor.ir"

    enum class DestinationType {
        PROPERTY_DETAIL,
        PROFILE,
        PARTNER_HOME,
        PARTNER_COMMISSIONS,
        PARTNER_ROUTINE,
        PARTNER_LANDS,
        PARTNER_NOTIFICATIONS,
        PARTNER_LOGIN,
        PUBLIC_HOME,
        SEARCH,
        EXPRESS_PUBLIC,
        UNKNOWN_FALLBACK,
    }

    data class Route(
        val type: DestinationType,
        /** URL نهایی برای WebView (همیشه https و دامنه vinor). */
        val targetUrl: String,
        val requiresAuth: Boolean,
        val propertyId: String? = null,
        val originalUri: String = "",
        val logLabel: String = "",
    )

    fun resolve(raw: String?): Route {
        val fallback = Route(
            type = DestinationType.UNKNOWN_FALLBACK,
            targetUrl = VinorPartnerUrls.ENTRY,
            requiresAuth = false,
            originalUri = raw.orEmpty(),
            logLabel = "fallback_home",
        )
        if (raw.isNullOrBlank()) {
            logIncoming(raw, fallback)
            return fallback
        }
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
        if (uri == null) {
            logIncoming(raw, fallback)
            return fallback
        }
        val host = (uri.host ?: "").lowercase(Locale.US).removePrefix("www.")
        if (host != HOST && !host.endsWith(".$HOST")) {
            logIncoming(raw, fallback)
            return fallback
        }
        val path = normalizePath(uri.path)
        val route = when {
            path.matches(Regex("^/property/([^/]+)$", RegexOption.IGNORE_CASE)) -> {
                val id = Regex("^/property/([^/]+)$", RegexOption.IGNORE_CASE)
                    .find(path)!!.groupValues[1]
                propertyRoute(id, raw)
            }
            path.matches(Regex("^/express/partner/lands/([^/]+)$", RegexOption.IGNORE_CASE)) -> {
                val id = Regex("^/express/partner/lands/([^/]+)$", RegexOption.IGNORE_CASE)
                    .find(path)!!.groupValues[1]
                propertyRoute(id, raw, partnerPath = true)
            }
            path.matches(Regex("^/express/([^/]+)$", RegexOption.IGNORE_CASE)) -> {
                val code = Regex("^/express/([^/]+)$", RegexOption.IGNORE_CASE)
                    .find(path)!!.groupValues[1]
                if (code.equals("partner", ignoreCase = true)) {
                    Route(
                        type = DestinationType.PARTNER_HOME,
                        targetUrl = VinorPartnerUrls.ENTRY,
                        requiresAuth = true,
                        originalUri = raw,
                        logLabel = "partner_home",
                    )
                } else {
                    Route(
                        type = DestinationType.EXPRESS_PUBLIC,
                        targetUrl = "https://$HOST/express/$code",
                        requiresAuth = false,
                        propertyId = code,
                        originalUri = raw,
                        logLabel = "express_public:$code",
                    )
                }
            }
            path == "/profile" || path.startsWith("/profile/") ->
                Route(
                    type = DestinationType.PROFILE,
                    targetUrl = "https://$HOST/express/partner/profile${querySuffix(uri)}",
                    requiresAuth = true,
                    originalUri = raw,
                    logLabel = "profile_short",
                )
            path == "/express/partner/profile" || path.startsWith("/express/partner/profile/") ->
                Route(
                    type = DestinationType.PROFILE,
                    targetUrl = "https://$HOST$path${querySuffix(uri)}",
                    requiresAuth = true,
                    originalUri = raw,
                    logLabel = "profile",
                )
            path == "/express/partner/commissions" || path.startsWith("/express/partner/commissions/") ->
                Route(
                    type = DestinationType.PARTNER_COMMISSIONS,
                    targetUrl = "https://$HOST$path${querySuffix(uri)}",
                    requiresAuth = true,
                    originalUri = raw,
                    logLabel = "commissions",
                )
            path == "/express/partner/routine" || path.startsWith("/express/partner/routine/") ->
                Route(
                    type = DestinationType.PARTNER_ROUTINE,
                    targetUrl = "https://$HOST$path${querySuffix(uri)}",
                    requiresAuth = true,
                    originalUri = raw,
                    logLabel = "routine",
                )
            path == "/express/partner/my-properties" ||
                path == "/express/partner/lands" ->
                Route(
                    type = DestinationType.PARTNER_LANDS,
                    targetUrl = "https://$HOST/express/partner/my-properties",
                    requiresAuth = true,
                    originalUri = raw,
                    logLabel = "my_properties",
                )
            path == "/express/partner/notifications" || path.startsWith("/express/partner/notifications/") ->
                Route(
                    type = DestinationType.PARTNER_NOTIFICATIONS,
                    targetUrl = "https://$HOST$path${querySuffix(uri)}",
                    requiresAuth = true,
                    originalUri = raw,
                    logLabel = "notifications",
                )
            path == "/express/partner/login" || path.startsWith("/express/partner/login/") ->
                Route(
                    type = DestinationType.PARTNER_LOGIN,
                    targetUrl = "https://$HOST$path${querySuffix(uri)}",
                    requiresAuth = false,
                    originalUri = raw,
                    logLabel = "login",
                )
            path.startsWith("/express/partner") ->
                Route(
                    type = DestinationType.PARTNER_HOME,
                    targetUrl = VinorPartnerUrls.normalizeEntryUrl(raw),
                    requiresAuth = true,
                    originalUri = raw,
                    logLabel = "partner_path",
                )
            path == "/public" || path.startsWith("/public/") || path == "/search" ->
                Route(
                    type = DestinationType.SEARCH,
                    targetUrl = "https://$HOST${if (path == "/search") "/public" else path}${querySuffix(uri)}",
                    requiresAuth = false,
                    originalUri = raw,
                    logLabel = "search",
                )
            path == "/" || path == "/home" || path.isEmpty() ->
                Route(
                    type = DestinationType.PUBLIC_HOME,
                    targetUrl = "https://$HOST/",
                    requiresAuth = false,
                    originalUri = raw,
                    logLabel = "public_home",
                )
            else -> fallback.copy(originalUri = raw, logLabel = "unknown_path:$path")
        }
        logIncoming(raw, route)
        return route
    }

    fun loginUrlFor(pendingTarget: String): String {
        val next = URLEncoder.encode(pendingTarget, StandardCharsets.UTF_8.name())
        return "https://$HOST/express/partner/login?next=$next"
    }

    private fun propertyRoute(id: String, raw: String, partnerPath: Boolean = false): Route {
        val target = "https://$HOST/express/partner/lands/$id"
        return Route(
            type = DestinationType.PROPERTY_DETAIL,
            targetUrl = target,
            requiresAuth = true,
            propertyId = id,
            originalUri = raw,
            logLabel = "property:$id",
        )
    }

    private fun normalizePath(path: String?): String {
        val p = (path ?: "/").trim()
        if (p.isEmpty()) return "/"
        val noSlash = if (p.length > 1 && p.endsWith("/")) p.dropLast(1) else p
        return if (noSlash.startsWith("/")) noSlash else "/$noSlash"
    }

    private fun querySuffix(uri: URI): String {
        val q = uri.rawQuery
        return if (q.isNullOrBlank()) "" else "?$q"
    }

    private fun logIncoming(raw: String?, route: Route) {
        try {
            Log.i(
                TAG,
                "deeplink in raw=${raw.orEmpty()} type=${route.type} auth=${route.requiresAuth} " +
                    "label=${route.logLabel} target=${route.targetUrl}",
            )
        } catch (_: Throwable) {
            // JVM unit tests without Android runtime
        }
    }
}
