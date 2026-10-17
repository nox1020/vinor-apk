package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingStatsTest {
    @After
    fun clear() {
        ListingRepository.clear()
    }

    @Test
    fun navigationArg_roundTripsTheListing() {
        val raw = ListingStats.pack(JSONObject().put("code", "۴").put("title", "ملک").put("weekly_views", 2))
        val back = ListingStats.read(raw)
        assertEquals(ListingStats.ARG_SNAPSHOT, ListingStatsFragment.ARG_SNAPSHOT)
        assertEquals("۴", back.getString("code"))
        assertEquals("ملک", back.getString("title"))
        assertEquals(2, back.getInt("weekly_views"))
        assertEquals("{}", ListingStats.pack(ListingStats.read("not-json")))
    }

    @Test
    fun display_reusesWeekViewAndRepositoryAfterUnpublish() {
        val passed = JSONObject()
            .put("code", "4")
            .put("title", "ملک")
            .put("published_at", "2026-10-16T09:48:00Z")
            .put("weekly_views", 3)
            .put("days_since_publish", 4)
            .put("visit_requests_total", 0)
            .put("published_for_sale", true)
        ListingRepository.adopt(JSONObject(passed.toString()))
        ListingRepository.applyPublished("4", false)
        val view = ListingStats.display(passed)
        assertTrue(view.available)
        assertFalse(view.published)
        assertEquals(ListingWeek.UNPUBLISHED, view.statusText)
        assertEquals("", view.hint)
        assertEquals("ملک", ListingStats.resolve(passed).getString("title"))
    }

    @Test
    fun portfolio_matchesPreviousCodeAndRefreshesPublishDates() {
        val current = JSONObject().put("code", "2").put("title", "قدیمی").put("weekly_views", 0)
        val row = JSONObject()
            .put("code", "15")
            .put("title", "تازه")
            .put("published_at", "2026-10-16T09:48:00Z")
            .put("first_published_at", "2026-09-16T09:48:00Z")
            .put("weekly_views", 1)
            .put("views_since_publish", 1)
            .put("visit_requests_7d", 0)
            .put("visit_requests_total", 0)
            .put("days_since_publish", 1)
            .put("published_for_sale", true)
            .put("extras", JSONObject().put("previous_code", "2"))
        val portfolio = JSONObject()
            .put("ok", true)
            .put("items", JSONArray().put(row))
        assertEquals("15", ListingStats.find(portfolio, "2")!!.getString("code"))
        val next = ListingStats.applyPortfolio(current, portfolio)
        assertEquals("15", next.getString("code"))
        assertEquals("قدیمی", next.getString("title"))
        assertEquals(1, next.getInt("weekly_views"))
        val view = ListingStats.display(next)
        assertTrue(view.published)
        assertTrue(view.lines.any { it.label == "تاریخ انتشار" && it.value.contains("۱۳:۱۸") })
        assertEquals("15", ListingRepository.resolve("2")!!.getString("code"))
    }

    @Test
    fun oldPortfolio_staysEmptyInsteadOfInventingNumbers() {
        val current = JSONObject().put("code", "4").put("title", "ملک")
        val portfolio = JSONObject().put("ok", true).put("items", JSONArray().put(JSONObject().put("code", "4").put("title", "ملک")))
        val next = ListingStats.applyPortfolio(current, portfolio)
        val view = ListingStats.display(next)
        assertFalse(view.available)
        assertEquals(ListingStats.Phase.EMPTY, ListingStats.phase(view.available, finished = true, failed = false))
    }

    @Test
    fun phase_coversLoadingContentEmptyAndOffline() {
        assertEquals(ListingStats.Phase.LOADING, ListingStats.phase(ready = false, finished = false, failed = false))
        assertEquals(ListingStats.Phase.CONTENT, ListingStats.phase(ready = true, finished = false, failed = false))
        assertEquals(ListingStats.Phase.CONTENT, ListingStats.phase(ready = true, finished = true, failed = true))
        assertEquals(ListingStats.Phase.EMPTY, ListingStats.phase(ready = false, finished = true, failed = false))
        assertEquals(ListingStats.Phase.OFFLINE, ListingStats.phase(ready = false, finished = true, failed = true))
    }

    @Test
    fun brokenPortfolio_doesNotReplaceTheCard() {
        val current = JSONObject().put("code", "4").put("weekly_views", 2).put("published_at", "2026-10-16T09:48:00Z")
        val next = ListingStats.applyPortfolio(current, JSONObject().put("offline", true).put("http_status", 0))
        assertEquals(2, next.getInt("weekly_views"))
        assertTrue(ListingStats.display(next).published)
    }
}
