package ir.vinor.app

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

class VinorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix("vinor") }
        }
        // گرم‌کردن WebView/Chromium بلافاصله پس از بالا آمدن اپ — قبل از MainActivity
        Handler(Looper.getMainLooper()).post {
            runCatching { WebViewProvider.prewarm(this) }
        }
        runCatching { VinorFcmRegistrar.start(this) }
    }
}
