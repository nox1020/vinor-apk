package ir.vinor.app

import android.net.Uri

/**
 * صفحات فرعی همکار داخل اپ — همان HTML وب، بدون خروج به مرورگر.
 * خانهٔ نیتیو و جزئیات ملک از اینجا به شِل نیتیو برمی‌گردند.
 */
class PartnerPageFragment : BaseWebViewFragment() {

    override val targetUrl: String
        get() = arguments?.getString(ARG_URL)?.trim().orEmpty().ifBlank { VinorPartnerUrls.ENTRY }

    override val fragmentTag: String = "PartnerPageFragment"

    override fun consumeInternalUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = (uri.host ?: "").lowercase().removePrefix("www.")
        if (host != "vinor.ir" && !host.endsWith(".vinor.ir")) return false
        val path = (uri.path ?: "/").trimEnd('/').ifBlank { "/" }
        val land = uri.getQueryParameter("land")
        val hostAct = activity as? MainActivity
        return when {
            path == "/express/partner/home" || path == "/express/partner" -> {
                if (!land.isNullOrBlank()) hostAct?.openLand(land) else hostAct?.openPartnerHome()
                true
            }
            path.startsWith("/express/partner/lands/") -> {
                val code = path.substringAfterLast('/')
                if (code.isNotBlank()) hostAct?.openLand(code) else hostAct?.openPartnerHome()
                true
            }
            path.startsWith("/property/") -> {
                val code = path.substringAfterLast('/')
                if (code.isNotBlank()) hostAct?.openLand(code)
                true
            }
            path.matches(Regex("^/express/[^/]+$")) && path != "/express/partner" -> {
                val code = path.substringAfterLast('/')
                if (code.isNotBlank() && !code.equals("partner", ignoreCase = true)) {
                    hostAct?.openLand(code)
                    true
                } else false
            }
            else -> false
        }
    }

    companion object {
        const val ARG_URL = "url"
    }
}
