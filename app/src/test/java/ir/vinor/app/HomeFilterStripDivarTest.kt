package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ردیف فیلتر هدر خانه به سبک دیوار — هم‌تراز با data-filter-strip در search_feed.html وب. */
class HomeFilterStripDivarTest {
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

    private val chips = listOf(
        Triple("Sort", "ic_chip_sort", HomeHeader.FilterChip.SORT),
        Triple("District", "ic_chip_pin", HomeHeader.FilterChip.DISTRICT),
        Triple("Price", "ic_chip_price", HomeHeader.FilterChip.PRICE),
        Triple("Area", "ic_chip_area", HomeHeader.FilterChip.AREA),
    )

    @Test
    fun activeCountAndPerChipClear() {
        val q = SearchQuery(
            q = "ویلا", city = "تهران", districts = listOf("ونک"),
            priceMin = 1_000_000_000L, sort = SearchSort.values().first { it != SearchSort.NEWEST },
        )
        assertEquals(4, HomeHeader.activeCount(q)) // q + district + price + sort; city is not a filter
        assertEquals(0, HomeHeader.activeCount(SearchQuery(city = "تهران")))
        assertTrue(HomeHeader.isActive(q, HomeHeader.FilterChip.PRICE))
        assertFalse(HomeHeader.isActive(q, HomeHeader.FilterChip.AREA))

        val noPrice = HomeHeader.clearChip(q, HomeHeader.FilterChip.PRICE)
        assertEquals(null, noPrice.priceMin)
        assertEquals(listOf("ونک"), noPrice.districts)
        assertEquals("تهران", noPrice.city)
        assertEquals("ویلا", noPrice.q)
        assertEquals(SearchSort.NEWEST, HomeHeader.clearChip(q, HomeHeader.FilterChip.SORT).sort)
        assertTrue(HomeHeader.clearChip(q, HomeHeader.FilterChip.DISTRICT).districts.isEmpty())
        val area = SearchQuery(areaMin = 80, areaMax = 150)
        assertFalse(HomeHeader.isActive(HomeHeader.clearChip(area, HomeHeader.FilterChip.AREA), HomeHeader.FilterChip.AREA))

        assertEquals("۳", HomeHeader.countLabel(3))
        assertEquals("۱۲", HomeHeader.countLabel(12))
        assertTrue(HomeHeader.showCaret(false))
        assertFalse(HomeHeader.showCaret(true))
    }

    @Test
    fun eachChipHasIconLabelCaretAndClear() {
        val xml = res("layout/fragment_search.xml")
        for ((cap, icon, _) in chips) {
            val box = xml.substringAfter("android:id=\"@+id/searchChip${cap}Box\"").substringBefore("</LinearLayout>")
            assertTrue(cap, box.contains("android:background=\"@drawable/bg_search_chip_state\""))
            val label = box.substringAfter("android:id=\"@+id/searchChip$cap\"").substringBefore("/>")
            assertTrue(cap, label.contains("app:drawableStartCompat=\"@drawable/$icon\""))
            assertTrue(cap, label.contains("app:drawableEndCompat=\"@drawable/ic_search_caret\""))
            assertTrue(cap, label.contains("android:ellipsize=\"end\"") && label.contains("android:singleLine=\"true\""))
            assertTrue(cap, label.contains("android:maxWidth=\"176dp\""))
            assertTrue(cap, label.contains("android:textSize=\"13sp\""))
            val clear = box.substringAfter("android:id=\"@+id/searchChip${cap}Clear\"").substringBefore("/>")
            assertTrue(cap, clear.contains("@drawable/ic_chip_clear") && clear.contains("android:visibility=\"gone\""))
            assertTrue(root().resolve("res/drawable/$icon.xml").isFile)
        }
        val reset = xml.substringAfter("android:id=\"@+id/searchChipClear\"").substringBefore("</LinearLayout>")
        assertTrue(reset.contains("@drawable/bg_search_chip_clear"))
        assertTrue(reset.contains("@drawable/ic_chip_sliders"))
        assertTrue(reset.contains("android:id=\"@+id/searchChipCount\""))
        assertTrue(reset.contains("پاک کردن"))
        assertFalse(xml.contains("حذف فیلترها ✕"))
        // reset chip leads the strip
        assertTrue(xml.indexOf("@+id/searchChipClear\"") < xml.indexOf("@+id/searchChipSortBox\""))
    }

    @Test
    fun activeStateIsTintedOutlineNotSolidGreen() {
        val on = res("drawable/bg_search_chip_on.xml")
        assertTrue(on.contains("<solid android:color=\"@color/search_chip_on_bg\" />"))
        assertTrue(on.contains("android:width=\"1dp\"") && on.contains("android:color=\"@color/vinor_accent_green\""))
        assertFalse(on.contains("<solid android:color=\"@color/vinor_accent_green\""))
        val colors = res("values/colors.xml")
        assertTrue(colors.contains("<color name=\"search_chip_on_bg\">#2410B981</color>"))   // 14% brand green
        assertTrue(colors.contains("<color name=\"search_chip_outline\">#1FFFFFFF</color>")) // 12% white hairline
        assertTrue(res("color/search_chip_text.xml").contains("@color/search_chip_on_text"))
        assertTrue(res("color/search_chip_caret.xml").contains("@color/search_chip_on_text"))
        assertFalse(res("color/search_chip_text.xml").contains("vinor_on_primary"))
        val strip = res("layout/fragment_search.xml").substringAfter("android:id=\"@+id/searchChipScroll\"").substringBefore(">")
        assertTrue(strip.contains("android:requiresFadingEdge=\"horizontal\""))
        assertTrue(strip.contains("android:fadingEdgeLength=\"28dp\""))
    }

    @Test
    fun fragmentWiresClearsAndPaintsChips() {
        val f = kt("SearchFragment.kt")
        for ((cap, _, chip) in chips) {
            assertTrue(cap, f.contains("binding.searchChip${cap}Clear.setOnClickListener { clearChip(HomeHeader.FilterChip.${chip.name}) }"))
            assertTrue(cap, f.contains("paintChip(b.searchChip${cap}Box, b.searchChip$cap, b.searchChip${cap}Clear,"))
            assertTrue(cap, f.contains("binding.searchChip$cap.setOnClickListener"))
        }
        assertTrue(f.contains("b.searchChipCount.text = HomeHeader.countLabel(count)"))
        assertTrue(f.contains("query = HomeHeader.clearChip(query, chip)\n        publish()"))
        assertTrue(f.contains("box.isSelected = active"))
        assertTrue(f.contains("HomeHeader.showCaret(active)"))
    }
}
