package ir.vinor.app

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
            SearchSort.NEWEST -> filtered.sortedBy { it.index }
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

    fun parseArea(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || "توافقی" in trimmed) return null
        val latin = SearchQuery.normalizeDigits(trimmed).replace(Regex("[,،٬\\s]"), "")
        val head = latin.substringBefore("متر")
        val number = Regex("\\d+").findAll(head).lastOrNull()?.value ?: return null
        return number.toLongOrNull()?.takeIf { it in 1..1_000_000 }
    }

    fun districtFromLocation(location: String): String {
        val loc = location.trim()
        if (loc.isEmpty()) return ""
        val key = "در "
        val at = loc.lastIndexOf(key)
        if (at < 0) return ""
        return loc.substring(at + key.length).trim()
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
