package ir.vinor.app

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * مدل نمایشی «اعلان‌ها» به سبک دیوار — هم‌تراز با app/express_partner/notifications_feed.py وب:
 * نوع اعلان (بازدید/کیف پول/آگهی/عمومی)، گروه روز (امروز/دیروز/قدیمی‌تر به وقت تهران)،
 * زمان کوتاه فارسی، لینک امن داخلی، شمارش خوانده‌نشده و فیلتر «همه / خوانده‌نشده».
 */
object NotificationFeed {
    enum class Kind(val label: String) { VISIT("بازدید"), WALLET("کیف پول"), LISTING("آگهی"), GENERAL("اعلان") }
    enum class Day(val label: String) { TODAY("امروز"), YESTERDAY("دیروز"), OLDER("قدیمی‌تر") }
    enum class Tab { ALL, UNREAD }

    data class Row(
        val id: String,
        val title: String,
        val body: String,
        val kind: Kind,
        val unread: Boolean,
        val link: String?,
        val time: String,
        val ts: Long,
        val day: Day,
    )

    data class Section(val day: Day, val rows: List<Row>)
    data class Feed(val sections: List<Section>, val total: Int, val unread: Int)

    private val TEHRAN: TimeZone = TimeZone.getTimeZone("GMT+03:30") // بدون ساعت تابستانی از ۱۴۰۱
    private val MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )
    private val WALLET_URL = listOf("/wallet", "/commissions", "/withdraw")
    private val WALLET_WORDS = listOf("کیف پول", "پورسانت", "کمیسیون", "برداشت", "واریز")
    private val LISTING_URL = listOf("/lands/", "/divar/", "/listings", "/my-property", "/special-listings", "/special_listings")
    private val LISTING_WORDS = listOf("آگهی", "فایل")
    private val VINOR_HOSTS = listOf("https://vinor.ir", "https://www.vinor.ir")

    private fun str(n: JSONObject, key: String): String =
        if (n.isNull(key)) "" else n.optString(key, "").trim()

    fun kindOf(n: JSONObject): Kind {
        val t = str(n, "type").lowercase()
        val s = str(n, "source").lowercase()
        val u = str(n, "action_url").lowercase()
        val title = str(n, "title")
        if (t == "visit" || s.startsWith("visit") || "tab=visits" in u || "بازدید" in title) return Kind.VISIT
        if (t in setOf("wallet", "commission", "withdraw", "deposit") || WALLET_URL.any { it in u } ||
            WALLET_WORDS.any { it in title }
        ) return Kind.WALLET
        if (str(n, "ad_id").isNotEmpty() || LISTING_URL.any { it in u } || LISTING_WORDS.any { it in title }) return Kind.LISTING
        return Kind.GENERAL
    }

    /** فقط مسیر داخلی وینور؛ لینک بیرونی یا `//host` نادیده گرفته می‌شود. */
    fun safeLink(raw: String?): String? {
        var url = raw?.trim().orEmpty()
        if (url.isEmpty() || url == "null") return null
        val low = url.lowercase()
        for (host in VINOR_HOSTS) {
            if (low == host || low.startsWith("$host/")) {
                url = url.substring(host.length).ifEmpty { "/" }
                break
            }
        }
        return if (url.startsWith("/") && !url.startsWith("//") && '\\' !in url) url else null
    }

    /** created_at سرور ثانیهٔ یونیکس است؛ میلی‌ثانیه و ISO هم پذیرفته می‌شود. */
    fun epochOf(n: JSONObject): Long? {
        if (!n.has("created_at") || n.isNull("created_at")) return null
        val raw = n.opt("created_at")
        val num = when (raw) {
            is Number -> raw.toDouble()
            else -> raw.toString().trim().toDoubleOrNull()
        }
        if (num != null) {
            val v = if (num > 1e12) num / 1000.0 else num
            return if (v > 0) v.toLong() else null
        }
        return parseIso(raw.toString().trim())
    }

    private fun parseIso(s: String): Long? {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?""").find(s) ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val g = m.groupValues
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        return cal.timeInMillis / 1000
    }

    private fun tehranDay(ts: Long): Calendar = Calendar.getInstance(TEHRAN).apply { timeInMillis = ts * 1000 }

    /** تعداد روز تقویمی (به وقت تهران) بین ts و now. */
    private fun daysBetween(ts: Long, now: Long): Int {
        fun dayNumber(c: Calendar): Long {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            utc.clear()
            utc.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            return utc.timeInMillis / 86_400_000L
        }
        return (dayNumber(tehranDay(now)) - dayNumber(tehranDay(minOf(ts, now)))).toInt()
    }

    fun dayOf(ts: Long?, now: Long): Day {
        if (ts == null) return Day.OLDER
        return when (daysBetween(ts, now)) {
            in Int.MIN_VALUE..0 -> Day.TODAY
            1 -> Day.YESTERDAY
            else -> Day.OLDER
        }
    }

    fun faDigits(raw: Any): String =
        raw.toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

    fun shortTime(ts: Long?, now: Long): String {
        if (ts == null) return ""
        val delta = now - ts
        if (delta < 60) return "همین حالا"
        if (delta < 3600) return "${faDigits(delta / 60)} دقیقه پیش"
        return when (dayOf(ts, now)) {
            Day.TODAY -> "${faDigits(delta / 3600)} ساعت پیش"
            Day.YESTERDAY -> "دیروز"
            Day.OLDER -> {
                val days = daysBetween(ts, now)
                if (days < 7) return "${faDigits(days)} روز پیش"
                val c = tehranDay(ts)
                val j = LandVisit.toJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
                val n = tehranDay(now)
                val ny = LandVisit.toJalali(n.get(Calendar.YEAR), n.get(Calendar.MONTH) + 1, n.get(Calendar.DAY_OF_MONTH))[0]
                val label = "${faDigits(j[2])} ${MONTHS[j[1] - 1]}"
                if (j[0] == ny) label else "$label ${faDigits(j[0])}"
            }
        }
    }

    fun rowOf(n: JSONObject, now: Long): Row {
        val ts = epochOf(n)
        return Row(
            id = str(n, "id"),
            title = str(n, "title").ifEmpty { "اعلان جدید" },
            body = str(n, "body").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" "),
            kind = kindOf(n),
            unread = !n.optBoolean("is_read", false),
            link = safeLink(if (n.isNull("action_url")) null else n.optString("action_url")),
            time = shortTime(ts, now),
            ts = ts ?: 0L,
            day = dayOf(ts, now),
        )
    }

    fun build(items: List<JSONObject>, now: Long, tab: Tab = Tab.ALL): Feed {
        val rows = items
            .filter { it.optString("source").trim() != VisitAudience.PLUS_VISIT_SOURCE }
            .map { rowOf(it, now) }
            .sortedByDescending { it.ts }
        val shown = if (tab == Tab.UNREAD) rows.filter { it.unread } else rows
        val sections = Day.values().mapNotNull { d ->
            shown.filter { it.day == d }.takeIf { it.isNotEmpty() }?.let { Section(d, it) }
        }
        return Feed(sections, rows.size, rows.count { it.unread })
    }

    /** کدام حالت خالی: «هیچ اعلانی نداری» یا «همه‌ی اعلان‌ها خوانده شدن» (یا هیچ). */
    fun emptyState(feed: Feed, tab: Tab): Pair<String, String>? = when {
        feed.total == 0 -> EMPTY_ALL
        tab == Tab.UNREAD && feed.unread == 0 -> EMPTY_UNREAD
        else -> null
    }

    val EMPTY_ALL = "هیچ اعلانی نداری" to "اعلان‌های بازدید، کیف پول و آگهی‌هایت اینجا نشان داده می‌شوند."
    val EMPTY_UNREAD = "همه‌ی اعلان‌ها خوانده شدن" to "اعلان تازه‌ای نداری."
    const val POSTCHI_PATH = "/express/partner/home?ai=1&thread=__postchi__"
}
