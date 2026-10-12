package ir.vinor.app

import android.content.Context
import org.json.JSONObject

/**
 * بعد از انتشار، توقف انتشار یا حذف، کش دیسک و حافظهٔ نشست جزئیات، آگهی‌های من و خانه با هم پاک می‌شوند.
 * خروج و ۴۰۱ همهٔ همین حافظه را خالی می‌کنند.
 */
object ListingMemory {

    fun invalidateLand(code: String, context: Context?, detailPath: String = ""): List<String> {
        val clean = code.trim()
        val paths = LinkedHashSet<String>()
        if (clean.isNotEmpty()) {
            paths.add(MyPropertyManage.landDataPath(clean))
            val extra = detailPath.trim()
            if (extra.isNotEmpty()) paths.add(extra)
        }
        paths.add(MyPropertyManage.PORTFOLIO_PATH)
        paths.add(DashboardFragment.HOME_DATA)
        for (path in paths) PartnerApi.forget(path, context)
        if (clean.isNotEmpty()) LandDetailFragment.dropRemembered(clean)
        MyPropertiesFragment.clearRemembered()
        DashboardFragment.clearRememberedHome()
        return paths.toList()
    }

    fun clearSession() {
        LandDetailFragment.clearRemembered()
        MyPropertiesFragment.clearRemembered()
        MyPropertyManageFragment.clearRemembered()
        DashboardFragment.clearRememberedHome()
    }

    /** همان چیزی که باز کردن جزئیات قبل از شبکه نشان می‌دهد. آنلاین کش دیسک را رسم نمی‌کند. */
    fun detailOnOpen(
        code: String,
        online: Boolean,
        disk: JSONObject?,
        cleared: Collection<String>,
    ): JSONObject? {
        LandDetailFragment.peekRemembered(code)?.let { return it }
        if (online) return null
        if (MyPropertyManage.landDataPath(code.trim()) in cleared) return null
        return disk
    }

    /** همان چیزی که باز کردن آگهی‌های من قبل از شبکه نشان می‌دهد. */
    fun portfolioOnOpen(
        online: Boolean,
        disk: JSONObject?,
        cleared: Collection<String>,
    ): JSONObject? {
        MyPropertiesFragment.peekRemembered()?.let { return it }
        if (online) return null
        if (MyPropertyManage.PORTFOLIO_PATH in cleared) return null
        return disk
    }

    /** همان چیزی که باز کردن خانه قبل از شبکه نشان می‌دهد. */
    fun homeOnOpen(
        online: Boolean,
        disk: JSONObject?,
        cleared: Collection<String>,
    ): JSONObject? {
        DashboardFragment.peekRememberedHome()?.let { return it }
        if (online) return null
        if (DashboardFragment.HOME_DATA in cleared) return null
        return disk
    }
}
