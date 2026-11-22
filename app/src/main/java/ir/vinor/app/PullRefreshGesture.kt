package ir.vinor.app

import kotlin.math.abs

/**
 * تصمیم‌های خالص pull-to-refresh (بدون وابستگی اندروید تا تست واحد داشته باشد).
 * همان قواعد هستهٔ وب express-ptr-core.js: قفل محور، اسکرول داخلی، فقط کشش رو به پایین.
 */
object PullRefreshGesture {
    enum class Axis { UNDECIDED, PULL, OTHER }

    /** نسبت لازم تا حرکت «عمودی» حساب شود؛ هم‌اندازهٔ وب (AXIS_RATIO). */
    const val AXIS_RATIO = 1.2f

    /**
     * محور ژست پس از عبور از slop: PULL فقط وقتی رو به پایین و غالباً عمودی باشد.
     * افقی (کاروسل، چیپ‌ها، تب‌ها) یا رو به بالا → OTHER و تا پایان لمس رفرش شروع نمی‌شود.
     */
    fun axis(dx: Float, dy: Float, touchSlop: Float): Axis {
        if (abs(dx) < touchSlop && abs(dy) < touchSlop) return Axis.UNDECIDED
        return if (dy > 0f && abs(dy) > abs(dx) * AXIS_RATIO) Axis.PULL else Axis.OTHER
    }

    /** اسکرولِ ظرفی زیر انگشت (لیست/ScrollView داخلی) یا خود فرزند → کشیدن یعنی اسکرول به بالا. */
    fun childCanScrollUp(directChild: Boolean, innerUnderFinger: Boolean): Boolean =
        directChild || innerUnderFinger
}
