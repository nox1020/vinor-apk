package ir.vinor.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** نوار «مشتری فعال / بازدید / معامله» از «وینور من» به «پیگیری» آمد؛ هم‌رفتار با وب. */
class FollowStatsStripTest {
    private fun payload(visible: Boolean = true): JSONObject = JSONObject()
        .put(
            "summary_stats",
            JSONObject()
                .put("active_customers", 2)
                .put("visits_done", "۳")
                .put("deals_done", 1204)
                .put("visible", visible),
        )
        .put(
            "summary_tabs",
            JSONObject().put("active_customers", "requests").put("visits_done", "visits").put("deals_done", "deals"),
        )

    @Test
    fun summaryReadsServerCountsInFixedOrder() {
        val stats = FollowRequests.summary(payload())
        assertEquals(listOf("مشتری فعال", "بازدید", "معامله"), stats.map { it.title })
        assertEquals(listOf(2L, 3L, 1204L), stats.map { it.value })
        assertEquals(listOf("requests", "visits", "deals"), stats.map { it.filter })
        assertEquals("۱،۲۰۴", PartnerFormat.toman(stats[2].value))
    }

    @Test
    fun summaryHiddenForOldServerArchiveAndUnapproved() {
        assertTrue(FollowRequests.summary(JSONObject().put("ok", true)).isEmpty())
        assertTrue(FollowRequests.summary(payload(visible = false)).isEmpty())
        assertTrue(FollowRequests.summary(payload(), ListingArchive.ARCHIVED).isEmpty())
        assertEquals(3, FollowRequests.summary(payload(), ListingArchive.ACTIVE).size)
    }

    @Test
    fun missingTabsFallBackToWebMapping() {
        val obj = payload().apply { remove("summary_tabs") }
        assertEquals(listOf("requests", "visits", "deals"), FollowRequests.summary(obj).map { it.filter })
        val bogus = payload().put("summary_tabs", JSONObject().put("visits_done", "nope"))
        assertEquals("visits", FollowRequests.summary(bogus)[1].filter)
    }

    @Test
    fun tapSelectsFilterAndSecondTapReturnsToAll() {
        val (customers, visits, deals) = FollowRequests.summary(payload())
        assertEquals("visits", FollowRequests.statTapFilter(FollowRequests.FILTER_ALL, visits))
        assertEquals(FollowRequests.FILTER_ALL, FollowRequests.statTapFilter("visits", visits))
        assertEquals("deals", FollowRequests.statTapFilter("visits", deals))
        assertEquals("requests", FollowRequests.statTapFilter(FollowRequests.FILTER_ALL, customers))
        assertTrue(FollowRequests.statSelected("deals", deals))
        assertFalse(FollowRequests.statSelected("deals", visits))
        assertEquals("بازدید: ۳، نمایش همه", FollowRequests.statLabel("visits", visits))
        assertEquals("معامله: ۱،۲۰۴", FollowRequests.statLabel("visits", deals))
        // هر فیلتر نوار در فیلترهای پیگیری هم هست.
        val ids = FollowRequests.filters(FollowRequests.Counts(0, 0, 0)).map { it.id }
        assertTrue(ids.containsAll(listOf("requests", "visits", "deals", FollowRequests.FILTER_ALL)))
    }

    @Test
    fun trackingLayoutHasSlimStripInsideRefreshScroll() {
        val xml = source("src/main/res/layout/fragment_native_partner.xml").readText()
        val refresh = xml.indexOf("ir.vinor.app.VinorSwipeRefreshLayout")
        val strip = xml.indexOf("@+id/nativeFollowStats\"")
        assertTrue(refresh in 0 until strip)
        for (id in listOf("Customers", "Visits", "Deals")) {
            assertTrue(xml.contains("@+id/nativeFollowStat$id\""))
            assertTrue(xml.contains("@+id/nativeFollowStat${id}Value\""))
        }
        assertTrue(xml.contains("android:layout_height=\"40dp\""))
        assertTrue(xml.contains("@drawable/bg_follow_stats"))
        assertFalse(xml.contains("\u0628\u0627\u0632\u0646\u0634\u0631")) // retired repost label
        val bg = source("src/main/res/drawable/bg_follow_stats.xml").readText()
        assertTrue(bg.contains("#11161C"))
        assertTrue(source("src/main/res/drawable/bg_follow_stat_on.xml").readText().contains("#141920"))
    }

    @Test
    fun fragmentWiresStripToRequestFilter() {
        val code = source("src/main/java/ir/vinor/app/NativePartnerFragment.kt").readText()
        assertTrue(code.contains("bindFollowStats(obj)"))
        assertTrue(code.contains("FollowRequests.summary(obj, archiveStatus)"))
        assertTrue(code.contains("requestFilter = FollowRequests.statTapFilter(requestFilter, stat)"))
        assertTrue(code.contains("binding.nativeFollowStats.isVisible = false"))
        assertTrue(code.contains("setOnRefreshListener"))
    }

    @Test
    fun profileNoLongerPaintsTheStatsBox() {
        val xml = source("src/main/res/layout/fragment_profile.xml").readText()
        val code = source("src/main/java/ir/vinor/app/ProfileFragment.kt").readText()
        for (id in listOf("profileSummaryPerformance", "profileSummaryCustomers", "profileSummaryVisits", "profileSummaryDeals")) {
            assertFalse(id, xml.contains(id))
            assertFalse(id, code.contains(id))
        }
        assertFalse(code.contains("HomePulse.customers"))
        // کیف پول و بقیهٔ «وینور من» سر جایشان هستند.
        assertTrue(xml.contains("profileSummaryWallet"))
        assertTrue(code.contains("HomePulse.withdrawable(home)"))
    }

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val current = dir ?: return@repeat
            File(current, relative).takeIf { it.isFile }?.let { return it }
            File(current, "app/$relative").takeIf { it.isFile }?.let { return it }
            dir = current.parentFile
        }
        throw AssertionError("missing $relative")
    }
}
