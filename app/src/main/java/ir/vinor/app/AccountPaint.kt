package ir.vinor.app

/** صفحه‌ای که قبل از عوض شدن حساب کشیده شده دیگر مال این کاربر نیست. */
internal object AccountPaint {
    fun isStale(paintedGeneration: Int, sessionGeneration: Int): Boolean =
        paintedGeneration >= 0 && paintedGeneration != sessionGeneration
}
