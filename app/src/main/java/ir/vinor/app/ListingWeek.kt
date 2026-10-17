package ir.vinor.app

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * آمار هفتهٔ اول یک آگهی، همان فیلدهای `apk_my_property_data`.
 * عدد نمودار، تغییر قیمت، ارزش خالص و میانگین محله خوانده نمی‌شود.
 */
object ListingWeek {
    const val TITLE = "هفتهٔ اول"
    const val ACTIVITY_TITLE = "فعالیت"
    const val UNPUBLISHED =
        "هنوز برای فروش منتشر نشده. بعد از انتشار، بازدید همکاران و درخواست بازدید اینجا می‌آید."

    private val tehran = TimeZone.getTimeZone("Asia/Tehran")
    private val stamp = Regex(
        """^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?)?$""",
    )
    private val keys = listOf(
        "weekly_views",
        "views_since_publish",
        "visit_requests_7d",
        "visit_requests_total",
        "published_at",
        "first_published_at",
        "days_since_publish",
        "activity",
    )

    data class Event(
        val type: String,
        val text: String,
        val date: String,
    )

    data class Stats(
        val available: Boolean,
        val weeklyViews: Int,
        val viewsSincePublish: Int,
        val visitRequests7d: Int,
        val visitRequestsTotal: Int,
        val publishedAt: String?,
        val firstPublishedAt: String?,
        val daysSincePublish: Int?,
        val activity: List<Event>,
    )

    data class Line(val label: String, val value: String)

    data class ActivityLine(val text: String, val whenLabel: String)

    data class ViewModel(
        val available: Boolean,
        val title: String,
        val published: Boolean,
        val statusText: String,
        val lines: List<Line>,
        val hint: String,
        val activity: List<ActivityLine>,
    )

    fun parse(raw: JSONObject?): Stats {
        if (raw == null || !hasFields(raw)) return emptyStats()
        return Stats(
            available = true,
            weeklyViews = count(raw, "weekly_views"),
            viewsSincePublish = count(raw, "views_since_publish"),
            visitRequests7d = count(raw, "visit_requests_7d"),
            visitRequestsTotal = count(raw, "visit_requests_total"),
            publishedAt = date(raw, "published_at"),
            firstPublishedAt = date(raw, "first_published_at"),
            daysSincePublish = optionalCount(raw, "days_since_publish"),
            activity = activity(raw),
        )
    }

    fun present(raw: JSONObject?): ViewModel = present(parse(raw))

    fun present(stats: Stats): ViewModel {
        if (!stats.available) {
            return ViewModel(false, TITLE, false, "", emptyList(), "", emptyList())
        }
        val events = stats.activity.mapNotNull { event ->
            val text = event.text.ifBlank { fallbackText(event.type) }
            if (text.isBlank()) null else ActivityLine(persianDigits(text), formatWhen(event.date))
        }
        if (stats.publishedAt == null) {
            return ViewModel(true, TITLE, false, UNPUBLISHED, dateLines(stats), "", events)
        }
        val lines = ArrayList<Line>()
        lines.add(Line("بازدید همکاران در ۷ روز", persianDigits(stats.weeklyViews)))
        lines.add(Line("بازدید همکاران از انتشار", persianDigits(stats.viewsSincePublish)))
        lines.add(Line("درخواست بازدید در ۷ روز", persianDigits(stats.visitRequests7d)))
        lines.add(Line("درخواست بازدید از انتشار", persianDigits(stats.visitRequestsTotal)))
        val days = stats.daysSincePublish
        if (days != null) lines.add(Line("روز از انتشار", persianDigits(days)))
        lines.addAll(dateLines(stats))
        val hint = if (days != null && days >= 3 && stats.visitRequestsTotal == 0) {
            "${persianDigits(days)} روز از انتشار گذشته و هنوز درخواست بازدید نیست. عکس اضافه کنید یا پورسانت را مشخص کنید."
        } else {
            ""
        }
        return ViewModel(true, TITLE, true, "", lines, hint, events)
    }

    private fun dateLines(stats: Stats): List<Line> {
        val lines = ArrayList<Line>()
        val published = stats.publishedAt?.let(::formatWhen).orEmpty()
        if (published.isNotBlank()) lines.add(Line("تاریخ انتشار", published))
        val first = stats.firstPublishedAt?.let(::formatWhen).orEmpty()
        if (first.isNotBlank()) lines.add(Line("اولین انتشار", first))
        return lines
    }

    /** فقط کلیدهایی که در مبدأ هستند روی کارت می‌نشینند. نبودن کلید، مقدار قبلی را پاک نمی‌کند. */
    fun copyOnto(target: JSONObject, source: JSONObject?) {
        if (source == null) return
        for (key in keys) {
            if (source.has(key)) target.put(key, source.opt(key))
        }
    }

    fun formatWhen(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return ""
        val civil = civil(text) ?: return persianDigits(text)
        val day = LandVisit.display("%04d-%02d-%02d".format(civil.year, civil.month, civil.day))
        if (!civil.hasTime) return day
        val clock = persianDigits("%02d:%02d".format(civil.hour, civil.minute))
        return "$day · $clock"
    }

    fun persianDigits(value: Int): String = persianDigits(value.toString())

    fun persianDigits(latin: String): String = buildString(latin.length) {
        for (ch in latin) {
            append(
                when (ch) {
                    in '0'..'9' -> "۰۱۲۳۴۵۶۷۸۹"[ch - '0']
                    else -> ch
                },
            )
        }
    }

    private fun emptyStats() = Stats(false, 0, 0, 0, 0, null, null, null, emptyList())

    private fun hasFields(obj: JSONObject): Boolean = keys.any { obj.has(it) }

    private fun count(obj: JSONObject, key: String): Int = optionalCount(obj, key) ?: 0

    private fun optionalCount(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val parsed = when (val raw = obj.opt(key)) {
            is Number -> {
                val value = raw.toDouble()
                if (value.isNaN() || value.isInfinite()) null else value.toInt()
            }
            is String -> {
                val latin = latinDigits(raw).trim()
                if (latin.isEmpty() || latin.equals("null", ignoreCase = true)) null
                else latin.toDoubleOrNull()?.toInt()
            }
            else -> null
        } ?: return null
        return if (parsed < 0) 0 else parsed
    }

    private fun date(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val raw = obj.opt(key)
        if (raw !is String) return null
        val text = raw.trim()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return null
        return text
    }

    private fun activity(obj: JSONObject): List<Event> {
        if (!obj.has("activity") || obj.isNull("activity")) return emptyList()
        val rows = obj.optJSONArray("activity") ?: return emptyList()
        val out = ArrayList<Event>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            out.add(
                Event(
                    type = text(row, "type"),
                    text = text(row, "text"),
                    date = text(row, "date"),
                ),
            )
        }
        return out
    }

    private fun text(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val raw = obj.opt(key)
        if (raw !is String) return ""
        val value = raw.trim()
        return if (value.equals("null", ignoreCase = true)) "" else value
    }

    private fun fallbackText(type: String): String = when (type.trim().lowercase()) {
        "publish" -> "برای فروش منتشر شد"
        "view" -> "بازدید همکار"
        "visit" -> "درخواست بازدید"
        else -> ""
    }

    private fun latinDigits(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                in '۰'..'۹' -> append('0' + (ch - '۰'))
                in '٠'..'٩' -> append('0' + (ch - '٠'))
                ',', '،', '٬', ' ' -> Unit
                else -> append(ch)
            }
        }
    }

    private data class Civil(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val hasTime: Boolean,
    )

    private fun civil(text: String): Civil? {
        val match = stamp.matchEntire(text) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        if (match.groupValues[4].isEmpty()) return Civil(year, month, day, 0, 0, false)
        val hour = match.groupValues[4].toIntOrNull() ?: return null
        val minute = match.groupValues[5].toIntOrNull() ?: return null
        val second = match.groupValues[6].toIntOrNull() ?: 0
        val cal = Calendar.getInstance(zoneOf(match.groupValues[7]))
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, day)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, second)
        cal.set(Calendar.MILLISECOND, 0)
        cal.get(Calendar.YEAR)
        cal.timeZone = tehran
        return Civil(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            true,
        )
    }

    private fun zoneOf(raw: String): TimeZone {
        if (raw.isEmpty() || raw == "Z") return TimeZone.getTimeZone("UTC")
        val body = if (raw.length == 5 && (raw[0] == '+' || raw[0] == '-')) {
            raw.substring(0, 3) + ":" + raw.substring(3)
        } else {
            raw
        }
        return TimeZone.getTimeZone("GMT$body")
    }
}
