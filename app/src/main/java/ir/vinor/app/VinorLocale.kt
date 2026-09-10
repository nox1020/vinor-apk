package ir.vinor.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** خانه و کشو باید مثل وب همیشه RTL فارسی باشند، حتی اگر زبان سیستم انگلیسی باشد. */
object VinorLocale {
    fun wrap(base: Context): Context {
        val locale = Locale("fa")
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
