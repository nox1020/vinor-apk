package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingWeekTest {
    @Test
    fun parse_readsTheFullFirstWeekPayload() {
        val stats = ListingWeek.parse(sample())
        assertTrue(stats.available)
        assertEquals(1, stats.weeklyViews)
        assertEquals(1, stats.viewsSincePublish)
        assertEquals(1, stats.visitRequests7d)
        assertEquals(1, stats.visitRequestsTotal)
        assertEquals("2026-10-16T09:48:00Z", stats.publishedAt)
        assertEquals("2026-09-16T09:48:00Z", stats.firstPublishedAt)
        assertEquals(1, stats.daysSincePublish)
        assertEquals("publish", stats.activity.single().type)
        assertEquals("برای فروش منتشر شد", stats.activity.single().text)
        assertEquals("2026-10-16T09:48:00Z", stats.activity.single().date)
    }

    @Test
    fun present_showsRealCountsAndPersianActivityDate() {
        val view = ListingWeek.present(sample())
        assertTrue(view.available)
        assertTrue(view.published)
        assertEquals(ListingWeek.TITLE, view.title)
        assertEquals("", view.statusText)
        assertEquals("", view.hint)
        assertEquals("۱", view.lines.first { it.label == "بازدید همکاران در ۷ روز" }.value)
        assertEquals("۱", view.lines.first { it.label == "بازدید همکاران از انتشار" }.value)
        assertEquals("۱", view.lines.first { it.label == "درخواست بازدید در ۷ روز" }.value)
        assertEquals("۱", view.lines.first { it.label == "درخواست بازدید از انتشار" }.value)
        assertEquals("۱", view.lines.first { it.label == "روز از انتشار" }.value)
        val expectedDay = LandVisit.display("2026-10-16")
        val firstDay = LandVisit.display("2026-09-16")
        assertEquals("$expectedDay · ۱۳:۱۸", view.lines.first { it.label == "تاریخ انتشار" }.value)
        assertEquals("$firstDay · ۱۳:۱۸", view.lines.first { it.label == "اولین انتشار" }.value)
        val event = view.activity.single()
        assertEquals("برای فروش منتشر شد", event.text)
        assertEquals("$expectedDay · ۱۳:۱۸", event.whenLabel)
        assertFalse(event.whenLabel.any { it in '0'..'9' })
    }

    @Test
    fun missingFields_hideTheBlock() {
        val view = ListingWeek.present(JSONObject().put("title", "ملک").put("price_total", 10))
        assertFalse(view.available)
        assertTrue(view.lines.isEmpty())
        assertTrue(view.activity.isEmpty())
        assertEquals("", view.hint)
        assertEquals("", view.statusText)
    }

    @Test
    fun nullFields_areUnpublishedAndSkipFakeChartNumbers() {
        val raw = JSONObject()
            .put("weekly_views", JSONObject.NULL)
            .put("views_since_publish", JSONObject.NULL)
            .put("visit_requests_7d", JSONObject.NULL)
            .put("visit_requests_total", JSONObject.NULL)
            .put("published_at", JSONObject.NULL)
            .put("first_published_at", JSONObject.NULL)
            .put("days_since_publish", JSONObject.NULL)
            .put("monthly_change_pct", JSONObject.NULL)
            .put("monthly_change_label", JSONObject.NULL)
            .put("yearly_change_pct", JSONObject.NULL)
            .put("yearly_change_label", JSONObject.NULL)
            .put("neighborhood_avg", JSONObject.NULL)
            .put("equity", JSONObject().put("total", JSONObject.NULL).put("paid_off_pct", JSONObject.NULL))
            .put("price_history", JSONArray())
            .put("activity", JSONObject.NULL)
        val stats = ListingWeek.parse(raw)
        assertTrue(stats.available)
        assertNull(stats.publishedAt)
        assertNull(stats.firstPublishedAt)
        assertNull(stats.daysSincePublish)
        assertEquals(0, stats.weeklyViews)
        assertTrue(stats.activity.isEmpty())
        val view = ListingWeek.present(stats)
        assertFalse(view.published)
        assertEquals(ListingWeek.UNPUBLISHED, view.statusText)
        assertTrue(view.lines.isEmpty())
        assertEquals("", view.hint)
        assertTrue(view.activity.isEmpty())
    }

    @Test
    fun unpublished_keepsFirstPublishedDateAndHidesCounters() {
        val view = ListingWeek.present(
            JSONObject()
                .put("published_at", JSONObject.NULL)
                .put("first_published_at", "2026-09-16T09:48:00Z")
                .put("weekly_views", 4)
                .put("days_since_publish", 9),
        )
        assertFalse(view.published)
        assertEquals(ListingWeek.UNPUBLISHED, view.statusText)
        assertEquals(listOf("اولین انتشار"), view.lines.map { it.label })
        assertEquals("", view.hint)
    }

    @Test
    fun blankPublishedAt_isUnpublished() {
        val view = ListingWeek.present(
            JSONObject()
                .put("published_at", "  ")
                .put("weekly_views", 4)
                .put("days_since_publish", 9),
        )
        assertFalse(view.published)
        assertTrue(view.lines.isEmpty())
        assertEquals("", view.hint)
    }

    @Test
    fun stringAndPersianDigits_parseAsCounts() {
        val stats = ListingWeek.parse(
            JSONObject()
                .put("published_at", "2026-10-16T09:48:00Z")
                .put("weekly_views", "۲")
                .put("views_since_publish", "12")
                .put("visit_requests_7d", "۱٬۰۰0")
                .put("visit_requests_total", 3.8)
                .put("days_since_publish", "۴"),
        )
        assertEquals(2, stats.weeklyViews)
        assertEquals(12, stats.viewsSincePublish)
        assertEquals(1000, stats.visitRequests7d)
        assertEquals(3, stats.visitRequestsTotal)
        assertEquals(4, stats.daysSincePublish)
    }

    @Test
    fun quietHint_afterThreeQuietDays() {
        val view = ListingWeek.present(published(days = 3, visits = 0, weekly = 2))
        assertEquals(
            "۳ روز از انتشار گذشته و هنوز درخواست بازدید نیست. عکس اضافه کنید یا پورسانت را مشخص کنید.",
            view.hint,
        )
        assertEquals("۲", view.lines.first { it.label == "بازدید همکاران در ۷ روز" }.value)
    }

    @Test
    fun quietHint_staysHiddenWhenAVisitExistsOrTheWeekIsYoung() {
        assertEquals("", ListingWeek.present(published(days = 5, visits = 1, weekly = 0)).hint)
        assertEquals("", ListingWeek.present(published(days = 2, visits = 0, weekly = 0)).hint)
        assertEquals("", ListingWeek.present(published(days = null, visits = 0, weekly = 0)).hint)
        assertFalse(ListingWeek.present(published(days = null, visits = 0, weekly = 0)).lines.any { it.label == "روز از انتشار" })
    }

    @Test
    fun legacyChartNumbers_doNotAppear() {
        val raw = published(days = 1, visits = 1, weekly = 1)
            .put("monthly_change_pct", 42)
            .put("monthly_change_label", "+۴۲٪")
            .put("yearly_change_pct", 11)
            .put("neighborhood_avg", 9_000_000)
            .put("equity", JSONObject().put("total", 500).put("paid_off_pct", 80))
            .put("price_history", JSONArray().put(10).put(20).put(30))
        val view = ListingWeek.present(raw)
        val shown = (view.lines.map { it.value } + view.activity.map { it.text } + listOf(view.hint, view.statusText))
            .joinToString(" ")
        assertFalse(shown.contains("۴۲"))
        assertFalse(shown.contains("۹"))
        assertFalse(shown.contains("۵۰۰"))
        assertFalse(shown.contains("۸۰"))
        assertFalse(shown.contains("۱۰"))
        assertFalse(shown.contains("۲۰"))
        assertFalse(shown.contains("۳۰"))
    }

    @Test
    fun activity_skipsGarbageAndKeepsATypedRowWithoutText() {
        val raw = published(days = 1, visits = 1, weekly = 1).put(
            "activity",
            JSONArray()
                .put("نه")
                .put(JSONObject.NULL)
                .put(JSONObject().put("type", "visit").put("text", JSONObject.NULL).put("date", ""))
                .put(JSONObject().put("type", "note").put("text", "").put("date", "دیروز"))
                .put(JSONObject().put("type", "view").put("text", "۲ بازدید").put("date", "2026-10-16")),
        )
        val view = ListingWeek.present(raw)
        assertEquals(listOf("درخواست بازدید", "۲ بازدید"), view.activity.map { it.text })
        assertEquals("", view.activity[0].whenLabel)
        assertEquals(LandVisit.display("2026-10-16"), view.activity[1].whenLabel)
    }

    @Test
    fun copyOnto_updatesPresentKeysAndKeepsTheRest() {
        val card = JSONObject()
            .put("title", "ملک")
            .put("weekly_views", 4)
            .put("published_at", "2026-10-01T00:00:00Z")
        ListingWeek.copyOnto(
            card,
            JSONObject()
                .put("weekly_views", 7)
                .put("published_at", JSONObject.NULL)
                .put("activity", JSONArray().put(JSONObject().put("type", "publish").put("text", "برای فروش منتشر شد"))),
        )
        assertEquals("ملک", card.getString("title"))
        assertEquals(7, card.getInt("weekly_views"))
        assertTrue(card.isNull("published_at"))
        assertFalse(card.has("first_published_at"))
        val merged = MyPropertyManage.mergeDetail(
            JSONObject().put("code", "4").put("title", "ملک").put("weekly_views", 2),
            JSONObject().put("ok", true).put("visit_requests_total", 0).put("days_since_publish", 3)
                .put("published_at", "2026-10-01T00:00:00Z"),
        )
        assertEquals(2, merged.getInt("weekly_views"))
        assertEquals(0, merged.getInt("visit_requests_total"))
        assertEquals(3, merged.getInt("days_since_publish"))
    }

    private fun sample(): JSONObject = JSONObject()
        .put("weekly_views", 1)
        .put("views_since_publish", 1)
        .put("visit_requests_7d", 1)
        .put("visit_requests_total", 1)
        .put("published_at", "2026-10-16T09:48:00Z")
        .put("first_published_at", "2026-09-16T09:48:00Z")
        .put("days_since_publish", 1)
        .put("monthly_change_pct", JSONObject.NULL)
        .put("equity", JSONObject().put("total", JSONObject.NULL).put("paid_off_pct", JSONObject.NULL))
        .put("price_history", JSONArray())
        .put(
            "activity",
            JSONArray().put(
                JSONObject()
                    .put("type", "publish")
                    .put("text", "برای فروش منتشر شد")
                    .put("date", "2026-10-16T09:48:00Z"),
            ),
        )

    private fun published(days: Int?, visits: Int, weekly: Int): JSONObject {
        val obj = JSONObject()
            .put("published_at", "2026-10-16T09:48:00Z")
            .put("weekly_views", weekly)
            .put("views_since_publish", weekly)
            .put("visit_requests_7d", visits)
            .put("visit_requests_total", visits)
        if (days == null) obj.put("days_since_publish", JSONObject.NULL) else obj.put("days_since_publish", days)
        return obj
    }
}
