package ir.vinor.app

import org.json.JSONArray

/** جزئیات ملک نسبت به نقش بیننده. مهمان، همکار واردشده، یا مالک. */
object LandDetailRole {
    enum class Audience { GUEST, PARTNER, OWNER }

    enum class StickyKind { CALL, DIVAR, MANAGE }

    data class Sticky(val kind: StickyKind, val label: String)

    private val highlightLabels = listOf("متراژ", "نوع", "کاربری", "نحوه پرداخت", "شکل زمین", "دیوارکشی")

    fun audience(hasSession: Boolean, isMine: Boolean): Audience = when {
        hasSession && isMine -> Audience.OWNER
        hasSession -> Audience.PARTNER
        else -> Audience.GUEST
    }

    fun place(city: String, location: String): String {
        val town = city.trim()
        val area = location.trim()
        return when {
            town.isNotEmpty() && area.isNotEmpty() && town != area -> "$town، $area"
            town.isNotEmpty() -> town
            else -> area
        }
    }

    /** متای خام کد فایل برای مهمان پنهان است؛ اگر محله خالی باشد همان متا می‌ماند. */
    fun showMeta(hasSession: Boolean, place: String, meta: String): Boolean {
        if (meta.isBlank()) return false
        if (place.isBlank()) return true
        return hasSession
    }

    fun showCommission(hasSession: Boolean, amount: Int): Boolean = hasSession && amount > 0

    /** جزئیات فقط فرم انتشار را نشان می‌دهد. توقف انتشار در مدیریت ملک است. */
    fun showPublish(hasSession: Boolean, canPublish: Boolean): Boolean = hasSession && canPublish

    fun badge(
        hasSession: Boolean,
        isMine: Boolean,
        isDivar: Boolean,
        ownerBadge: String,
        published: Boolean,
    ): String {
        if (isDivar) return "دیوار"
        val server = ownerBadge.trim()
        if (server.isNotEmpty()) return server
        if (hasSession && isMine) return if (published) "منتشرشده" else "خصوصی"
        return ""
    }

    fun sticky(
        audience: Audience,
        isDivar: Boolean,
        showDivar: Boolean,
        hasDivarUrl: Boolean,
    ): Sticky {
        val external = isDivar || showDivar || hasDivarUrl
        if (external && audience != Audience.OWNER) return Sticky(StickyKind.DIVAR, "دیدن در دیوار")
        return when (audience) {
            Audience.GUEST, Audience.PARTNER -> Sticky(StickyKind.CALL, "تماس")
            Audience.OWNER -> Sticky(StickyKind.MANAGE, "مدیریت ملک")
        }
    }

    /** شماره‌ای که خود آگهی برای تماس می‌دهد (call_phone)، وگرنه خط بازدید وینور. */
    fun callPhone(raw: String?): String {
        val p = raw.orEmpty().trim()
        return if (p.isNotEmpty()) p else "09121471301"
    }

    fun stickyHint(audience: Audience, published: Boolean): String {
        if (audience != Audience.OWNER) return ""
        return if (published) "منتشرشده" else "خصوصی"
    }

    /** نوار کوتاه ویژگی، از ردیف‌های واقعی آگهی. */
    fun highlights(rows: JSONArray): List<String> {
        val byLabel = LinkedHashMap<String, String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val label = row.optString("label").trim()
            val value = row.optString("value").trim()
            if (label.isEmpty() || value.isEmpty()) continue
            if (label in highlightLabels && label !in byLabel) byLabel[label] = value
        }
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (label in highlightLabels) {
            val value = byLabel[label] ?: continue
            val text = if (label == "متراژ" && !value.contains("متر")) "$value متر" else value
            if (!seen.add(text)) continue
            out += text
            if (out.size == 4) break
        }
        return out
    }
}
