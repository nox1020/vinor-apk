package ir.vinor.app

/** ردیف‌های SSR صفحهٔ جستجو. ترتیب HTML همان ترتیب جدید به قدیم سرور است. */
object SearchPageHtml {
    private val rowRe = Regex(
        """<a\b[^>]*\bclass\s*=\s*(["'])[^"']*\bvn-search-row\b[^"']*\1[^>]*>[\s\S]*?</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val landsRe = Regex("""/lands/([^/?#]+)""")
    private val divarRe = Regex("""/divar/([^/?#]+)""")

    fun parse(html: String): List<SearchAd> {
        if (html.isBlank()) return emptyList()
        val out = ArrayList<SearchAd>()
        for (match in rowRe.findAll(html)) {
            val block = match.value
            val open = block.substringBefore(">")
            val href = unescape(attr(open, "href"))
            val land = attr(open, "data-land-code")
            val divar = attr(open, "data-divar-token")
            val token = land.ifBlank { divar }.ifBlank { tokenFromHref(href) }
            val title = inner(block, "vn-search-name")
            if (title.isBlank() && token.isBlank()) continue
            val published = attr(open, "data-published-for-sale")
            if (published.equals("false", ignoreCase = true) || published == "0") continue
            val source = when {
                land.isNotBlank() || href.contains("/lands/") -> "vinor"
                divar.isNotBlank() || href.contains("/divar/") -> "divar"
                else -> ""
            }
            val location = inner(block, "vn-search-loc")
            val created = attr(open, "data-created")
                .ifBlank { attr(open, "data-published-at") }
                .ifBlank { attr(block, "datetime") }
            out += SearchAd(
                title = title,
                priceRaw = inner(block, "vn-search-price"),
                location = location,
                city = attr(open, "data-city"),
                district = SearchAds.districtFromLocation(location),
                areaRaw = inner(block, "vn-search-area"),
                token = token,
                source = source,
                url = href,
                imageUrl = unescape(imgSrc(block)),
                imageCount = shots(block),
                commissionAmount = commission(block),
                createdAt = SearchAds.parseTime(created),
                index = out.size,
            )
        }
        return out
    }

    private fun tokenFromHref(href: String): String {
        landsRe.find(href)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it }
        return divarRe.find(href)?.groupValues?.get(1).orEmpty()
    }

    private fun attr(block: String, name: String): String {
        val dq = Regex("""\b$name="([^"]*)"""", RegexOption.IGNORE_CASE).find(block)
        if (dq != null) return dq.groupValues[1]
        val sq = Regex("""\b$name='([^']*)'""", RegexOption.IGNORE_CASE).find(block)
        return sq?.groupValues?.get(1).orEmpty()
    }

    private fun inner(block: String, cls: String): String {
        val re = Regex("""<[^>]*class="[^"]*\b$cls\b[^"]*"[^>]*>([\s\S]*?)</""", RegexOption.IGNORE_CASE)
        val raw = re.find(block)?.groupValues?.get(1).orEmpty()
        return unescape(raw.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
    }

    private fun imgSrc(block: String): String {
        val re = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return re.find(block)?.groupValues?.get(1).orEmpty()
    }

    private fun commission(block: String): Int = digits(inner(block, "vn-search-comm"))

    private fun shots(block: String): Int = digits(inner(block, "vn-search-shots"))

    private fun digits(raw: String): Int {
        val mapped = buildString {
            raw.forEach { c ->
                when (c) {
                    in '۰'..'۹' -> append('0' + (c - '۰'))
                    in '٠'..'٩' -> append('0' + (c - '٠'))
                    in '0'..'9' -> append(c)
                }
            }
        }
        return mapped.toIntOrNull() ?: 0
    }

    private fun unescape(value: String): String =
        value.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
}
