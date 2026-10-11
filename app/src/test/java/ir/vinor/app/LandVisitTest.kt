package ir.vinor.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandVisitTest {

    @Test
    fun visitSitsBesideChatAndSkipsDivarAndOwner() {
        assertTrue(
            LandVisit.showBesideChat(LandDetailRole.Audience.PARTNER, false, false, false),
        )
        assertTrue(
            LandVisit.showBesideChat(LandDetailRole.Audience.GUEST, false, false, false),
        )
        assertFalse(
            LandVisit.showBesideChat(LandDetailRole.Audience.OWNER, false, false, false),
        )
        assertFalse(
            LandVisit.showBesideChat(LandDetailRole.Audience.PARTNER, true, false, false),
        )
        assertFalse(
            LandVisit.showBesideChat(LandDetailRole.Audience.PARTNER, false, true, true),
        )
    }

    @Test
    fun jalaliRoundTripMatchesKnownDays() {
        assertEquals("۱۴۰۵/۰۱/۰۱", LandVisit.display("2026-03-21"))
        assertEquals("۱۴۰۵/۰۷/۱۸", LandVisit.display("2026-10-10"))
        val mehr = LandVisit.jalaliOf("2026-10-10")
        assertEquals(1405, mehr[0])
        assertEquals(7, mehr[1])
        assertEquals(18, mehr[2])
    }

    @Test
    fun dateWindowHonorsWeekdayAndRemainingTehranHours() {
        val (min, max) = LandVisit.bounds("", "2026-10-10")
        assertEquals("2026-10-10", min)
        assertEquals("2027-01-08", max)
        assertFalse(LandVisit.allows("2026-10-09", min, max, emptySet(), emptyList(), min, 15))
        assertTrue(LandVisit.allows("2026-10-10", min, max, emptySet(), listOf(16, 18), min, 15))
        assertFalse(LandVisit.allows("2026-10-10", min, max, emptySet(), listOf(10, 15), min, 15))
        assertTrue(LandVisit.allows("2026-10-11", min, max, emptySet(), listOf(10), min, 20))
        // 2026-10-10 is Saturday = JS weekday 6
        assertFalse(LandVisit.allows("2026-10-10", min, max, setOf(0, 1, 2, 3, 4), emptyList(), min, 10))
        assertTrue(LandVisit.allows("2026-10-10", min, max, setOf(6), emptyList(), min, 10))
        val cells = LandVisit.monthCells(1405, 7, "2026-10-10", min, max, emptySet(), emptyList(), min, 10)
        val selected = cells.filterNotNull().single { it.selected }
        assertEquals("۱۸", selected.label)
        assertTrue(selected.enabled)
        assertTrue(selected.today)
        // اول مهر ۱۴۰۵ چهارشنبه است؛ شنبه ستون صفر است پس روز ۱ در خانهٔ چهارم می‌نشیند.
        assertEquals(4, cells.indexOfFirst { it != null })
        assertEquals("2026-09-23", cells[4]!!.iso)
        assertEquals("۱", cells[4]!!.label)
        assertEquals("مهر ۱۴۰۵", LandVisit.monthTitle(1405, 7))
        val farvardin = LandVisit.monthCells(1405, 1, "", "2026-03-21", "2026-06-19", emptySet(), emptyList(), "2026-03-21", 8)
        assertEquals(0, farvardin.indexOfFirst { it != null })
        assertEquals("2026-03-21", farvardin[0]!!.iso)
    }

    @Test
    fun customerRulesMatchTheWebForm() {
        assertEquals("نام مشتری را وارد کنید.", LandVisit.customerError("", "رضایی", "09121234567"))
        assertEquals("نام خانوادگی مشتری را وارد کنید.", LandVisit.customerError("مریم", " ", "09121234567"))
        assertEquals("شماره تماس مشتری را وارد کنید.", LandVisit.customerError("مریم", "رضایی", ""))
        assertEquals("شماره تماس مشتری معتبر نیست.", LandVisit.customerError("مریم", "رضایی", "02112345678"))
        assertEquals("", LandVisit.customerError("مریم", "رضایی", "۰۹۱۲۱۲۳۴۵۶۷"))
        val customer = LandVisit.customer("مریم", "رضایی", "+989121234567")
        assertEquals("09121234567", customer.phone)
        assertEquals("09121234567", LandVisit.normalizePhone("0098 912-123-4567"))
        assertEquals("شماره تماس مشتری معتبر نیست.", LandVisit.customerError("مریم", "رضایی", "9121234567"))
        assertEquals("شماره تماس مشتری معتبر نیست.", LandVisit.customerError("مریم", "رضایی", "0912.123.4567"))
        assertEquals("09121234567", LandVisit.formFields("2026-10-11", customer)["customer_phone"])
        assertEquals("2026-10-11", LandVisit.formFields("2026-10-11", customer)["visit_date"])
    }

    @Test
    fun pageHtmlSuppliesTheRequestUrlAndPendingBlocksASecondBooking() {
        val html = """
            <script id="landDetailConfig" type="application/json">
            {"txMode":"visit","visitDateMin":"2026-10-10","visitAllowedHours":[18],"csrfToken":"abc","txStatusUrl":"/express/partner/lands/1/tx-status"}
            </script>
            <button id="landTxToggleBtn" data-visit-request-url="/express/partner/lands/1/visit-request" data-csrf="abc"></button>
        """.trimIndent()
        val page = LandVisit.parsePage(html)
        assertEquals("/express/partner/lands/1/visit-request", page.requestUrl)
        assertEquals("/express/partner/lands/1/tx-status", page.statusUrl)
        assertEquals("abc", page.csrf)
        assertEquals(listOf(18), page.hours)
        assertTrue(LandVisit.canSchedule(page.mode, page.canRevisit))
        val pending = LandVisit.mergeStatus(
            page,
            JSONObject().put("success", true).put("tx_mode", "visit_pending").put("visit_date_jalali", "۱۸ مهر"),
        )
        assertFalse(LandVisit.canSchedule(pending.mode, pending.canRevisit))
        assertTrue(LandVisit.blockedMessage(pending).contains("نهایی شده"))
        assertTrue(LandVisit.blockedMessage(pending).contains("۱۸ مهر"))
        val deal = LandVisit.blockedMessage(page.copy(mode = "deal", canRevisit = false))
        assertFalse(deal.contains("|"))
        assertTrue(deal.contains("رفع معامله"))
    }

    @Test
    fun successKeepsAnAlreadySentMessage() {
        assertEquals(LandVisit.SENT, LandVisit.successMessage(JSONObject().put("success", true)))
        assertEquals(
            "این بازدید قبلاً ثبت شده",
            LandVisit.successMessage(JSONObject().put("message", "این بازدید قبلاً ثبت شده")),
        )
        assertFalse(LandVisit.isOk(JSONObject().put("success", false).put("message", "تاریخ نامعتبر است")))
        assertFalse(LandVisit.isOk(JSONObject().put("message", "تمام")))
        assertTrue(LandVisit.isOk(JSONObject().put("success", true)))
        assertEquals("تاریخ نامعتبر است", LandVisit.failureMessage(JSONObject().put("success", false).put("message", "تاریخ نامعتبر است")))
        assertFalse(LandVisit.shouldFollowRedirect(""))
        assertFalse(LandVisit.shouldFollowRedirect("https://vinor.ir/express/partner/home"))
        assertFalse(LandVisit.shouldFollowRedirect("/express/partner/dashboard/"))
        assertTrue(LandVisit.shouldFollowRedirect("/express/partner/lands/1?next=/home"))
    }
}
