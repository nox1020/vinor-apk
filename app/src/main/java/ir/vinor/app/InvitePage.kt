package ir.vinor.app

import org.json.JSONObject

/** متن و لینک صفحه دعوت؛ فیلدها همان پاسخ `/express/partner/api/invite/data` هستند. */
object InvitePage {
    const val TITLE = "دعوت"
    const val EMPTY_LINK = "لینک دعوت هنوز آماده نیست"
    const val MISSING = "لینک دعوت هنوز آماده نیست."

    fun lead(approved: Boolean, bonus: Int): String {
        if (!approved) {
            return "لینک زیر را بفرست تا دوستانت راحت‌تر به وینور بپیوندند."
        }
        if (bonus <= 0) {
            return "لینک را برای همکار بفرست. پس از تأیید، پاداش دعوت به موجودی شما اضافه می‌شود."
        }
        return "هر همکار تأییدشده، ${PartnerFormat.toman(bonus)} تومان به موجودی شما اضافه می‌شود."
    }

    fun linkOf(payloadUrl: String, shown: String): String {
        val fromPayload = clean(payloadUrl)
        if (fromPayload.isNotBlank()) return fromPayload
        val fromView = clean(shown)
        if (fromView.isBlank() || fromView == EMPTY_LINK) return ""
        return fromView
    }

    fun shareMessage(shareText: String, url: String): String? {
        val intro = clean(shareText)
        val link = clean(url)
        return when {
            intro.isBlank() && link.isBlank() -> null
            link.isBlank() -> intro
            intro.isBlank() || intro.contains(link) -> if (intro.isBlank()) link else intro
            else -> "$intro\n$link"
        }
    }

    fun earnedText(approved: Boolean, stats: JSONObject?): String? {
        if (!approved || stats == null || !stats.has("total_earned")) return null
        return PartnerFormat.toman(stats.optLong("total_earned")) + " تومان"
    }

    private fun clean(raw: String): String {
        val text = raw.trim()
        return if (text.isBlank() || text.equals("null", ignoreCase = true)) "" else text
    }
}
