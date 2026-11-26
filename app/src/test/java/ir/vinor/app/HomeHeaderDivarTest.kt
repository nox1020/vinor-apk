package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** هدر خانه به سبک دیوار — هم‌تراز با search_feed.html وب. */
class HomeHeaderDivarTest {
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val d = dir ?: return@repeat
            listOf(File(d, "src/main"), File(d, "app/src/main")).firstOrNull { File(it, "AndroidManifest.xml").isFile }?.let { return it }
            dir = d.parentFile
        }
        throw AssertionError("app/src/main not found")
    }

    private fun res(path: String) = root().resolve("res/$path").readText()
    private fun kt(name: String) = root().resolve("java/ir/vinor/app/$name").readText()

    @Test
    fun labelsHaveNoTextCaret() {
        assertEquals("جدیدترین", HomeHeader.chipLabel("جدیدترین ▾"))
        assertEquals("قیمت", HomeHeader.chipLabel("قیمت"))
        assertEquals(SearchQuery.ALL_PROVINCES, HomeHeader.cityLabel(""))
        assertEquals("تهران", HomeHeader.cityLabel(" تهران "))
        assertTrue(HomeHeader.showBell(true))
        assertFalse(HomeHeader.showBell(false))
    }

    @Test
    fun unifiedPillHoldsInputDividerAndCity() {
        val xml = res("layout/fragment_search.xml")
        val pill = xml.substringAfter("android:id=\"@+id/searchPill\"").substringBefore("android:id=\"@+id/searchBell\"")
        val ids = listOf("searchInput", "searchClear", "searchPillDivider", "searchCity").map { pill.indexOf("@+id/$it\"") }
        assertTrue(ids.toString(), ids.all { it > 0 } && ids == ids.sorted())
        assertTrue(pill.contains("android:background=\"@drawable/bg_search_pill\""))
        assertTrue(pill.contains("android:layout_height=\"44dp\""))
        val city = pill.substringAfter("@+id/searchCity\"").substringBefore("/>")
        assertTrue(city.contains("@drawable/bg_search_city"))
        assertTrue(city.contains("app:drawableStartCompat=\"@drawable/ic_search_pin\""))
        assertFalse(city.contains("bg_search_chip_state"))
        val bell = xml.substringAfter("android:id=\"@+id/searchBell\"").substringBefore("/>")
        assertTrue(bell.contains("@drawable/ic_bell_outline"))
        assertTrue(bell.contains("android:contentDescription=\"اعلان‌ها\""))
        assertFalse(xml.contains("▾"))
    }

    @Test
    fun drawablesAreDivarLike() {
        val pill = res("drawable/bg_search_pill.xml")
        assertTrue(pill.contains("android:radius=\"999dp\""))
        assertTrue(pill.contains("@color/vinor_raised"))
        val chip = res("drawable/bg_search_chip.xml")
        assertTrue(chip.contains("@android:color/transparent"))
        assertTrue(chip.contains("@color/search_chip_outline"))
        assertTrue(res("values/colors.xml").contains("<color name=\"vinor_chrome\">#141416</color>"))
    }

    @Test
    fun chipStripScrollsWithoutScrollbars() {
        val xml = res("layout/fragment_search.xml")
        val strip = xml.substringAfter("android:id=\"@+id/searchChipScroll\"").substringBefore(">")
        assertTrue(strip.contains("android:scrollbars=\"none\""))
        assertTrue(strip.contains("android:layout_height=\"44dp\""))
        for (id in listOf("searchChipSort", "searchChipDistrict", "searchChipPrice", "searchChipArea")) {
            val chip = xml.substringAfter("@+id/$id\"").substringBefore("/>")
            assertTrue(id, chip.contains("app:drawableEndCompat=\"@drawable/ic_search_caret\""))
        }
    }

    @Test
    fun actionsStillWired() {
        val f = kt("SearchFragment.kt")
        for (wire in listOf(
            "searchChipSort.setOnClickListener", "searchChipDistrict.setOnClickListener",
            "searchChipPrice.setOnClickListener", "searchChipArea.setOnClickListener",
            "searchCity.setOnClickListener", "searchChipClear.setOnClickListener",
            "searchBell.setOnClickListener { (activity as? MainActivity)?.openNotifications() }",
        )) assertTrue(wire, f.contains(wire))
        assertFalse(f.contains("▾"))
        assertFalse(f.contains("بازنشر"))
        // pull-to-refresh on Home is untouched
        assertTrue(res("layout/fragment_dashboard.xml").contains("<ir.vinor.app.VinorSwipeRefreshLayout"))
    }
}
