package ir.vinor.app

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * از targetSdk ۳۶ به بعد، `windowOptOutEdgeToEdgeEnforcement` نادیده گرفته می‌شود
 * و `setDecorFitsSystemWindows(true)` دیگر محتوا را از زیر نوار وضعیت و ناوبری بیرون نمی‌کشد.
 * اینجا روی همهٔ APIها لبه به لبه رسم می‌کنیم و خودمان اینست را به پدینگ تبدیل می‌کنیم
 * تا اندروید ۱۶، اندروید ۱۵ و نسخه‌های قدیمی یک‌جور بمانند.
 */
object SystemUi {

    fun apply(activity: AppCompatActivity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bar = ContextCompat.getColor(activity, R.color.vinor_chrome)
        window.statusBarColor = bar
        window.navigationBarColor = bar
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    /** ریشهٔ اکتیویتی: بالا، پایین، چپ و راست، به‌اضافهٔ صفحه‌کلید. */
    fun install(content: View) {
        val baseLeft = content.paddingLeft
        val baseTop = content.paddingTop
        val baseRight = content.paddingRight
        val baseBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val pad = paddingOf(insets)
            view.setPadding(
                baseLeft + pad[0],
                baseTop + pad[1],
                baseRight + pad[2],
                baseBottom + pad[3],
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
        content.post { ViewCompat.requestApplyInsets(content) }
    }

    /**
     * شیت پایین پنجرهٔ جدا دارد و خودش هم لبه‌به‌لب می‌شود.
     * فقط پایین و کناره‌ها را جابه‌جا می‌کنیم تا نوار وضعیت بالای شیت کوتاه فاصله نیندازد.
     */
    fun installSheet(content: View) {
        val baseLeft = content.paddingLeft
        val baseRight = content.paddingRight
        val baseBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val pad = paddingOf(insets)
            view.setPadding(baseLeft + pad[0], view.paddingTop, baseRight + pad[2], baseBottom + pad[3])
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    private fun paddingOf(insets: WindowInsetsCompat): IntArray {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        return SystemUiPadding.resolve(
            bars.left,
            bars.top,
            bars.right,
            bars.bottom,
            cutout.left,
            cutout.top,
            cutout.right,
            cutout.bottom,
            ime,
        )
    }
}
