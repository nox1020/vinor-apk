package ir.vinor.app

import android.content.Context
import org.json.JSONObject

/**
 * بعد از انتشار، توقف انتشار یا بایگانی، کش دیسک و حافظهٔ نشست جزئیات، آگهی‌های من و خانه با هم پاک می‌شوند.
 * خروج و ۴۰۱ همهٔ همین حافظه را خالی می‌کنند.
 */
object ListingMemory {

    fun invalidateLand(code: String, context: Context?, detailPath: String = ""): List<String> {
        val paths = forgetDisk(code, context, detailPath)
        val clean = code.trim()
        if (clean.isNotEmpty()) LandDetailFragment.dropRemembered(clean)
        MyPropertiesFragment.clearRemembered()
        DashboardFragment.clearRememberedHome()
        VinorLandCardCache.remove(clean)
        return paths
    }

    /** کش دیسک را فراموش کن تا خواندن بعدی شبکه باشد. حافظهٔ نشست دست نمی‌خورد. */
    fun forgetDisk(code: String, context: Context?, detailPath: String = ""): List<String> {
        val clean = ListingRepository.currentCode(code).ifBlank { code.trim() }
        val paths = LinkedHashSet<String>()
        if (clean.isNotEmpty()) {
            paths.add(MyPropertyManage.landDataPath(clean))
            val asked = code.trim()
            if (asked.isNotEmpty() && asked != clean) paths.add(MyPropertyManage.landDataPath(asked))
            val extra = detailPath.trim()
            if (extra.isNotEmpty()) paths.add(extra)
        }
        ListingArchive.cachePaths().forEach { paths.add(it) }
        paths.add(DashboardFragment.HOME_DATA)
        for (path in paths) PartnerApi.forget(path, context)
        return paths.toList()
    }

    /**
     * بعد از انتشار یا توقف، هر کپی محلی همین آگهی همان پرچم‌ها را می‌گیرد.
     * کد قبلی (`extras.previous_code`) هم به کد فعلی وصل می‌شود.
     */
    fun syncPublished(code: String, published: Boolean, context: Context?): JSONObject {
        val canonical = ListingRepository.applyPublished(code, published)
        val current = Listings.codeOf(canonical).ifBlank { ListingRepository.currentCode(code) }
        patchSession(code, current)
        if (context != null) {
            writeBack(context, current)
            val previous = Listings.previousCode(canonical)
            if (previous.isNotBlank() && previous != current) {
                VinorFavoritesStore.replace(context, previous, current)
            }
        }
        return canonical
    }

    fun clearSession() {
        LandDetailFragment.clearRemembered()
        MyPropertiesFragment.clearRemembered()
        MyPropertyManageFragment.clearRemembered()
        DashboardFragment.clearRememberedHome()
        ListingRepository.clear()
    }

    private fun patchSession(code: String, current: String) {
        listOf(code.trim(), current).filter { it.isNotEmpty() }.distinct().forEach { key ->
            val remembered = LandDetailFragment.peekRemembered(key) ?: return@forEach
            ListingRepository.rewrite(remembered)
            val now = Listings.codeOf(remembered).ifBlank { current }
            if (now != key) LandDetailFragment.dropRemembered(key)
            LandDetailFragment.remember(now, remembered)
        }
        MyPropertiesFragment.peekRemembered()?.let { portfolio ->
            ListingRepository.rewrite(portfolio)
            MyPropertiesFragment.remember(portfolio)
        }
        DashboardFragment.peekRememberedHome()?.let { home ->
            ListingRepository.rewrite(home)
            DashboardFragment.rememberHome(home)
        }
        listOf(code.trim(), current).filter { it.isNotEmpty() }.distinct().forEach { key ->
            val card = VinorLandCardCache.get(key) ?: return@forEach
            ListingRepository.rewrite(card)
            val now = Listings.codeOf(card).ifBlank { key }
            if (now != key) VinorLandCardCache.remove(key)
            VinorLandCardCache.put(now, card)
        }
    }

    private fun writeBack(context: Context, code: String) {
        val paths = ArrayList<String>()
        paths.add(MyPropertyManage.landDataPath(code))
        paths.addAll(ListingArchive.cachePaths())
        paths.add(DashboardFragment.HOME_DATA)
        for (path in paths) {
            val disk = PartnerApi.peekJson(path, context) ?: continue
            disk.remove("from_cache")
            ListingRepository.rewrite(disk)
            PartnerApi.remember(path, disk, context)
        }
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
