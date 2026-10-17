package ir.vinor.app

import android.app.Activity

/** نصب مستقیم به‌روزرسانی را از VinorAppUpdateController می‌گیرد، نه از فروشگاه. */
object StoreUpdatePrompt {
    fun check(@Suppress("UNUSED_PARAMETER") activity: Activity) = Unit

    fun onResume(@Suppress("UNUSED_PARAMETER") activity: Activity) = Unit
}
