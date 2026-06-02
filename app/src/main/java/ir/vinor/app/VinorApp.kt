package ir.vinor.app

import android.app.Application
import android.os.Build
import android.webkit.WebView

class VinorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix("vinor") }
        }
        // prewarm فقط پس از اولین Activity — جلوگیری از کرش WebView با context اپلیکیشن روی برخی دستگاه‌ها
        runCatching { VinorFcmRegistrar.start(this) }
    }
}
