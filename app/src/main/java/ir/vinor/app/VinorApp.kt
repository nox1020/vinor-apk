package ir.vinor.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class VinorApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(VinorLocale.wrap(base))
    }

    override fun onCreate() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa"))
        super.onCreate()
        PartnerApi.attach(this)
        VinorAppForeground.install()
        VinorNotificationTray.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix("vinor") }
        }
        // prewarm فقط پس از اولین Activity — جلوگیری از کرش WebView با context اپلیکیشن روی برخی دستگاه‌ها
        runCatching { VinorFcmRegistrar.start(this) }
        if (StoreListing.allowsPackageInstall()) {
            runCatching { VinorAppUpdateController.bootstrap(this) }
        }
    }
}
