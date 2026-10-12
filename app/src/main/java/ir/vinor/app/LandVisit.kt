package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * تعیین وقت بازدید در جزئیات ملک، همان قواعد صفحهٔ وب:
 * تاریخ از امروز تا ۹۰ روز، ساعت مجاز تهران، نام و موبایل مشتری.
 */
object LandVisit {
    const val LABEL = "بازدید"
    const val TITLE = "تعیین وقت بازدید"
    const val SENT = "درخواست بازدید ارسال شد"
    const val UNAVAILABLE = "امکان ثبت درخواست بازدید برای این ملک وجود ندارد."
    const val NEED_DATE = "لطفاً تاریخ بازدید را انتخاب کنید."

    data class Schedule(
        val requestUrl: String = "",
        val statusUrl: String = "",
        val completeUrl: String = "",
        val csrf: String = "",
        val mode: String = "",
        val canRevisit: Boolean = false,
        val visitDate: String = "",
        val visitDateJalali: String = "",
        val minDate: String = "",
        val weekdays: Set<Int> = emptySet(),
        val hours: List<Int> = emptyList(),
    )

    data class Customer(val first: String, val last: String, val phone: String)

    sealed class Outcome {
        data class Ready(val schedule: Schedule) : Outcome()
        data object Login : Outcome()
        data object Offline : Outcome()
        data class Blocked(val message: String) : Outcome()
        data object Unavailable : Outcome()
    }

    data class DayCell(
        val iso: String,
        val label: String,
        val enabled: Boolean,
        val today: Boolean,
        val selected: Boolean,
    )

    fun showBesideChat(
        audience: LandDetailRole.Audience,
        isDivar: Boolean,
        showDivar: Boolean,
        hasDivarUrl: Boolean,
    ): Boolean {
        val external = isDivar || showDivar || hasDivarUrl
        if (external && audience != LandDetailRole.Audience.OWNER) return false
        return audience == LandDetailRole.Audience.PARTNER || audience == LandDetailRole.Audience.GUEST
    }

    fun pagePath(code: String): String = "/express/partner/lands/${code.trim()}"

    fun statusPath(code: String): String = "/express/partner/lands/${code.trim()}/tx-status"

    /** مسیر ثبت، مستقل از HTML قدیمی جزئیات که دیگر دکمهٔ بازدید ندارد. */
    fun requestPath(code: String): String {
        val clean = code.trim()
        if (clean.isEmpty()) return ""
        val encoded = java.net.URLEncoder.encode(clean, Charsets.UTF_8.name()).replace("+", "%20")
        return "/express/partner/visit-requests/$encoded"
    }

    /**
     * وضعیت معامله همان منبع تقویم است. صفحهٔ HTML جزئیات دیگر آدرس ثبت را ندارد.
     */
    fun decide(landCode: String, status: JSONObject?): Outcome {
        val obj = status ?: JSONObject()
        if (obj.optBoolean("offline") || obj.optInt("http_status") == 0) return Outcome.Offline
        if (needsLogin(obj)) return Outcome.Login
        if (!obj.optBoolean("success")) {
            val message = obj.optString("message").trim()
            return if (message.isNotEmpty() && message != "Not Found") {
                Outcome.Blocked(message)
            } else {
                Outcome.Unavailable
            }
        }
        val schedule = fromStatus(landCode, obj)
        if (!canSchedule(schedule.mode, schedule.canRevisit)) {
            return Outcome.Blocked(blockedMessage(schedule))
        }
        if (schedule.requestUrl.isBlank()) return Outcome.Unavailable
        return Outcome.Ready(schedule)
    }

    fun fromStatus(landCode: String, status: JSONObject): Schedule {
        val url = status.optString("visit_request_url").trim().ifBlank { requestPath(landCode) }
        return Schedule(
            requestUrl = url,
            statusUrl = statusPath(landCode),
            csrf = status.optString("csrf_token").trim(),
            mode = status.optString("tx_mode").trim(),
            canRevisit = if (status.has("can_revisit")) status.optBoolean("can_revisit") else false,
            visitDate = status.optString("visit_date").trim(),
            visitDateJalali = status.optString("visit_date_jalali").trim(),
            minDate = status.optString("visit_date_min").trim(),
            weekdays = intSet(status.optJSONArray("visit_allowed_weekdays")),
            hours = intList(status.optJSONArray("visit_allowed_hours")),
        )
    }

    private fun needsLogin(obj: JSONObject): Boolean {
        if (obj.optBoolean("session_expired") || obj.optInt("http_status") == 401) return true
        return FollowRequests.isUnauthorized(obj)
    }

    fun parsePage(html: String): Schedule {
        val config = configObject(html)
        val weekdays = intSet(config?.optJSONArray("visitAllowedWeekdays"))
        val hours = intList(config?.optJSONArray("visitAllowedHours"))
        return Schedule(
            requestUrl = attr(html, "data-visit-request-url").ifBlank {
                text(config, "visitRequestUrl")
            },
            statusUrl = attr(html, "data-tx-status-url").ifBlank { text(config, "txStatusUrl") },
            completeUrl = attr(html, "data-visit-complete-url").ifBlank { text(config, "visitCompleteUrl") },
            csrf = attr(html, "data-csrf").ifBlank {
                text(config, "csrfToken").ifBlank { PartnerCustomers.csrfFromHtml(html) }
            },
            mode = attr(html, "data-land-tx-mode").ifBlank { text(config, "txMode") },
            canRevisit = flag(config, "canRevisit") || attr(html, "data-can-revisit") == "1",
            visitDate = attr(html, "data-visit-date").ifBlank { text(config, "visitDate") },
            visitDateJalali = text(config, "visitDateJalali"),
            minDate = text(config, "visitDateMin"),
            weekdays = weekdays,
            hours = hours,
        )
    }

    fun mergeStatus(page: Schedule, status: JSONObject?): Schedule {
        if (status == null || !status.optBoolean("success")) return page
        val mode = status.optString("tx_mode").trim().ifBlank { page.mode }
        val date = status.optString("visit_date").trim().ifBlank { page.visitDate }
        val jalali = status.optString("visit_date_jalali").trim().ifBlank { page.visitDateJalali }
        val url = status.optString("visit_request_url").trim().ifBlank { page.requestUrl }
        val revisit = if (status.has("can_revisit")) status.optBoolean("can_revisit") else page.canRevisit
        return page.copy(
            requestUrl = url,
            mode = mode,
            canRevisit = revisit,
            visitDate = date,
            visitDateJalali = jalali,
        )
    }

    fun canSchedule(mode: String, canRevisit: Boolean): Boolean {
        val key = mode.trim()
        if (key.isEmpty() || key == "visit" || key == "revisit") return true
        if (!canRevisit) return false
        return key == "deal" || key == "visit_no_show"
    }

    fun blockedMessage(schedule: Schedule): String {
        val hint = schedule.visitDateJalali.ifBlank { display(schedule.visitDate) }
        val extra = if (hint.isBlank()) "" else " بازدید: $hint"
        return when (schedule.mode.trim()) {
            "visit_pending" -> "درخواست بازدید نهایی شده است.$extra"
            "visit_done_pending" -> "بازدید انجام شد ثبت شده و منتظر تأیید ادمین است."
            "visit_commission_pending" -> "بازدید تأیید شده — می‌توانید درخواست معامله ثبت کنید."
            "visit_done" -> "بازدید این ملک ثبت شده است.$extra"
            "deal_pending" -> "درخواست معامله ثبت شد و منتظر تأیید ادمین است."
            "deal", "release" -> "ابتدا رفع معامله را انجام دهید. برای ثبت بازدید مجدد، از گزینه «رفع معامله» استفاده کنید."
            "visit_no_show" -> "تا زمان تأیید، امکان ثبت بازدید جدید وجود ندارد."
            "hidden" -> "برای ثبت درخواست بازدید باید حساب همکاری شما تأیید شده باشد."
            "blocked" -> "این فایل در حال حاضر توسط همکار دیگری در حال معامله است."
            else -> UNAVAILABLE
        }
    }

    /** همان normalizeIranMobileInput وب: فاصله و خط تیره، ارقام فارسی، +98 و 0098. */
    fun normalizePhone(raw: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        val ar = "٠١٢٣٤٥٦٧٨٩"
        val sb = StringBuilder()
        for (ch in raw.trim()) {
            if (ch == ' ' || ch == '-' || ch == '−') continue
            val fi = fa.indexOf(ch)
            if (fi >= 0) {
                sb.append(fi)
                continue
            }
            val ai = ar.indexOf(ch)
            if (ai >= 0) {
                sb.append(ai)
                continue
            }
            sb.append(ch)
        }
        var s = sb.toString()
        if (s.startsWith("+98")) s = "0" + s.drop(3)
        if (s.startsWith("0098")) s = "0" + s.drop(4)
        if (s.startsWith("98") && s.length == 12) s = "0" + s.drop(2)
        return s
    }

    fun isValidPhone(raw: String): Boolean = normalizePhone(raw).matches(Regex("^09\\d{9}$"))

    fun customerError(first: String, last: String, phone: String): String {
        if (first.trim().isEmpty()) return "نام مشتری را وارد کنید."
        if (last.trim().isEmpty()) return "نام خانوادگی مشتری را وارد کنید."
        if (phone.trim().isEmpty()) return "شماره تماس مشتری را وارد کنید."
        if (!isValidPhone(phone)) return "شماره تماس مشتری معتبر نیست."
        return ""
    }

    fun customer(first: String, last: String, phone: String): Customer =
        Customer(first.trim(), last.trim(), normalizePhone(phone))

    fun formFields(dateIso: String, customer: Customer): Map<String, String> = linkedMapOf(
        "visit_date" to dateIso,
        "customer_first_name" to customer.first,
        "customer_last_name" to customer.last,
        "customer_phone" to customer.phone,
    )

    fun successMessage(payload: JSONObject, selectedIso: String = ""): String {
        val booked = payload.optString("visit_date").trim()
        if (selectedIso.isNotBlank() && booked.isNotBlank() && booked != selectedIso) {
            val label = payload.optString("visit_date_jalali").trim().ifBlank { display(booked) }
            return "نوبت همان روز پر بود. بازدید برای $label ثبت شد."
        }
        val message = payload.optString("message").trim()
        if (message.isNotEmpty()) return message
        return SENT
    }

    fun isOk(payload: JSONObject): Boolean {
        val code = payload.optInt("http_status", 200)
        if (code !in 200..299) return false
        if (payload.optBoolean("offline")) return false
        return payload.optBoolean("success")
    }

    /** وب فقط خانه و داشبورد را بعد از ثبت نادیده می‌گیرد. */
    fun shouldFollowRedirect(url: String): Boolean {
        val raw = url.trim()
        if (raw.isEmpty()) return false
        val noQuery = raw.substringBefore('?').substringBefore('#')
        val path = if ("://" in noQuery) {
            val after = noQuery.substringAfter("://")
            val slash = after.indexOf('/')
            if (slash < 0) "/" else after.substring(slash)
        } else if (noQuery.startsWith("/")) {
            noQuery
        } else {
            "/$noQuery"
        }
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        return normalized != "/express/partner/home" && normalized != "/express/partner/dashboard"
    }

    fun failureMessage(payload: JSONObject): String {
        if (payload.optBoolean("offline")) {
            return payload.optString("message").trim().ifBlank { "برای انجام این عملیات اینترنت لازم است." }
        }
        val message = payload.optString("message").trim()
        if (message.isNotEmpty() && message != "Not Found") return message
        if (payload.optInt("http_status") == 401 || payload.optString("error") == "unauthorized") {
            return "برای ادامه وارد شوید."
        }
        return "خطا در ثبت بازدید"
    }

    fun todayIso(now: Calendar = tehranNow()): String = isoOf(now)

    fun bounds(minIso: String, todayIso: String): Pair<String, String> {
        val start = minIso.trim().ifBlank { todayIso }
        val cal = calendarOf(start)
        cal.add(Calendar.DAY_OF_MONTH, 90)
        return start to isoOf(cal)
    }

    fun allows(
        iso: String,
        minIso: String,
        maxIso: String,
        weekdays: Set<Int>,
        hours: List<Int>,
        todayIso: String,
        tehranHour: Int,
    ): Boolean {
        if (iso.isBlank() || iso < minIso || iso > maxIso) return false
        val day = calendarOf(iso)
        val js = day.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        if (weekdays.isNotEmpty() && js !in weekdays) return false
        if (hours.isEmpty()) return true
        if (iso > todayIso) return true
        if (iso < todayIso) return false
        return hours.any { it > tehranHour }
    }

    fun monthCells(
        jalaliYear: Int,
        jalaliMonth: Int,
        selectedIso: String,
        minIso: String,
        maxIso: String,
        weekdays: Set<Int>,
        hours: List<Int>,
        todayIso: String,
        tehranHour: Int,
    ): List<DayCell?> {
        val first = calendarOf(isoFromJalali(jalaliYear, jalaliMonth, 1))
        val lead = if (first.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) {
            0
        } else {
            first.get(Calendar.DAY_OF_WEEK)
        }
        val length = monthLength(jalaliYear, jalaliMonth)
        val cells = ArrayList<DayCell?>(42)
        repeat(lead) { cells.add(null) }
        for (day in 1..length) {
            val iso = isoFromJalali(jalaliYear, jalaliMonth, day)
            cells.add(
                DayCell(
                    iso = iso,
                    label = persianDigits(day.toString()),
                    enabled = allows(iso, minIso, maxIso, weekdays, hours, todayIso, tehranHour),
                    today = iso == todayIso,
                    selected = iso == selectedIso,
                ),
            )
        }
        return cells
    }

    fun shiftMonth(jalaliYear: Int, jalaliMonth: Int, delta: Int): Pair<Int, Int> {
        var y = jalaliYear
        var m = jalaliMonth + delta
        while (m < 1) {
            m += 12
            y -= 1
        }
        while (m > 12) {
            m -= 12
            y += 1
        }
        return y to m
    }

    fun jalaliOf(iso: String): IntArray {
        val cal = calendarOf(iso)
        return toJalali(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    fun display(iso: String): String {
        if (iso.isBlank()) return ""
        val j = jalaliOf(iso)
        return persianDigits("%04d/%02d/%02d".format(j[0], j[1], j[2]))
    }

    fun monthTitle(jalaliYear: Int, jalaliMonth: Int): String {
        val name = MONTHS.getOrElse(jalaliMonth - 1) { "" }
        return "$name ${persianDigits(jalaliYear.toString())}"
    }

    fun persianDigits(raw: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        return raw.map { ch -> if (ch in '0'..'9') fa[ch - '0'] else ch }.joinToString("")
    }

    fun tehranHour(now: Calendar = tehranNow()): Int = now.get(Calendar.HOUR_OF_DAY)

    private fun tehranNow(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))

    private fun isoOf(cal: Calendar): String =
        "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )

    private fun calendarOf(iso: String): Calendar {
        val parts = iso.split("-")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        cal.set(Calendar.YEAR, parts[0].toInt())
        cal.set(Calendar.MONTH, parts[1].toInt() - 1)
        cal.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    private fun isoFromJalali(jy: Int, jm: Int, jd: Int): String {
        val g = toGregorian(jy, jm, jd)
        return "%04d-%02d-%02d".format(g[0], g[1], g[2])
    }

    private fun monthLength(jy: Int, jm: Int): Int {
        if (jm in 1..6) return 31
        if (jm in 7..11) return 30
        val leap = toJalali(toGregorian(jy, 12, 30)[0], toGregorian(jy, 12, 30)[1], toGregorian(jy, 12, 30)[2])
        return if (leap[0] == jy && leap[1] == 12 && leap[2] == 30) 30 else 29
    }

    private fun configObject(html: String): JSONObject? {
        val match = Regex(
            """<script\b[^>]*id\s*=\s*["']landDetailConfig["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html) ?: return null
        val raw = unescape(match.groupValues[1].trim())
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun attr(html: String, name: String): String {
        val match = Regex(
            """\b$name\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html) ?: return ""
        return unescape(match.groupValues[1]).trim()
    }

    private fun unescape(raw: String): String =
        raw.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun text(obj: JSONObject?, key: String): String {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return ""
        val value = obj.optString(key).trim()
        return if (value.isBlank() || value.equals("null", true)) "" else value
    }

    private fun flag(obj: JSONObject?, key: String): Boolean {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return false
        return when (val value = obj.opt(key)) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.equals("true", true) || value == "1"
            else -> false
        }
    }

    private fun intSet(array: JSONArray?): Set<Int> {
        if (array == null) return emptySet()
        val out = LinkedHashSet<Int>()
        for (i in 0 until array.length()) {
            val n = array.optInt(i, Int.MIN_VALUE)
            if (n in 0..6) out.add(n)
        }
        return out
    }

    private fun intList(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        val out = ArrayList<Int>()
        for (i in 0 until array.length()) {
            val n = array.optInt(i, Int.MIN_VALUE)
            if (n in 0..23) out.add(n)
        }
        return out
    }

    private val MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    private fun toJalali(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm = if (days < 186) 1 + (days / 31) else 7 + ((days - 186) / 30)
        val jd = 1 + if (days < 186) days % 31 else (days - 186) % 30
        return intArrayOf(jy, jm, jd)
    }

    private fun toGregorian(jy: Int, jm: Int, jd: Int): IntArray {
        val jy2 = jy + 1595
        var days = -355668 + (365 * jy2) + ((jy2 / 33) * 8) + (((jy2 % 33) + 3) / 4) + jd +
            if (jm < 7) (jm - 1) * 31 else ((jm - 7) * 30) + 186
        var gy = 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            gy += 100 * (--days / 36524)
            days %= 36524
            if (days >= 365) days++
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        var gd = days + 1
        val leap = gy % 4 == 0 && (gy % 100 != 0 || gy % 400 == 0)
        val sal = intArrayOf(0, 31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 0
        while (gm < 13 && gd > sal[gm]) {
            gd -= sal[gm]
            gm++
        }
        return intArrayOf(gy, gm, gd)
    }
}
