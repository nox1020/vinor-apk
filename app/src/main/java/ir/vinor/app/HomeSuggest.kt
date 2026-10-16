package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * فهرست عمودی فایل پیشنهادی خانه.
 * همان کاتالوگ عمومی وب (`divar_pro_ads`) است؛ `suggested_files` فقط وقتی
 * کاتالوگ خالی باشد می‌آید تا یک فایل مأموریت بقیهٔ آگهی‌ها را پنهان نکند.
 */
object HomeSuggest {
    const val LIMIT = 12

    fun files(home: JSONObject): JSONArray {
        val catalog = usable(home.optJSONArray("divar_pro_ads"))
        if (catalog.length() > 0) return catalog
        return usable(home.optJSONArray("suggested_files"))
    }

    fun fingerprint(files: JSONArray): String {
        val parts = ArrayList<String>(files.length())
        for (i in 0 until files.length()) {
            val ad = files.optJSONObject(i) ?: continue
            parts += listOf(
                landCode(ad),
                displayTitle(ad),
                priceLine(ad),
                specLine(ad),
                commissionLabel(ad),
                imageUrl(ad),
                badge(ad),
            ).joinToString("|")
        }
        return parts.joinToString("\n")
    }

    fun title(ad: JSONObject): String {
        val text = ad.optString("title").trim()
        return if (text.isEmpty() || text == "null") "فایل" else text
    }

    /** عنوان کوتاه کارت: «زمین ۵۵۰ متری» از متراژ واقعی عنوان یا `area`. */
    fun displayTitle(ad: JSONObject): String {
        val raw = title(ad)
        val meters = meters(ad)
        if (meters <= 0L) return raw
        val kind = kindOf(raw)
        val up = if (raw.contains("به بالا") || raw.contains("متربه")) " به بالا" else ""
        return "$kind ${PartnerFormat.toman(meters)} متری$up"
    }

    fun subtitle(ad: JSONObject): String {
        val price = priceLine(ad)
        if (price.isNotEmpty()) return price
        return locationLine(ad)
    }

    fun priceLine(ad: JSONObject): String {
        val price = ad.optString("price").trim()
        if (price.isNotEmpty() && price != "null") {
            if ("تومان" in price || "توافقی" in price) return price
            val parsed = SearchAds.parseToman(price)
            if (parsed != null && parsed > 0L) return PartnerFormat.toman(parsed) + " تومان"
            return price
        }
        val toman = priceToman(ad)
        if (toman > 0L) return PartnerFormat.toman(toman) + " تومان"
        return ""
    }

    /** «دماوند - گرمابسرد» از شهرِ لوکیشن و محله‌ای که در خود عنوان آمده. */
    fun locationLine(ad: JSONObject): String {
        val city = cityOf(ad.optString("location"))
        val hood = neighborhood(ad.optString("title"), city)
        return when {
            city.isNotEmpty() && hood.isNotEmpty() && hood != city -> "$city، $hood"
            city.isNotEmpty() -> city
            else -> hood
        }
    }

    /** یک ویژگی واقعی عنوان یا `facts`، کنار متراژ. */
    fun specLine(ad: JSONObject): String {
        val meters = meters(ad)
        val metersText = if (meters > 0L) "${PartnerFormat.toman(meters)} م²" else ""
        val feature = firstFact(ad).ifBlank { featureOf(ad.optString("title")) }
        val featureHasSize = feature.contains("م²") || feature.contains("متر")
        return when {
            feature.isNotEmpty() && metersText.isNotEmpty() && !featureHasSize -> "$feature | $metersText"
            feature.isNotEmpty() -> feature
            else -> metersText
        }
    }

    /** درصد پورسانت از `commission_amount / price_toman`، نه عدد ثابت. */
    fun commissionPercent(ad: JSONObject): Int {
        val explicit = percentField(ad, "commission_pct", "express_commission_pct")
        if (explicit in 1..100) return explicit
        val price = priceToman(ad)
        val commission = longField(ad, "commission_amount")
        if (price <= 0L || commission <= 0L) return 0
        val pct = (commission * 100 + price / 2) / price
        return pct.toInt().coerceIn(0, 100)
    }

    fun commissionLabel(ad: JSONObject): String {
        val pct = commissionPercent(ad)
        if (pct <= 0) return ""
        return "${PartnerFormat.toman(pct.toLong())}٪ پورسانت"
    }

    /** سن آگهی روی گوشهٔ عکس — مثل «۴ روز پیش». */
    fun listedAge(ad: JSONObject): String {
        val value = ad.optString("listed_label").trim()
        if (value.isEmpty() || value == "null") return ""
        return if (ageLabelRe.containsMatchIn(value)) value else ""
    }

    /**
     * برچسب روی عکس کوچک. فیلد واقعی یا «جدید» برای آگهی چندساعت اخیر.
     * «پیشنهادی» تکرار عنوان ردیف است و روی بندانگشتی جا نمی‌شود.
     * برچسب زمانی (`listed_label`) جدا با [listedAge] روی عکس می‌آید.
     */
    fun badge(ad: JSONObject): String {
        listOf("badge", "tag", "ribbon").forEach { key ->
            val value = ad.optString(key).trim()
            if (value.isNotEmpty() && value != "null" && value != "پیشنهادی") return value
        }
        val listed = ad.optString("listed_label").trim()
        if (
            listed.isNotEmpty() &&
            listed != "null" &&
            listed != "پیشنهادی" &&
            !ageLabelRe.containsMatchIn(listed)
        ) {
            return listed
        }
        val location = ad.optString("location")
        if (recentRe.containsMatchIn(location)) return "جدید"
        return ""
    }

    /** ویژگی کارت، بدون تکرار متراژی که عنوان از قبل می‌گوید. */
    fun cardSpec(ad: JSONObject): String {
        val spec = specLine(ad)
        if (meters(ad) <= 0L) return spec
        val feature = spec.substringBefore(" | ").trim()
        if (feature.contains("م²") || feature.contains("متر")) return ""
        return feature
    }

    /** در RTL نقطهٔ صفر، ته ردیف است. شروع یعنی اولین کارت سمت راست. */
    fun startScrollX(contentWidth: Int, viewportWidth: Int, rtl: Boolean): Int {
        val range = (contentWidth - viewportWidth).coerceAtLeast(0)
        return if (rtl) range else 0
    }

    /** new = مرجانی، gold = ویژه، green = بقیه. */
    fun badgeTone(text: String): String = when {
        text.contains("جدید") -> "new"
        text.contains("ویژه") -> "gold"
        else -> "green"
    }

    fun imageUrl(ad: JSONObject): String = PartnerImages.coverUrl(ad)

    fun landCode(ad: JSONObject): String {
        val parsed = SearchAds.fromObjects(JSONArray().put(ad))
        if (parsed.isEmpty()) return ""
        return SearchAds.openCode(parsed[0])
    }

    private fun meters(ad: JSONObject): Long {
        val area = ad.optString("area").trim()
        if (area.isNotEmpty() && area != "null") {
            val fromArea = PartnerApi.asciiDigits(area).toLongOrNull() ?: 0L
            if (fromArea in 10L..99_999L) return fromArea
        }
        return metersIn(ad.optString("title"))
    }

    private fun metersIn(title: String): Long {
        val match = meterRe.find(title.replace("\u200c", "")) ?: return 0L
        val raw = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
        return PartnerApi.asciiDigits(raw).toLongOrNull() ?: 0L
    }

    private fun kindOf(title: String): String {
        val text = title.replace("\u200c", "")
        val land = text.contains("زمین")
        if (land) return "زمین"
        if (Regex("""باغ(?!چه)""").containsMatchIn(text)) return "باغ"
        if (text.contains("ویلا")) return "ویلا"
        if (text.contains("آپارتمان")) return "آپارتمان"
        if (text.contains("خانه")) return "خانه"
        return "زمین"
    }

    private fun featureOf(title: String): String {
        val text = title.replace("\u200c", "")
        return when {
            text.contains("نزدیک آسفالت") || text.contains("نزدیک‌آسفالت") -> "نزدیک آسفالت"
            text.contains("آب") && text.contains("برق") -> "آب و برق"
            text.contains("رهگیری") -> "کد رهگیری"
            text.contains("قابل ساخت") || text.contains("مجوز ساخت") -> "قابل ساخت"
            text.contains("چهاردیوار") || text.contains("چهار دیوار") -> "چهاردیواری"
            text.contains("قسط") || text.contains("اقساط") -> "قسطی"
            text.contains("سند") -> "سند"
            text.contains("بافت ویلایی") -> "بافت ویلایی"
            text.contains("مسکونی") -> "کاربری مسکونی"
            text.contains("تجاری") -> "کاربری تجاری"
            text.contains("ویلایی") -> "ویلایی"
            text.contains("آب") -> "آب"
            else -> ""
        }
    }

    private fun firstFact(ad: JSONObject): String {
        val facts = ad.optJSONArray("facts") ?: return ""
        for (i in 0 until facts.length()) {
            val text = when (val item = facts.opt(i)) {
                is String -> item.trim()
                is JSONObject -> item.optString("text").ifBlank { item.optString("label") }.trim()
                else -> ""
            }
            if (text.isNotEmpty() && text != "null") return text
        }
        return ""
    }

    private fun cityOf(location: String): String {
        var text = location.trim()
        if (text.isEmpty() || text == "null") return ""
        text = recencyRe.replace(text, "").trim()
        text = text.replace(Regex("""^در\s+"""), "").trim()
        return text
    }

    private fun neighborhood(title: String, city: String): String {
        val parts = tokens(title)
        if (parts.isEmpty()) return ""
        val indexes = parts.mapIndexedNotNull { index, token ->
            if (city.isNotEmpty() && (token == city || token.startsWith(city))) index else null
        }
        val cityAt = indexes.lastOrNull()
        if (cityAt != null) {
            for (i in cityAt - 1 downTo 0) {
                if (isPlace(parts[i], city)) return parts[i]
            }
            for (i in cityAt + 1 until parts.size) {
                if (isPlace(parts[i], city)) return parts[i]
            }
            return ""
        }
        return parts.asReversed().firstOrNull { isPlace(it, city) }.orEmpty()
    }

    private fun tokens(title: String): List<String> {
        val cleaned = title.replace("\u200c", "")
            .replace(Regex("""[*+()،,/|«»"\[\]:؛]+"""), " ")
        return cleaned.split(Regex("\\s+"))
            .map { it.trim('-', 'ـ') }
            .filter { it.isNotEmpty() }
    }

    private fun isPlace(token: String, city: String): Boolean {
        if (city.isNotEmpty() && (token == city || token.startsWith(city))) return false
        val letters = token.filter { it.isLetter() }
        if (letters.length < 3) return false
        if (letters in placeSkip || token in placeSkip) return false
        if (token.contains("متر")) return false
        return true
    }

    private fun usable(source: JSONArray?): JSONArray {
        val out = JSONArray()
        if (source == null) return out
        val seen = LinkedHashSet<String>()
        for (i in 0 until source.length()) {
            if (out.length() >= LIMIT) break
            val ad = source.optJSONObject(i) ?: continue
            if (SearchAds.fromObjects(JSONArray().put(ad)).isEmpty()) continue
            val key = dedupeKey(ad)
            if (!seen.add(key)) continue
            out.put(ad)
        }
        return out
    }

    private fun dedupeKey(ad: JSONObject): String {
        val code = landCode(ad)
        val created = listOf("created_at", "published_at", "created")
            .map { ad.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() && it != "null" }
            .orEmpty()
        if (code.isNotBlank()) {
            return if (created.isNotEmpty()) "$code|$created" else code
        }
        return listOf(title(ad), priceLine(ad), locationLine(ad), imageUrl(ad)).joinToString("|")
    }

    private fun priceToman(ad: JSONObject): Long {
        val direct = longField(ad, "price_toman")
        if (direct > 0L) return direct
        val total = longField(ad, "price_total")
        if (total > 0L) return total
        return SearchAds.parseToman(ad.optString("price")) ?: 0L
    }

    private fun percentField(ad: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            if (!ad.has(key) || ad.isNull(key)) continue
            val value = when (val raw = ad.opt(key)) {
                is Number -> raw.toDouble()
                is String -> {
                    val latin = SearchQuery.normalizeDigits(raw).replace('٫', '.').replace(',', '.')
                    Regex("""\d+(?:\.\d+)?""").find(latin)?.value?.toDoubleOrNull()
                }
                else -> null
            } ?: continue
            if (value in 1.0..100.0) return (value + 0.5).toInt().coerceIn(1, 100)
        }
        return 0
    }

    private fun longField(ad: JSONObject, key: String): Long {
        if (!ad.has(key) || ad.isNull(key)) return 0L
        return when (val raw = ad.opt(key)) {
            is Number -> raw.toLong()
            is String -> PartnerApi.asciiDigits(raw).toLongOrNull() ?: 0L
            else -> ad.optLong(key)
        }
    }

    private val meterRe = Regex(
        """([0-9۰-۹٠-٩]{2,5})\s*متر|([0-9۰-۹٠-٩]{2,5})متری|([0-9۰-۹٠-٩]{2,5})\s*م(?:\s|$)|([0-9۰-۹٠-٩]{2,5})(?=\s*زمین)""",
    )
    private val recencyRe = Regex(
        """(?:امروز|دیروز|پریروز|[0-9۰-۹٠-٩]+\s*(?:ساعت|دقیقه|روز|هفته|ماه)\s*پیش)\s*""",
    )
    private val recentRe = Regex("""ساعت\s*پیش|دقیقه\s*پیش|لحظاتی|امروز|پریروز""")
    private val ageLabelRe = Regex("""پیش|امروز|دیروز|پریروز|ساعت|دقیقه|لحظه|هفته|ماه|سال""")
    private val placeSkip = setOf(
        "زمین", "ویلا", "ویلایی", "ویلاهای", "باغ", "باغچه", "آب", "برق", "سند", "قسط", "قسطی",
        "اقساط", "اقساطی", "آسفالت", "نزدیک", "ساخت", "قابل", "بافت", "داخل", "فروش", "کد",
        "رهگیری", "دار", "وصل", "انشعابات", "انشعاب", "اشتراکات", "چهاردیواری", "چهاردیوار",
        "چهار", "دیواری", "شهرکی", "شهرک", "مجوز", "رسمی", "نقد", "خوشقواره", "متر", "متری",
        "بالا", "مادر", "چک", "فقره", "قطعه", "ویو", "لوکیشن", "سرسبز", "پرشیا", "تومن", "تومان",
        "با", "در", "از", "تا", "به", "یک", "یه", "بده", "بخر", "متنوع", "حال", "منطقه",
        "روستا", "شهر", "بی", "نظیر", "بینظیر", "ساعت", "پیش", "هفته", "ماه", "روز", "دیروز",
        "پریروز", "امروز", "شده", "بااشتراکات", "خوش", "قواره", "ساله", "تاپ", "فقط", "ساختوساز",
        "جاده", "اصلی", "چسب", "چسبیده", "میلیونی", "ماهه", "روزه",
    )
}
