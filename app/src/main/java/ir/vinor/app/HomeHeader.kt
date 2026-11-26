package ir.vinor.app

/**
 * هدر خانه به سبک دیوار (هم‌تراز با search_feed.html وب):
 * برچسب‌ها بدون «▾» متنی — فلش، آیکون برداری کنار چیپ است؛ شهر داخل قرص جستجو با آیکون مکان.
 *
 * ردیف فیلتر: هر چیپ آیکون + برچسب دارد؛ غیرفعال ← فلش، فعال ← حالت «خط سبز + ته‌رنگ» و ×
 * که فقط همان فیلتر را پاک می‌کند (مثل data-clear-filter وب). چیپ ابتدای ردیف شمارهٔ فیلترهای
 * فعال را نشان می‌دهد و همه را پاک می‌کند (مثل a.vn-search-chip.is-reset وب).
 */
object HomeHeader {
    enum class FilterChip { SORT, DISTRICT, PRICE, AREA }

    fun chipLabel(raw: String): String = raw.replace("▾", "").trim()

    fun cityLabel(city: String): String = city.trim().ifEmpty { SearchQuery.ALL_PROVINCES }

    /** فلش فقط روی چیپ غیرفعال؛ چیپ فعال به‌جایش × دارد. */
    fun showCaret(active: Boolean): Boolean = !active

    fun isActive(query: SearchQuery, chip: FilterChip): Boolean {
        val n = query.normalized()
        return when (chip) {
            FilterChip.SORT -> n.sort != SearchSort.NEWEST
            FilterChip.DISTRICT -> n.districts.isNotEmpty()
            FilterChip.PRICE -> n.priceMin != null || n.priceMax != null
            FilterChip.AREA -> n.areaMin != null || n.areaMax != null
        }
    }

    /** تعداد فیلترهای پاک‌شدنی (متن جستجو + چیپ‌ها)؛ همان چیزی که «پاک کردن» خالی می‌کند. */
    fun activeCount(query: SearchQuery): Int {
        val n = query.normalized()
        var count = FilterChip.values().count { isActive(n, it) }
        if (n.q.isNotEmpty()) count++
        if (n.rooms != null) count++
        return count
    }

    /** × داخل چیپ: فقط همان فیلتر پاک می‌شود؛ شهر و بقیهٔ فیلترها می‌مانند. */
    fun clearChip(query: SearchQuery, chip: FilterChip): SearchQuery = when (chip) {
        FilterChip.SORT -> query.copy(sort = SearchSort.NEWEST)
        FilterChip.DISTRICT -> query.copy(districts = emptyList())
        FilterChip.PRICE -> query.copy(priceMin = null, priceMax = null)
        FilterChip.AREA -> query.copy(areaMin = null, areaMax = null)
    }.normalized()

    fun countLabel(count: Int): String =
        count.toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")
}
