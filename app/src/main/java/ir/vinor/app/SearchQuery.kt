package ir.vinor.app

/**
 * قرارداد پارامتر جستجو (فاز A).
 * `deal` همیشه buy است و در query string نوشته نمی‌شود.
 * `rooms` رزرو است و در این فاز چیپ ندارد.
 */
data class SearchQuery(
    val q: String = "",
    val city: String = "",
    val districts: List<String> = emptyList(),
    val priceMin: Long? = null,
    val priceMax: Long? = null,
    val areaMin: Long? = null,
    val areaMax: Long? = null,
    val rooms: Int? = null,
    val sort: SearchSort = SearchSort.NEWEST,
) {
    val deal: String get() = DEAL

    fun normalized(): SearchQuery {
        val prices = swapIfNeeded(priceMin?.takeIf { it > 0L }, priceMax?.takeIf { it > 0L })
        val areas = swapIfNeeded(areaMin?.takeIf { it > 0L }, areaMax?.takeIf { it > 0L })
        return copy(
            q = q.trim(),
            city = city.trim(),
            districts = districts.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            priceMin = prices.first,
            priceMax = prices.second,
            areaMin = areas.first,
            areaMax = areas.second,
            rooms = rooms?.takeIf { it in 0..4 },
            sort = sort,
        )
    }

    fun toQueryString(): String {
        val n = normalized()
        val parts = mutableListOf<String>()
        if (n.q.isNotEmpty()) parts += "q=${encodeQuery(n.q)}"
        if (n.city.isNotEmpty()) parts += "city=${encodeQuery(n.city)}"
        if (n.districts.isNotEmpty()) {
            parts += "district=${encodeQuery(n.districts.joinToString(","))}"
        }
        n.priceMin?.let { parts += "price_min=$it" }
        n.priceMax?.let { parts += "price_max=$it" }
        n.areaMin?.let { parts += "area_min=$it" }
        n.areaMax?.let { parts += "area_max=$it" }
        n.rooms?.let { parts += "rooms=$it" }
        if (n.sort != SearchSort.NEWEST) parts += "sort=${n.sort.wire}"
        return parts.joinToString("&")
    }

    /** همهٔ فیلترها جز شهر (و deal ثابت) پاک می‌شوند. */
    fun clearAll(): SearchQuery = SearchQuery(city = city.trim())

    fun hasClearableFilters(): Boolean {
        val n = normalized()
        return n.q.isNotEmpty() ||
            n.districts.isNotEmpty() ||
            n.priceMin != null ||
            n.priceMax != null ||
            n.areaMin != null ||
            n.areaMax != null ||
            n.rooms != null ||
            n.sort != SearchSort.NEWEST
    }

    fun priceChipLabel(): String? {
        val n = normalized()
        return priceRangeLabel(n.priceMin, n.priceMax)
    }

    fun areaChipLabel(): String? {
        val n = normalized()
        return areaRangeLabel(n.areaMin, n.areaMax)
    }

    fun districtChipLabel(): String? {
        val items = normalized().districts
        if (items.isEmpty()) return null
        if (items.size == 1) return items.first()
        return "${items.first()} +${PartnerFormat.digits(items.size - 1)}"
    }

    fun sortChipLabel(): String = sort.label

    companion object {
        const val DEAL = "buy"
        const val ALL_PROVINCES = "همه استان‌ها"
        private const val EN_DASH = "\u2013"
        private const val DECIMAL = "\u066B"
        private const val BILLION = 1_000_000_000L
        private const val MILLION = 1_000_000L

        /** عدد کاربر: ارقام فارسی/عربی و جداکننده. نامعتبر → null. */
        fun parseAmount(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            if ('-' in raw || '−' in raw || '+' in raw) return null
            val latin = normalizeDigits(raw)
            val intPart = latin.substringBefore(DECIMAL).substringBefore('٫')
            val digits = buildString(intPart.length) {
                for (ch in intPart) {
                    if (ch in '0'..'9') append(ch)
                }
            }
            if (digits.isEmpty()) return null
            val value = digits.toLongOrNull() ?: return null
            return value.takeIf { it > 0L }
        }

        fun normalizeDigits(raw: String): String = buildString(raw.length) {
            for (ch in raw) {
                append(
                    when (ch) {
                        in '۰'..'۹' -> '0' + (ch - '۰')
                        in '٠'..'٩' -> '0' + (ch - '٠')
                        else -> ch
                    },
                )
            }
        }

        private fun swapIfNeeded(min: Long?, max: Long?): Pair<Long?, Long?> {
            if (min != null && max != null && min > max) return max to min
            return min to max
        }

        private fun encodeQuery(value: String): String = buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    ' ' -> append("%20")
                    '&' -> append("%26")
                    '=' -> append("%3D")
                    '#' -> append("%23")
                    '?' -> append("%3F")
                    '%' -> append("%25")
                    '+' -> append("%2B")
                    else -> append(ch)
                }
            }
        }

        private fun priceRangeLabel(min: Long?, max: Long?): String? {
            if (min == null && max == null) return null
            if (min != null && max != null) {
                val minUnit = priceUnit(min)
                val maxUnit = priceUnit(max)
                if (minUnit == maxUnit) {
                    return "${priceNumber(min)}–${priceNumber(max)} $minUnit"
                }
                return "${compactPrice(min)}–${compactPrice(max)}"
            }
            if (max != null) return "تا ${compactPrice(max)}"
            return "از ${compactPrice(min!!)}"
        }

        private fun areaRangeLabel(min: Long?, max: Long?): String? {
            if (min == null && max == null) return null
            val minText = min?.let { PartnerFormat.toman(it) }
            val maxText = max?.let { PartnerFormat.toman(it) }
            return when {
                minText != null && maxText != null -> "$minText$EN_DASH$maxText متر"
                maxText != null -> "تا $maxText متر"
                else -> "از $minText متر"
            }
        }

        private fun compactPrice(value: Long): String {
            val number = priceNumber(value)
            if (number == "۰") return PartnerFormat.toman(value)
            return "$number ${priceUnit(value)}"
        }

        private fun priceUnit(value: Long): String =
            if (value >= BILLION) "میلیارد" else "میلیون"

        private fun priceNumber(value: Long): String {
            val unit = if (value >= BILLION) BILLION else MILLION
            return oneDecimal(value, unit)
        }

        /** حداکثر یک رقم اعشار، با جداکنندهٔ ٫ . */
        private fun oneDecimal(value: Long, unit: Long): String {
            val tenths = (value * 10 + unit / 2) / unit
            var whole = tenths / 10
            var frac = tenths % 10
            if (unit == MILLION && whole >= 1000) {
                return oneDecimal(value, BILLION)
            }
            if (frac < 0) frac = 0
            val wholeText = PartnerFormat.toman(whole)
            if (frac == 0L) return wholeText
            return wholeText + DECIMAL + persianDigit(frac)
        }

        private fun persianDigit(n: Long): Char = "۰۱۲۳۴۵۶۷۸۹"[n.toInt().coerceIn(0, 9)]
    }
}

enum class SearchSort(val wire: String, val label: String) {
    NEWEST("newest", "جدیدترین"),
    PRICE_ASC("price_asc", "ارزان\u200Cترین"),
    PRICE_DESC("price_desc", "گران\u200Cترین"),
    AREA_ASC("area_asc", "کم\u200Cمتراژترین"),
    AREA_DESC("area_desc", "بیشترین متراژ"),
    ;

    companion object {
        fun fromWire(raw: String?): SearchSort {
            val wire = raw?.trim()?.lowercase().orEmpty()
            return values().firstOrNull { it.wire == wire } ?: NEWEST
        }
    }
}
