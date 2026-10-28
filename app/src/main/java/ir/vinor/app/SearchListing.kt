package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/** آگهیٔ نرمال‌شده برای فیلتر محلی. قیمت دیوار رشته است. */
data class SearchAd(
    val title: String = "",
    val priceRaw: String = "",
    val location: String = "",
    val city: String = "",
    val district: String = "",
    val areaRaw: String = "",
    val token: String = "",
    val source: String = "",
    val url: String = "",
    val imageUrl: String = "",
    val imageCount: Int = 0,
    val commissionAmount: Int = 0,
    val rooms: Int? = null,
    val createdAt: Long = 0L,
    val index: Int = 0,
) {
    val priceToman: Long? get() = SearchAds.parseToman(priceRaw)
    val areaM2: Long? get() = SearchAds.parseArea(areaRaw)
}

enum class SearchPhase {
    LOADING,
    EMPTY,
    CONTENT,
    ERROR,
}

data class SearchUiState(
    val phase: SearchPhase,
    val items: List<SearchAd> = emptyList(),
    val filtersActive: Boolean = false,
)

object SearchStateReducer {
    fun loading(): SearchUiState = SearchUiState(SearchPhase.LOADING)

    fun onLoaded(items: List<SearchAd>, filtersActive: Boolean): SearchUiState {
        return if (items.isEmpty()) {
            SearchUiState(SearchPhase.EMPTY, filtersActive = filtersActive)
        } else {
            SearchUiState(SearchPhase.CONTENT, items = items, filtersActive = filtersActive)
        }
    }

    /** خطا دادهٔ قبلی را نگه می‌دارد اگر لیست محتوا داشته باشد. */
    fun onNetworkError(previous: SearchUiState): SearchUiState {
        val hadContent = previous.items.isNotEmpty() && previous.phase != SearchPhase.EMPTY
        return SearchUiState(
            phase = SearchPhase.ERROR,
            items = if (hadContent) previous.items else emptyList(),
            filtersActive = if (hadContent) previous.filtersActive else false,
        )
    }
}

object SearchAds {
    fun apply(ads: List<SearchAd>, query: SearchQuery): List<SearchAd> {
        val n = query.normalized()
        val filtered = ads.filter { matches(it, n) }
        return when (n.sort) {
            SearchSort.NEWEST -> newest(filtered)
            SearchSort.PRICE_ASC -> filtered.sortedWith(priceOrder(ascending = true))
            SearchSort.PRICE_DESC -> filtered.sortedWith(priceOrder(ascending = false))
            SearchSort.AREA_ASC -> filtered.sortedWith(areaOrder(ascending = true))
            SearchSort.AREA_DESC -> filtered.sortedWith(areaOrder(ascending = false))
        }
    }

    /** «توافقی» و رشتهٔ خالی یعنی بدون قیمت. جداکننده‌ها و ارقام فارسی حذف می‌شوند. */
    fun parseToman(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || "توافقی" in trimmed) return null
        return SearchQuery.parseAmount(trimmed)
    }

    /** جدیدترین اول. اگر تاریخ باشد همان ملاک است، وگرنه ترتیب سرور حفظ می‌شود. */
    fun newest(ads: List<SearchAd>): List<SearchAd> {
        if (ads.none { it.createdAt > 0L }) return ads.sortedBy { it.index }
        return ads.sortedWith(compareByDescending<SearchAd> { it.createdAt }.thenBy { it.index })
    }

    /**
     * صفحهٔ جستجو اگر ردیف داشته باشد همان فهرست منتشرشده است.
     * وگرنه آگهی‌های خانه، و در نهایت کارت‌های HTML خانه.
     */
    fun catalog(
        searchRows: List<SearchAd>,
        homeAds: List<SearchAd>,
        homeHtmlAds: List<SearchAd>,
    ): List<SearchAd> {
        val picked = when {
            searchRows.isNotEmpty() -> searchRows
            homeAds.isNotEmpty() -> homeAds
            else -> homeHtmlAds
        }
        return dedupe(picked)
    }

    /**
     * کد باز شدن جزئیات. آگهی همکار اغلب `code` دارد نه `token`،
     * و گاهی فقط نشانی `/lands/` یا `?land=` است.
     */
    fun openCode(ad: SearchAd): String {
        val token = ad.token.trim()
        val url = ad.url.trim()
        val divarUrl = pathToken(url, "/divar/")
        val divarQuery = queryValue(url, "divar")
        if (ad.source == "divar" || divarUrl.isNotBlank() || divarQuery.isNotBlank()) {
            val tok = token.ifBlank { divarUrl }.ifBlank { divarQuery }
            if (tok.isNotBlank()) return "divar:$tok"
        }
        if (token.isNotBlank()) return token
        val land = pathToken(url, "/lands/").ifBlank { queryValue(url, "land") }
        return land
    }

    /** آگهی‌های JSON خانه. بدون پرچم انتشار می‌مانند؛ `published_for_sale=false` حذف می‌شود. */
    fun fromObjects(array: JSONArray): List<SearchAd> {
        val out = ArrayList<SearchAd>(array.length())
        for (i in 0 until array.length()) {
            val ad = array.optJSONObject(i) ?: continue
            if (unpublished(ad)) continue
            out += fromObject(ad, out.size)
        }
        return out
    }

    fun dedupe(ads: List<SearchAd>): List<SearchAd> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<SearchAd>(ads.size)
        for (ad in ads) {
            val key = when {
                // One property must remain one card even when Home and public
                // endpoints supply different dates, prices or display titles.
                ad.token.isNotBlank() -> "${ad.source}:${ad.token}"
                ad.url.isNotBlank() -> "u:${ad.url}"
                else -> "t:${ad.title}:${ad.location}:${ad.priceRaw}"
            }
            if (!seen.add(key)) continue
            out += ad.copy(index = out.size)
        }
        return out
    }

    fun parseTime(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return 0L
        text.toLongOrNull()?.let { n ->
            if (n > 1_000_000_000_000L) return n
            if (n > 1_000_000_000L) return n * 1000
        }
        val normalized = text.replace('T', ' ').substringBefore('.').substringBefore('+').substringBefore('Z').trim()
        return try {
            java.time.LocalDateTime.parse(normalized.replace(' ', 'T'))
                .toEpochSecond(java.time.ZoneOffset.UTC) * 1000
        } catch (_: Exception) {
            try {
                java.time.LocalDate.parse(normalized.take(10)).toEpochDay() * 86_400_000L
            } catch (_: Exception) {
                0L
            }
        }
    }

    fun parseArea(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || "توافقی" in trimmed) return null
        val latin = SearchQuery.normalizeDigits(trimmed).replace(Regex("[,،٬\\s]"), "")
        val head = latin.substringBefore("متر")
        val number = Regex("\\d+").findAll(head).lastOrNull()?.value ?: return null
        return number.toLongOrNull()?.takeIf { it in 1..1_000_000 }
    }

    /**
     * قیمت کارت. فقط مبلغ صحیح خام قالب می‌شود: رقم فارسی/عربی/لاتین و جداکنندهٔ هزارگان.
     * واحد، ممیز، یا هر حرف دیگری همان متن سرور می‌ماند.
     */
    fun cardPrice(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "توافقی"
        if (!pureIntegerAmount(trimmed)) return trimmed
        val amount = parseToman(trimmed) ?: return trimmed
        return PartnerFormat.toman(amount) + " تومان"
    }

    /** متراژ کارت. واحد اگر در متن نباشد اضافه می‌شود. */
    fun cardArea(raw: String): String {
        val trimmed = raw.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return ""
        if ("متر" in trimmed) return trimmed
        val meters = parseArea(trimmed) ?: return ""
        return PartnerFormat.toman(meters) + " متر"
    }

    /** محلهٔ کارت: شهر و منطقه، بدون تکرار. عبارت زمانی سرور همان‌طور می‌ماند. */
    fun cardPlace(ad: SearchAd): String {
        val loc = ad.location.trim()
        if (loc.isNotEmpty() && (standaloneWord(loc, "پیش") || loc.contains("ساعت") || loc.contains("هفته"))) {
            return loc
        }
        if (keepLocationText(ad, loc)) return loc
        val town = ad.city.trim()
        val hood = ad.district.trim().ifBlank { districtFromLocation(loc) }.ifBlank {
            loc.removePrefix("در ").trim()
        }.removePrefix("در ").trim()
        val listed = town.isNotEmpty() && (
            hood == town ||
                hood.startsWith("$town،") ||
                hood.startsWith("$town,") ||
                hood.startsWith("$town ·")
            )
        return when {
            town.isNotEmpty() && hood.isNotEmpty() && !listed -> "در $town، $hood"
            hood.isNotEmpty() -> "در $hood"
            town.isNotEmpty() -> "در $town"
            else -> ""
        }
    }

    /**
     * محله بعد از «در» به‌صورت واژهٔ جدا: ابتدای متن یا پس از فاصله/نشانه، و بعدش فاصله.
     * «بندر عباس» برش نمی‌خورد؛ بدون تطبیق درست خالی می‌ماند تا متن محل دست نخورد.
     */
    fun districtFromLocation(location: String): String {
        val loc = location.trim()
        if (loc.isEmpty()) return ""
        val rest = textAfterStandaloneDar(loc)
        return rest?.trim().orEmpty()
    }

    /** رقم و جداکنندهٔ هزارگان (٬ ، , و فاصله). ممیز و حرف قبول نیست. */
    private fun pureIntegerAmount(text: String): Boolean {
        var digits = 0
        for (ch in text) {
            when {
                ch in '0'..'9' || ch in '۰'..'۹' || ch in '٠'..'٩' -> digits++
                ch == ',' || ch == '،' || ch == '٬' || ch.isWhitespace() -> Unit
                else -> return false
            }
        }
        return digits > 0
    }

    /** «پیش» و مانند آن فقط واژهٔ جدا هستند، نه ابتدای «پیشوا». */
    private fun standaloneWord(text: String, word: String): Boolean {
        var from = 0
        while (from <= text.length - word.length) {
            val at = text.indexOf(word, from)
            if (at < 0) return false
            val beforeOk = at == 0 || isWordBoundary(text[at - 1])
            val after = at + word.length
            val afterOk = after >= text.length || isWordBoundary(text[after])
            if (beforeOk && afterOk) return true
            from = at + 1
        }
        return false
    }

    /**
     * اگر «در» فقط داخل واژه آمده (مثل «بندر عباس») محله‌ای در کار نیست
     * و کارت همان متن محل را نشان می‌دهد.
     */
    private fun keepLocationText(ad: SearchAd, loc: String): Boolean {
        if (loc.isEmpty() || ad.district.isNotBlank()) return false
        if (textAfterStandaloneDar(loc) != null) return false
        return darFollowedBySpace(loc)
    }

    /** انتهای محله بعد از آخرین «در» جدا. بدون تطبیق درست null. */
    private fun textAfterStandaloneDar(loc: String): String? {
        val word = "در"
        var startOfRest = -1
        var from = 0
        while (from <= loc.length - word.length) {
            val at = loc.indexOf(word, from)
            if (at < 0) break
            val beforeOk = at == 0 || isWordBoundary(loc[at - 1])
            val after = at + word.length
            if (beforeOk && after < loc.length && loc[after].isWhitespace()) {
                var rest = after + 1
                while (rest < loc.length && loc[rest].isWhitespace()) rest++
                if (rest < loc.length) startOfRest = rest
            }
            from = at + 1
        }
        if (startOfRest < 0) return null
        return loc.substring(startOfRest)
    }

    private fun darFollowedBySpace(loc: String): Boolean {
        val word = "در"
        var from = 0
        while (from <= loc.length - word.length) {
            val at = loc.indexOf(word, from)
            if (at < 0) return false
            val after = at + word.length
            if (after < loc.length && loc[after].isWhitespace()) return true
            from = at + 1
        }
        return false
    }

    private fun isWordBoundary(ch: Char): Boolean {
        if (ch.isWhitespace()) return true
        return when (ch.category) {
            CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION,
            -> true
            else -> false
        }
    }

    fun norm(raw: String): String {
        val mapped = buildString(raw.length) {
            for (ch in raw.trim()) {
                append(
                    when (ch) {
                        'ي', 'ى' -> 'ی'
                        'ك' -> 'ک'
                        in '۰'..'۹' -> '0' + (ch - '۰')
                        in '٠'..'٩' -> '0' + (ch - '٠')
                        else -> ch
                    },
                )
            }
        }
        return mapped.lowercase()
    }

    private fun unpublished(ad: JSONObject): Boolean {
        if (Listings.published(ad)) return false
        if (Listings.isPrivateOwnerHome(ad)) return true
        if (!ad.has("published_for_sale")) return false
        return !Listings.flagTrue(ad, "published_for_sale")
    }

    private fun fromObject(ad: JSONObject, index: Int): SearchAd {
        val area = text(ad, "area").ifBlank { text(ad, "size") }.ifBlank { text(ad, "area_m2") }
        val price = text(ad, "price").ifBlank {
            val toman = ad.optLong("price_toman")
            if (toman > 0) {
                toman.toString()
            } else {
                val total = ad.optLong("price_total")
                if (total > 0) total.toString() else ""
            }
        }
        val location = text(ad, "location")
        val district = text(ad, "district").ifBlank { text(ad, "neighborhood") }
        val created = listOf("created_at", "published_at", "created", "date", "listed_at")
            .firstNotNullOfOrNull { key -> text(ad, key).takeIf { it.isNotEmpty() } }
            .orEmpty()
        val identity = identityOf(ad)
        return SearchAd(
            title = text(ad, "title"),
            priceRaw = price,
            location = location,
            city = text(ad, "city"),
            district = district.ifBlank { districtFromLocation(location) },
            areaRaw = area.ifBlank { areaFromFacts(ad) },
            token = identity.token,
            source = identity.source,
            url = identity.url,
            imageUrl = text(ad, "image_url"),
            imageCount = ad.optInt("image_count"),
            commissionAmount = ad.optInt("commission_amount"),
            createdAt = parseTime(created),
            index = index,
        )
    }

    private data class Identity(val token: String, val source: String, val url: String)

    /** `token` اگر باشد همان می‌ماند؛ وگرنه `code` / `land_code` / نشانی. */
    private fun identityOf(ad: JSONObject): Identity {
        val explicit = text(ad, "token")
        val code = text(ad, "code").ifBlank { text(ad, "land_code") }
        val divarToken = text(ad, "divar_token")
        val rawUrl = text(ad, "url").ifBlank { text(ad, "href") }.ifBlank { text(ad, "link") }
        val sourceHint = text(ad, "source")
        val divar = sourceHint == "divar" || rawUrl.contains("/divar/") || divarToken.isNotBlank() && code.isBlank() && explicit.isBlank()
        val token = when {
            divar -> explicit.ifBlank { divarToken }.ifBlank { pathToken(rawUrl, "/divar/") }
            explicit.isNotBlank() -> explicit
            code.isNotBlank() -> code
            else -> pathToken(rawUrl, "/lands/").ifBlank { queryValue(rawUrl, "land") }
        }
        val source = when {
            sourceHint == "divar" || sourceHint == "vinor" -> sourceHint
            divar -> "divar"
            token.isNotBlank() -> "vinor"
            else -> ""
        }
        val url = rawUrl.ifBlank {
            when {
                source == "divar" && token.isNotBlank() -> "/express/partner/divar/$token"
                source == "vinor" && token.isNotBlank() -> "/express/partner/lands/$token"
                else -> ""
            }
        }
        return Identity(token, source, url)
    }

    private fun pathToken(url: String, marker: String): String {
        val at = url.indexOf(marker)
        if (at < 0) return ""
        return url.substring(at + marker.length)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
    }

    private fun queryValue(url: String, key: String): String {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isBlank() || query == url) return ""
        for (part in query.split('&')) {
            val bits = part.split('=', limit = 2)
            if (bits.size == 2 && bits[0] == key) {
                return runCatching { java.net.URLDecoder.decode(bits[1], "UTF-8") }.getOrDefault(bits[1]).trim()
            }
        }
        return ""
    }

    private fun areaFromFacts(ad: JSONObject): String {
        val facts = ad.optJSONArray("facts") ?: return ""
        for (i in 0 until facts.length()) {
            val bit = facts.optString(i)
            if ("متر" in bit) return bit
        }
        return ""
    }

    private fun text(ad: JSONObject, key: String): String {
        val value = ad.optString(key).trim()
        return if (value.isEmpty() || value == "null") "" else value
    }

    private fun matches(ad: SearchAd, query: SearchQuery): Boolean {
        if (query.q.isNotEmpty()) {
            val hay = norm(
                listOf(ad.title, ad.priceRaw, ad.location, ad.district, ad.city).joinToString(" "),
            )
            if (!hay.contains(norm(query.q))) return false
        }
        if (query.city.isNotEmpty()) {
            val want = norm(query.city)
            val cityOk = norm(ad.city) == want || norm(ad.location).contains(want)
            if (!cityOk) return false
        }
        if (query.districts.isNotEmpty()) {
            val loc = norm(ad.location)
            val district = norm(ad.district)
            val hit = query.districts.any { name ->
                val needle = norm(name)
                needle.isNotEmpty() && (district == needle || loc.contains(needle))
            }
            if (!hit) return false
        }
        if (query.priceMin != null || query.priceMax != null) {
            val price = ad.priceToman ?: return false
            if (query.priceMin != null && price < query.priceMin) return false
            if (query.priceMax != null && price > query.priceMax) return false
        }
        if (query.areaMin != null || query.areaMax != null) {
            val area = ad.areaM2 ?: return false
            if (query.areaMin != null && area < query.areaMin) return false
            if (query.areaMax != null && area > query.areaMax) return false
        }
        val wantedRooms = query.rooms
        if (wantedRooms != null) {
            val rooms = ad.rooms ?: return false
            if (wantedRooms >= 4) {
                if (rooms < 4) return false
            } else if (rooms != wantedRooms) {
                return false
            }
        }
        return true
    }

    /** بدون قیمت همیشه آخر است. */
    private fun priceOrder(ascending: Boolean): Comparator<SearchAd> {
        return compareBy<SearchAd> { it.priceToman == null }
            .thenBy { if (ascending) it.priceToman ?: Long.MAX_VALUE else -(it.priceToman ?: 0L) }
            .thenBy { it.index }
    }

    private fun areaOrder(ascending: Boolean): Comparator<SearchAd> {
        return compareBy<SearchAd> { it.areaM2 == null }
            .thenBy { if (ascending) it.areaM2 ?: Long.MAX_VALUE else -(it.areaM2 ?: 0L) }
            .thenBy { it.index }
    }
}
