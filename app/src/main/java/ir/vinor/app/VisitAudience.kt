package ir.vinor.app

import org.json.JSONObject

/** بازدیدِ دریافتی و اعلانش فقط وقتی مال همین کاربر است که شمارهٔ مالک یا گیرنده یکی باشد. */
internal object VisitAudience {
    const val PLUS_VISIT_SOURCE = "visit_plus_user"

    fun samePhone(left: String, right: String): Boolean {
        val a = canonical(left)
        val b = canonical(right)
        return a.isNotEmpty() && a == b
    }

    fun canonical(raw: String): String {
        val normalized = LandVisit.normalizePhone(raw)
        return if (LandVisit.isValidPhone(normalized)) normalized else ""
    }

    fun accountMismatch(sessionPhone: String, registeredPhone: String): Boolean {
        val session = canonical(sessionPhone)
        val registered = canonical(registeredPhone)
        return session.isNotEmpty() && registered.isNotEmpty() && session != registered
    }

    fun showNotification(item: JSONObject, viewerPhone: String): Boolean {
        if (item.optString("source").trim() == PLUS_VISIT_SOURCE) return false
        val recipient = first(item, "user_id", "recipient", "owner_phone")
        if (recipient.isBlank()) return !isVisitNotification(item)
        return samePhone(recipient, viewerPhone)
    }

    fun showPush(
        data: Map<String, String>,
        sessionPhone: String,
        registeredPhone: String,
    ): Boolean {
        val recipient = listOf("owner_phone", "user_id", "recipient", "owner", "phone")
            .firstNotNullOfOrNull { key -> data[key]?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        if (recipient.isNotBlank()) return samePhone(recipient, sessionPhone)
        if (accountMismatch(sessionPhone, registeredPhone)) return false
        if (isVisitPush(data)) return samePhone(sessionPhone, registeredPhone)
        return true
    }

    private fun isVisitNotification(item: JSONObject): Boolean {
        val source = item.optString("source")
        return item.optString("type") == "visit" ||
            source.startsWith("visit") ||
            item.optString("title").contains("بازدید")
    }

    fun isVisitPush(data: Map<String, String>): Boolean {
        val kind = "${data["type"].orEmpty()} ${data["source"].orEmpty()}"
        if (kind.contains("visit")) return true
        val text = "${data["title"].orEmpty()} ${data["body"].orEmpty()}"
        if (text.contains("بازدید")) return true
        return data["url"].orEmpty().contains("/requests")
    }

    private fun first(item: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = item.optString(key).trim()
            if (value.isNotEmpty() && !value.equals("null", true)) return value
        }
        return ""
    }
}
