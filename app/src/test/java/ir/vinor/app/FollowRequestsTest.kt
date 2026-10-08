package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowRequestsTest {

    @Test
    fun keepsSelectedTabAndFallsBackWhenItDisappears() {
        val tabs = FollowRequests.tabs(
            JSONArray()
                .put(JSONObject().put("id", "requests").put("title", "درخواست"))
                .put(JSONObject().put("id", "visits").put("title", "بازدید")),
        )
        assertEquals("visits", FollowRequests.activeId("visits", "requests", tabs))
        assertEquals("requests", FollowRequests.activeId("", "requests", tabs))
        assertEquals("requests", FollowRequests.activeId("gone", "requests", tabs))
        assertEquals("requests", FollowRequests.activeId("", "", tabs))
    }

    @Test
    fun filtersPipelineAndReadsRealFields() {
        val payload = JSONObject().put(
            "inbox_items",
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", 15)
                        .put("pipeline_tab", "visits")
                        .put("title", "null")
                        .put("land_title", "آپارتمان جردن")
                        .put("status_label", "بازدید")
                        .put("customer_name", "مریم")
                        .put("visit_when", "فردا ۱۰")
                        .put("land_code", "A1")
                        .put("can_complete", 1)
                        .put("can_register_result", true)
                        .put("can_remove", true),
                )
                .put(JSONObject().put("id", "9").put("pipeline_tab", "requests").put("title", "دیگر")),
        )
        assertTrue(FollowRequests.items(payload, "requests").single().optString("title") == "دیگر")
        val card = FollowRequests.card(FollowRequests.items(payload, "visits").single())
        assertEquals("15", card.id)
        assertEquals("آپارتمان جردن", card.title)
        assertEquals("بازدید", card.status)
        assertEquals("مریم", card.customer)
        assertEquals("فردا ۱۰", card.whenText)
        assertEquals("A1", card.landCode)
        assertEquals("انجام شد", card.actions.first().label)
        assertEquals(FollowRequests.Tone.PRIMARY, card.actions.first().tone)
        assertEquals("/express/partner/requests/15/complete", card.actions.first().path)
        val interested = card.actions.first { it.label == "علاقه‌مند" }
        assertEquals(FollowRequests.Tone.SECONDARY, interested.tone)
        assertEquals("interested", interested.body.optString("outcome"))
        val remove = card.actions.first { it.label == "حذف" }
        assertEquals(FollowRequests.Tone.DANGER, remove.tone)
        assertEquals("این مورد از پیگیری حذف شود؟", remove.confirm)
        assertEquals("/express/partner/requests/15/remove", remove.path)
    }

    @Test
    fun blankIdHasNoActionsAndOnlyRevisitBecomesPrimary() {
        val blank = FollowRequests.card(JSONObject().put("id", JSONObject.NULL).put("can_remove", true))
        assertEquals("", blank.id)
        assertTrue(blank.actions.isEmpty())
        assertEquals("پیگیری", blank.title)
        val revisit = FollowRequests.actions(
            JSONObject().put("can_revisit", true),
            "3",
        ).single()
        assertEquals(FollowRequests.Tone.PRIMARY, revisit.tone)
        assertEquals("/express/partner/requests/3/revisit", revisit.path)
        assertNull(revisit.confirm)
    }

    @Test
    fun acceptsServerGroupedPipelineItems() {
        val payload = JSONObject().put(
            "items_by_tab",
            JSONObject()
                .put(
                    "visits",
                    JSONArray().put(JSONObject().put("id", 4).put("title", "بازدید دماوند")),
                )
                .put(
                    "deals",
                    JSONArray().put(JSONObject().put("id", 5).put("title", "معامله دماوند")),
                ),
        )
        assertEquals("بازدید دماوند", FollowRequests.items(payload, "visits").single().optString("title"))
        assertEquals("معامله دماوند", FollowRequests.items(payload, "deals").single().optString("title"))
    }

    @Test
    fun irreversibleVisitAndDealActionsAskForConfirmation() {
        val actions = FollowRequests.actions(
            JSONObject()
                .put("can_confirm_deal", true)
                .put("can_close_deal", true)
                .put("can_no_show", true),
            "8",
        )
        assertEquals("انجام معامله تأیید شود؟", actions.first { it.label == "تأیید معامله" }.confirm)
        assertEquals("این معامله بسته شود؟", actions.first { it.label == "بستن معامله" }.confirm)
        assertEquals("عدم حضور مشتری ثبت شود؟", actions.first { it.label == "عدم حضور" }.confirm)
    }

    @Test
    fun acceptsSuccessWithoutOkAndKeepsFailureText() {
        assertTrue(FollowRequests.isOk(JSONObject().put("success", true).put("http_status", 200)))
        assertTrue(FollowRequests.isOk(JSONObject().put("ok", true).put("http_status", 200)))
        assertFalse(FollowRequests.isOk(JSONObject().put("success", false).put("http_status", 200).put("error", "unauthorized")))
        assertFalse(FollowRequests.isOk(JSONObject().put("ok", false).put("http_status", 400)))
        assertFalse(FollowRequests.isOk(JSONObject().put("http_status", 200).put("error", "Not Found")))
        assertEquals(
            "ثبت ناموفق بود.",
            FollowRequests.userMessage(JSONObject().put("http_status", 404).put("error", "Not Found")),
        )
        assertEquals(
            "برای ادامه وارد شوید.",
            FollowRequests.userMessage(JSONObject().put("http_status", 401).put("error", "unauthorized")),
        )
        assertEquals(
            "انجام شد",
            FollowRequests.userMessage(JSONObject().put("message", "انجام شد").put("http_status", 200)),
        )
        assertTrue(FollowRequests.isUnauthorized(JSONObject().put("http_status", 401)))
        assertTrue(FollowRequests.isUnauthorized(JSONObject().put("error", "session_expired")))
        assertFalse(FollowRequests.isUnauthorized(JSONObject().put("http_status", 403).put("error", "forbidden")))
    }
}
