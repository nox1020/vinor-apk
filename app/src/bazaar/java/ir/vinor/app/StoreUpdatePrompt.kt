package ir.vinor.app

import android.app.Activity
import android.content.Intent
import android.net.Uri

/** اگر نسخهٔ تازه‌ای در کافه‌بازار باشد، دیالوگ خود بازار باز می‌شود. */
object StoreUpdatePrompt {
    fun check(activity: Activity) {
        val uri = Uri.parse("bazaar://checkupdate?id=${activity.packageName}")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.farsitel.bazaar")
        runCatching { activity.startActivity(intent) }
    }

    fun onResume(@Suppress("UNUSED_PARAMETER") activity: Activity) = Unit
}
