package ir.vinor.app

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * شمارش معکوس توقف خودکار انتشار.
 * مهلت از sale_publish_expires_at است؛ اگر نباشد از sale_publish_remaining_seconds نسبت به زمان دریافت.
 */
object SalePublishCountdown {
    const val EXPIRES_AT = "sale_publish_expires_at"
    const val REMAINING_SECONDS = "sale_publish_remaining_seconds"
    const val STOPPED = "انتشار متوقف شده"
    const val TICK_MS = 60_000L

    private val ISO = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(?:Z|([+-])(\d{2}):?(\d{2}))?$""",
    )

    fun carries(obj: JSONObject?): Boolean {
        if (obj == null) return false
        return obj.has(EXPIRES_AT) || obj.has(REMAINING_SECONDS)
    }

    fun hasSignal(obj: JSONObject?): Boolean = basisKey(obj).isNotEmpty()

    fun basisKey(obj: JSONObject?): String {
        if (obj == null) return ""
        val expires = readExpiresRaw(obj).orEmpty()
        val remaining = readRemaining(obj)?.toString().orEmpty()
        if (expires.isEmpty() && remaining.isEmpty()) return ""
        return "$expires|$remaining"
    }

    fun copyOnto(target: JSONObject, source: JSONObject) {
        if (source.has(EXPIRES_AT)) {
            if (source.isNull(EXPIRES_AT)) target.put(EXPIRES_AT, JSONObject.NULL)
            else target.put(EXPIRES_AT, source.optString(EXPIRES_AT))
        }
        if (source.has(REMAINING_SECONDS)) {
            if (source.isNull(REMAINING_SECONDS)) target.put(REMAINING_SECONDS, JSONObject.NULL)
            else {
                val remaining = readRemaining(source)
                if (remaining == null) target.put(REMAINING_SECONDS, JSONObject.NULL)
                else target.put(REMAINING_SECONDS, remaining)
            }
        }
    }

    /** کارت ملک داخل پاسخ انتشار، اگر فیلد مهلت را داشته باشد. */
    fun propertyClock(payload: JSONObject, code: String): JSONObject? {
        val want = code.trim()
        listOf("items", "listings").forEach { key ->
            val rows = payload.optJSONArray(key) ?: return@forEach
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (want.isNotEmpty()) {
                    val rowCode = row.optString("code").ifBlank { row.optString("id") }.trim()
                    if (rowCode != want) continue
                }
                if (carries(row)) return row
            }
        }
        payload.optJSONObject("property")?.let { prop ->
            if (carries(prop)) {
                val propCode = prop.optString("code").ifBlank { prop.optString("id") }.trim()
                if (want.isEmpty() || propCode.isEmpty() || propCode == want) return prop
            }
        }
        if (carries(payload)) return payload
        return null
    }

    /**
     * null یعنی فیلد مهلت نیست و شمارش نشان داده نمی‌شود.
     * انتشار خاموش یا مهلت تمام‌شده: «انتشار متوقف شده».
     */
    fun label(published: Boolean, payload: JSONObject?, fetchedAtMillis: Long, nowMillis: Long): String? {
        if (payload == null || !hasSignal(payload)) return null
        if (!published) return STOPPED
        val left = remainingMillis(payload, fetchedAtMillis, nowMillis) ?: return null
        if (left <= 0L) return STOPPED
        return countdownText(left)
    }

    fun isLiveCountdown(text: String?): Boolean = !text.isNullOrBlank() && text != STOPPED

    fun countdownText(remainingMillis: Long): String {
        val totalMinutes = (remainingMillis / 60_000L).coerceAtLeast(0L)
        val days = totalMinutes / (24L * 60L)
        val hours = (totalMinutes % (24L * 60L)) / 60L
        val minutes = totalMinutes % 60L
        val parts = ArrayList<String>(3)
        if (days > 0L) parts.add("${fa(days)} روز")
        if (hours > 0L) parts.add("${fa(hours)} ساعت")
        if (minutes > 0L || parts.isEmpty()) parts.add("${fa(minutes)} دقیقه")
        return parts.joinToString(" ") + " تا توقف خودکار"
    }

    fun remainingMillis(payload: JSONObject, fetchedAtMillis: Long, nowMillis: Long): Long? {
        val expires = readExpiresRaw(payload)?.let { parseUtcMillis(it) }
        if (expires != null) return expires - nowMillis
        val remaining = readRemaining(payload) ?: return null
        val fetched = if (fetchedAtMillis > 0L) fetchedAtMillis else nowMillis
        return fetched + remaining * 1000L - nowMillis
    }

    fun parseUtcMillis(raw: String): Long? {
        val match = ISO.matchEntire(raw.trim()) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues[4].toIntOrNull() ?: return null
        val minute = match.groupValues[5].toIntOrNull() ?: return null
        val second = match.groupValues[6].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }
        val fraction = match.groupValues[7]
        val millis = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').take(3).toInt()
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        calendar.set(Calendar.MILLISECOND, millis)
        var utc = calendar.timeInMillis
        val sign = match.groupValues[8]
        if (sign.isNotEmpty()) {
            val offH = match.groupValues[9].toIntOrNull() ?: return null
            val offM = match.groupValues[10].toIntOrNull() ?: return null
            val offset = (offH * 60L + offM) * 60_000L
            utc += if (sign == "+") -offset else offset
        }
        return utc
    }

    private fun readExpiresRaw(obj: JSONObject): String? {
        if (!obj.has(EXPIRES_AT) || obj.isNull(EXPIRES_AT)) return null
        val value = obj.optString(EXPIRES_AT).trim()
        if (value.isBlank() || value == "null") return null
        return value
    }

    private fun readRemaining(obj: JSONObject): Long? {
        if (!obj.has(REMAINING_SECONDS) || obj.isNull(REMAINING_SECONDS)) return null
        return when (val raw = obj.opt(REMAINING_SECONDS)) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
    }

    private fun fa(value: Long): String {
        return value.toString().map { ch ->
            if (ch in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[ch - '0'] else ch
        }.joinToString("")
    }
}
