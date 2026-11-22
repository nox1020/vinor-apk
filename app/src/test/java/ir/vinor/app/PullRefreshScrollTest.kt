package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pull-to-refresh نباید با اسکرول عمودی/افقی دعوا کند (گزارش «همهٔ صفحات»):
 * منطق قفل محور + هر SwipeRefreshLayout اپ باید VinorSwipeRefreshLayout باشد.
 */
class PullRefreshScrollTest {
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val d = dir ?: return@repeat
            listOf(File(d, "src/main"), File(d, "app/src/main")).firstOrNull { File(it, "AndroidManifest.xml").isFile }?.let { return it }
            dir = d.parentFile
        }
        throw AssertionError("app/src/main not found")
    }

    private val slop = 16f

    @Test
    fun verticalDownDragIsPull() {
        assertEquals(PullRefreshGesture.Axis.PULL, PullRefreshGesture.axis(2f, 40f, slop))
        assertEquals(PullRefreshGesture.Axis.PULL, PullRefreshGesture.axis(-10f, 30f, slop))
    }

    @Test
    fun horizontalOrUpwardDragNeverPulls() {
        // چیپ/کاروسل افقی با کمی انحراف رو به پایین
        assertEquals(PullRefreshGesture.Axis.OTHER, PullRefreshGesture.axis(40f, 20f, slop))
        assertEquals(PullRefreshGesture.Axis.OTHER, PullRefreshGesture.axis(-30f, 26f, slop))
        // رو به بالا = اسکرول محتوا
        assertEquals(PullRefreshGesture.Axis.OTHER, PullRefreshGesture.axis(0f, -30f, slop))
    }

    @Test
    fun smallMoveStaysUndecided() {
        assertEquals(PullRefreshGesture.Axis.UNDECIDED, PullRefreshGesture.axis(5f, 10f, slop))
    }

    @Test
    fun innerScrolledContentBlocksRefresh() {
        assertTrue(PullRefreshGesture.childCanScrollUp(directChild = false, innerUnderFinger = true))
        assertTrue(PullRefreshGesture.childCanScrollUp(directChild = true, innerUnderFinger = false))
        assertFalse(PullRefreshGesture.childCanScrollUp(directChild = false, innerUnderFinger = false))
    }

    @Test
    fun everyLayoutUsesVinorSwipeRefreshLayout() {
        val layouts = root().resolve("res").listFiles { f -> f.isDirectory && f.name.startsWith("layout") }!!
            .flatMap { it.listFiles { f -> f.name.endsWith(".xml") }!!.toList() }
        val plain = layouts.filter { it.readText().contains("<androidx.swiperefreshlayout.widget.SwipeRefreshLayout") }
        assertTrue("plain SwipeRefreshLayout: ${plain.map { it.name }}", plain.isEmpty())
        val vinor = layouts.filter { it.readText().contains("<ir.vinor.app.VinorSwipeRefreshLayout") }.map { it.name }.toSet()
        for (name in listOf(
            "fragment_dashboard.xml", "fragment_my_property.xml", "fragment_native_partner.xml",
            "fragment_listing_stats.xml", "fragment_consultant_onboarding.xml",
        )) {
            assertTrue(name, name in vinor)
        }
    }

    @Test
    fun noCodeConstructsPlainSwipeRefreshLayout() {
        val kt = root().resolve("java").walkTopDown().filter { it.name.endsWith(".kt") }.toList()
        val bad = kt.filter { Regex("(?<![A-Za-z])SwipeRefreshLayout\\(").containsMatchIn(it.readText()) }
            .filter { it.name != "VinorSwipeRefreshLayout.kt" }
        assertTrue("plain SwipeRefreshLayout(...) in ${bad.map { it.name }}", bad.isEmpty())
        val wallet = kt.first { it.name == "CommissionsFragment.kt" }.readText()
        assertTrue(wallet.contains("VinorSwipeRefreshLayout(ctx)"))
    }

    @Test
    fun customLayoutChecksInnerScrollAndAxis() {
        val src = root().resolve("java/ir/vinor/app/VinorSwipeRefreshLayout.kt").readText()
        assertTrue(src.contains("override fun canChildScrollUp()"))
        assertTrue(src.contains("super.canChildScrollUp()"))
        assertTrue(src.contains("canScrollVertically(-1)"))
        assertTrue(src.contains("PullRefreshGesture.axis("))
        assertTrue(src.contains("override fun onInterceptTouchEvent"))
    }
}
