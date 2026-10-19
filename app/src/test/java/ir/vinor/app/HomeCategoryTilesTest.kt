package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeCategoryTilesTest {

    @Test
    fun homeShortcutsAreShortDivarTilesInOneRtlRow() {
        val xml = source("src/main/res/layout/fragment_dashboard.xml").readText()
        val row = xml.substring(xml.indexOf("dashboardShortcuts"), xml.indexOf("dashboardSuggestBlock"))
        assertTrue(row.contains("HorizontalScrollView"))
        assertTrue(row.contains("android:layoutDirection=\"rtl\""))
        assertTrue(row.contains("android:scrollbars=\"none\""))
        assertTrue(row.contains("bg_home_category"))
        assertTrue(row.contains("android:layout_width=\"48dp\""))
        assertTrue(row.contains("android:layout_height=\"48dp\""))
        assertTrue(row.contains("android:layout_width=\"68dp\""))
        assertTrue(row.contains("android:textSize=\"11sp\""))
        assertFalse(row.contains("118dp"))
        assertFalse(row.contains("bg_dash_tile"))
        assertTrue(row.contains("android:text=\"آموزش\""))
        assertTrue(row.contains("android:text=\"خبر\""))
        assertTrue(row.contains("android:text=\"پیگیری\""))
        assertTrue(row.contains("dashboardShortcutTrain"))
        assertTrue(row.contains("dashboardShortcutNews"))
        assertTrue(row.contains("dashboardShortcutFollow"))
        assertFalse(row.contains("آموزش فروش"))
        assertFalse(row.contains("خبر وینور"))
        assertFalse(row.contains("پیگیری مشتری‌ها"))
        assertFalse(row.contains("layout_weight"))
        assertTrue(row.indexOf("dashboardShortcutTrain") < row.indexOf("dashboardShortcutNews"))
        assertTrue(row.indexOf("dashboardShortcutNews") < row.indexOf("dashboardShortcutFollow"))
    }

    @Test
    fun shortcutTapsAndFollowVisibilityStayTheSame() {
        val dash = source("src/main/java/ir/vinor/app/DashboardFragment.kt").readText()
        assertTrue(dash.contains("dashboardShortcutTrain.setOnClickListener"))
        assertTrue(dash.contains("openPath(\"/express/partner/training\")"))
        assertTrue(dash.contains("dashboardShortcutNews.setOnClickListener"))
        assertTrue(dash.contains("openPath(\"/express/partner/news\")"))
        assertTrue(dash.contains("dashboardShortcutFollowTitle.text = \"پیگیری\""))
        assertTrue(dash.contains("openPath(\"/express/partner/requests\")"))
        assertTrue(dash.contains("dashboardShortcutFollowTitle.text = \"سطوح همکاری\""))
        assertTrue(dash.contains("openPath(\"/express/partner/levels\")"))
        assertFalse(dash.contains("پیگیری مشتری‌ها"))
    }

    @Test
    fun suggestedListDropsTheSectionHeaderAndKeepsTheCards() {
        val xml = source("src/main/res/layout/fragment_dashboard.xml").readText()
        val block = xml.substring(xml.indexOf("dashboardSuggestBlock"), xml.indexOf("dashboardHero"))
        assertTrue(block.contains("@+id/dashboardSuggest\""))
        assertFalse(block.contains("فایل‌های پیشنهادی"))
        assertFalse(block.contains("مشاهده همه"))
        assertFalse(block.contains("dashboardSuggestSeeAll"))
        assertFalse(block.contains("ic_chevron_left"))
        val dash = source("src/main/java/ir/vinor/app/DashboardFragment.kt").readText()
        assertFalse(dash.contains("dashboardSuggestSeeAll"))
        assertTrue(dash.contains("dashboardSuggest"))
        assertTrue(source("src/main/res/layout/item_home_suggest.xml").isFile)
    }

    private fun source(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val roots = ArrayList<File>()
        var dir: File? = cwd
        repeat(5) {
            val current = dir ?: return@repeat
            roots.add(current)
            dir = current.parentFile
        }
        for (root in roots) {
            val direct = File(root, relative)
            if (direct.isFile) return direct
            val underApp = File(root, "app/$relative")
            if (underApp.isFile) return underApp
        }
        throw AssertionError("missing $relative from $cwd")
    }
}
