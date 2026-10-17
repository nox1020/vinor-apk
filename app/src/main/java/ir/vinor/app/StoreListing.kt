package ir.vinor.app

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * کانال انتشار. پلی و بازار به‌روزرسانی را از خود فروشگاه می‌گیرند.
 * نصب مستقیم از وینور همان دانلود APK را نگه می‌دارد.
 */
object StoreListing {
    const val SIDELOAD = "sideload"
    const val PLAY = "play"
    const val BAZAAR = "bazaar"
    const val PACKAGE_NAME = "ir.vinor.app"

    fun channel(): String = BuildConfig.STORE

    fun allowsPackageInstall(channel: String = channel()): Boolean = channel == SIDELOAD

    fun httpsListing(channel: String): String = when (channel) {
        PLAY -> "https://play.google.com/store/apps/details?id=$PACKAGE_NAME"
        BAZAAR -> "https://cafebazaar.ir/app/$PACKAGE_NAME"
        else -> "https://vinor.ir/d"
    }

    /** آدرس باز کردن صفحهٔ برنامه. برای بازار اول اپ بازار، بعد وب. */
    fun storeUri(channel: String): String = when (channel) {
        BAZAAR -> "bazaar://details?id=$PACKAGE_NAME"
        else -> httpsListing(channel)
    }

    fun openListing(context: Context) {
        val current = channel()
        if (current == BAZAAR && openView(context, storeUri(BAZAAR), "com.farsitel.bazaar")) return
        openView(context, httpsListing(current), null)
    }

    private fun openView(context: Context, uri: String, packageName: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!packageName.isNullOrBlank()) intent.setPackage(packageName)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
