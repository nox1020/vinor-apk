package ir.vinor.app

import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/** جلوگیری از رفتن محتوا زیر نوار وضعیت (ساعت/باتری/آنتن) و نوار ناوبری. */
object SystemUi {

    private const val BAR_COLOR = "#030712"

    fun apply(activity: AppCompatActivity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor(BAR_COLOR)
        window.navigationBarColor = Color.parseColor(BAR_COLOR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
