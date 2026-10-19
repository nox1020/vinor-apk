package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeSearchBoxTest {
    @Test
    fun homeLayoutDropsTheFileSearchBoxAndKeepsTheSearchScreen() {
        val home = source("src/main/res/layout/fragment_dashboard.xml")
        val xml = home.readText()
        assertFalse(xml.contains("dashboardHomeSearch"))
        assertFalse(xml.contains("جستجو در فایل‌های وینور"))
        assertTrue(xml.contains("dashboardShortcuts"))
        val dash = source("src/main/java/ir/vinor/app/DashboardFragment.kt").readText()
        assertFalse(dash.contains("dashboardHomeSearch"))
        val search = source("src/main/res/layout/fragment_search.xml")
        assertTrue(search.isFile)
        assertTrue(search.length() > 0L)
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
