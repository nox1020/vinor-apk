package ir.vinor.app

/**
 * تصمیم Resume بر اساس پاسخ HTTP — بدون Android.
 *
 * 206 + Range درخواستی → ادامه از بایت فعلی
 * 200 وقتی Range فرستاده شده → سرور Range را نادیده گرفته؛ دانلود از صفر
 * 416 → اگر فایل محلی کامل به نظر برسد VERIFY، وگرنه شروع مجدد
 */
object VinorAppUpdateRange {

    enum class Action {
        APPEND,
        RESTART,
        COMPLETE,
        FAIL_TRANSIENT,
    }

    fun decide(
        resumeFrom: Long,
        httpCode: Int,
        contentLength: Long,
        expectedTotal: Long = 0L,
    ): Action {
        if (httpCode == 206) {
            return if (resumeFrom > 0L) Action.APPEND else Action.RESTART
        }
        if (httpCode == 200) {
            return if (resumeFrom > 0L) Action.RESTART else Action.APPEND
        }
        if (httpCode == 416) {
            if (expectedTotal > 0L && resumeFrom >= expectedTotal) return Action.COMPLETE
            if (contentLength == 0L && resumeFrom > 0L && expectedTotal > 0L && resumeFrom >= expectedTotal) {
                return Action.COMPLETE
            }
            return Action.RESTART
        }
        return Action.FAIL_TRANSIENT
    }

    fun rangeHeader(resumeFrom: Long): String? =
        if (resumeFrom > 0L) "bytes=$resumeFrom-" else null
}
