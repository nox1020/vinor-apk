package ir.vinor.app

import kotlin.math.max

/**
 * فاصلهٔ امن محتوا از نوار وضعیت، نوار ناوبری و صفحه‌کلید.
 * عددها پیکسل‌اند و از خود اینست‌ها می‌آیند؛ اینجا فقط ترکیب می‌شوند.
 */
object SystemUiPadding {

    fun resolve(
        barsLeft: Int = 0,
        barsTop: Int = 0,
        barsRight: Int = 0,
        barsBottom: Int = 0,
        cutoutLeft: Int = 0,
        cutoutTop: Int = 0,
        cutoutRight: Int = 0,
        cutoutBottom: Int = 0,
        imeBottom: Int = 0,
    ): IntArray {
        return intArrayOf(
            max(barsLeft.coerceAtLeast(0), cutoutLeft.coerceAtLeast(0)),
            max(barsTop.coerceAtLeast(0), cutoutTop.coerceAtLeast(0)),
            max(barsRight.coerceAtLeast(0), cutoutRight.coerceAtLeast(0)),
            maxOf(barsBottom.coerceAtLeast(0), cutoutBottom.coerceAtLeast(0), imeBottom.coerceAtLeast(0)),
        )
    }
}
