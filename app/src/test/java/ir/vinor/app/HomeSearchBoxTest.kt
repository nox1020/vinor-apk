package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeSearchBoxTest {
    @Test
    fun homeContainsSearchFiltersWithoutSeparateSearchDestination() {
        val home = source("src/main/res/layout/fragment_dashboard.xml")
        val xml = home.readText()
        assertTrue(xml.contains("dashboardSearchContainer"))
        assertTrue(xml.contains("dashboardShortcuts"))
        val dash = source("src/main/java/ir/vinor/app/DashboardFragment.kt").readText()
        assertTrue(dash.contains("installHomeSearch()"))
        val search = source("src/main/res/layout/fragment_search.xml").readText()
        assertTrue(search.contains("searchChipPrice"))
        assertTrue(search.contains("searchChipArea"))
        assertTrue(search.contains("searchChipDistrict"))
        val graph = source("src/main/res/navigation/nav_graph.xml").readText()
        assertFalse(graph.contains("android:id=\"@+id/searchFragment\""))
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
