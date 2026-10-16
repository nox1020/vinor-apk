package ir.vinor.app

import org.json.JSONObject

/**
 * صفحهٔ جستجو برای مهمان ردیف SSR ندارد.
 * آگهی‌های منتشرشدهٔ عمومی همان نشانی‌های `/express/partner/lands/` در sitemap هستند.
 */
object PublicSearchCatalog {
    const val SITEMAP_PATH = "/sitemap.xml"
    /** سقف ایمنی مسیر sitemap؛ با جستجوی وب هم‌تراز تا همهٔ آگهی‌های عمومی بیایند. */
    const val MAX = 5_000

    private val urlRe = Regex(
        """<url>\s*<loc>\s*([^<]+?)\s*</loc>(?:\s*<lastmod>\s*([^<]+?)\s*</lastmod>)?""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val landRe = Regex("""/express/partner/lands/([^/?#\s]+)""")

    data class LandRef(val code: String, val lastmod: String)

    fun landRefs(sitemap: String): List<LandRef> {
        if (sitemap.isBlank()) return emptyList()
        val out = ArrayList<LandRef>()
        val seen = HashSet<String>()
        for (match in urlRe.findAll(sitemap)) {
            val code = landRe.find(match.groupValues[1])?.groupValues?.get(1)?.trim().orEmpty()
            if (code.isEmpty() || !seen.add(code)) continue
            val lastmod = match.groupValues.getOrElse(2) { "" }.trim()
            out += LandRef(code, lastmod)
        }
        return out.sortedWith(compareByDescending<LandRef> { SearchAds.parseTime(it.lastmod) }.thenBy { it.code })
    }

    /** جزئیات عمومی ملک. فقط `published_for_sale` روشن برمی‌گردد. */
    fun fromDetail(obj: JSONObject, lastmod: String, index: Int): SearchAd? {
        if (!listed(obj)) return null
        val code = text(obj, "code")
        if (code.isEmpty()) return null
        val title = text(obj, "title")
        if (title.isEmpty()) return null
        val price = text(obj, "price")
        val toman = obj.optLong("price_toman")
        val images = obj.optJSONArray("images")
        val image = text(obj, "image_url")
        val created = lastmod.trim().ifBlank { text(obj, "created_at") }
            .ifBlank { text(obj, "published_at") }
        val location = text(obj, "location")
        return SearchAd(
            title = title,
            priceRaw = price.ifBlank { if (toman > 0L) PartnerFormat.toman(toman) + " تومان" else "" },
            location = location,
            city = text(obj, "city"),
            district = SearchAds.districtFromLocation(location),
            areaRaw = areaOf(obj),
            token = code,
            source = "vinor",
            url = "/express/partner/lands/$code",
            imageUrl = image,
            imageCount = images?.length() ?: if (image.isNotEmpty()) 1 else 0,
            commissionAmount = obj.optInt("commission_amount"),
            createdAt = SearchAds.parseTime(created),
            index = index,
        )
    }

    private fun listed(obj: JSONObject): Boolean {
        if (Listings.published(obj)) return true
        if (!obj.has("published_for_sale")) return false
        return Listings.flagTrue(obj, "published_for_sale")
    }

    private fun areaOf(obj: JSONObject): String {
        val size = obj.opt("size")
        if (size is Number && size.toLong() > 0L) return size.toLong().toString()
        return text(obj, "size").ifBlank { text(obj, "area") }
    }

    private fun text(obj: JSONObject, key: String): String {
        val value = obj.optString(key).trim()
        return if (value.isEmpty() || value == "null") "" else value
    }
}
