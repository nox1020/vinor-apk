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
        assertEquals("علاقه‌مند", card.actions.first().label)
        assertEquals(FollowRequests.Tone.PRIMARY, card.actions.first().tone)
        assertEquals("/express/partner/requests/15/visit-result", card.actions.first().path)
        assertEquals("interested", card.actions.first().body.optString("outcome"))
        val done = card.actions.first { it.label == "انجام شد" }
        assertEquals(FollowRequests.Tone.SECONDARY, done.tone)
        assertEquals("/express/partner/requests/15/complete", done.path)
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
        val confirm = actions.first { it.path.endsWith("/deal-confirm") }
        val close = actions.first { it.path.endsWith("/deal-close") }
        assertEquals("تأیید نهایی", confirm.label)
        assertEquals(FollowRequests.Tone.PRIMARY, confirm.tone)
        assertEquals("تأیید نهایی این پرونده ثبت شود؟", confirm.confirm)
        assertEquals("انجام معامله", close.label)
        assertEquals(FollowRequests.Tone.SECONDARY, close.tone)
        assertEquals("انجام معامله ثبت شود؟ پورسانت به کیف پول واریز می‌شود.", close.confirm)
        assertEquals("عدم حضور مشتری ثبت شود؟", actions.first { it.label == "عدم حضور" }.confirm)
        assertTrue(actions.none { it.opensWallet })
    }

    @Test
    fun closedDealShowsCreditedCommissionAndWallet() {
        val item = JSONObject()
            .put("id", 8)
            .put("pipeline_tab", "deals")
            .put("commission_amount", 3_000_000_000L)
            .put("commission_status", "paid")
        val card = FollowRequests.card(item, "deals")
        assertEquals("معامله موفق", card.status)
        assertEquals(
            "پورسانت ${PartnerFormat.toman(3_000_000_000L)} تومان · واریز شده",
            card.commission,
        )
        val deposit = card.actions.single()
        assertEquals("واریز پورسانت", deposit.label)
        assertTrue(deposit.opensWallet)
        assertEquals("/express/partner/commissions", deposit.path)
        assertEquals(FollowRequests.Tone.PRIMARY, deposit.tone)
    }

    @Test
    fun openDealKeepsCommissionVisibleWithoutDepositAction() {
        val item = JSONObject()
            .put("id", 8)
            .put("can_close_deal", true)
            .put("commission_amount", 1_500_000)
            .put("commission_status", "pending")
        val card = FollowRequests.card(item, "deals")
        assertEquals("پورسانت ${PartnerFormat.toman(1_500_000)} تومان · در انتظار واریز", card.commission)
        assertEquals("", card.status)
        assertTrue(card.actions.none { it.opensWallet })
        assertEquals("انجام معامله", card.actions.first { it.path.endsWith("/deal-close") }.label)
    }

    @Test
    fun awaitingCommissionPostsTheDepositInsteadOfOpeningTheWallet() {
        val item = JSONObject()
            .put("id", 8)
            .put("pipeline_tab", "deals")
            .put("commission_amount", 1_500_000)
            .put("commission_status", "در انتظار واریز")
        val card = FollowRequests.card(item, "deals")
        assertEquals("", card.status)
        assertEquals(
            "پورسانت ${PartnerFormat.toman(1_500_000)} تومان · در انتظار واریز",
            card.commission,
        )
        val deposit = card.actions.single()
        assertEquals("واریز پورسانت", deposit.label)
        assertFalse(deposit.opensWallet)
        assertEquals("/express/partner/requests/8/deal-close", deposit.path)
        assertEquals("پورسانت این پرونده به کیف پول واریز شود؟", deposit.confirm)
        assertEquals(FollowRequests.Tone.PRIMARY, deposit.tone)
        assertTrue(FollowRequests.refreshesWallet(deposit.path))
        assertTrue(FollowRequests.movesCard(deposit.path, deposit.body))
    }

    @Test
    fun yesFlagStillClosesTheDealInsteadOfAWalletShortcut() {
        val actions = FollowRequests.actions(
            JSONObject().put("can_close_deal", "yes").put("commission_status", "pending"),
            "8",
            "deals",
        )
        assertTrue(actions.none { it.opensWallet })
        assertEquals("انجام معامله", actions.single { it.path.endsWith("/deal-close") }.label)
    }

    @Test
    fun finishedDealWithoutAmountStillOpensWallet() {
        val card = FollowRequests.card(JSONObject().put("id", 8).put("pipeline_tab", "deals"), "deals")
        assertEquals("معامله موفق", card.status)
        val deposit = card.actions.single()
        assertEquals("واریز پورسانت", deposit.label)
        assertTrue(deposit.opensWallet)
        assertEquals(FollowRequests.Tone.PRIMARY, deposit.tone)
        val won = FollowRequests.card(
            JSONObject().put("id", 3).put("deal_status", "success").put("pipeline_tab", "deals"),
            "deals",
        )
        assertEquals("معامله موفق", won.status)
        assertEquals("پورسانت · معامله موفق", won.commission)
        assertTrue(won.actions.any { it.opensWallet })
        val rejected = FollowRequests.card(
            JSONObject().put("id", 8).put("pipeline_tab", "deals").put("commission_status", "rejected"),
            "deals",
        )
        assertTrue(rejected.actions.none { it.opensWallet })
    }

    @Test
    fun stageChangeKeepsTheMovedCardInView() {
        val tabs = FollowRequests.tabs(
            JSONArray()
                .put(JSONObject().put("id", "requests").put("title", "درخواست"))
                .put(JSONObject().put("id", "visits").put("title", "بازدید"))
                .put(JSONObject().put("id", "deals").put("title", "معامله")),
        )
        val interest = "/express/partner/requests/15/visit-result"
        val interested = JSONObject().put("outcome", "interested")
        assertTrue(FollowRequests.movesCard(interest, interested))
        assertEquals("15", FollowRequests.requestIdFromPath(interest))
        assertEquals("deals", FollowRequests.stageHint(interest, interested, tabs))
        val payload = JSONObject().put(
            "items_by_tab",
            JSONObject().put("deals", JSONArray().put(JSONObject().put("id", 15).put("title", "معامله"))),
        )
        assertEquals("deals", FollowRequests.locateTab(payload, "15", tabs))
        assertEquals("deals", FollowRequests.focusAfterAction("", "deals", "visits", tabs))
        assertEquals("deals", FollowRequests.focusAfterAction("", "", "deals", tabs))
        assertEquals("visits", FollowRequests.focusAfterAction("visits", "", "deals", tabs))
        assertFalse(FollowRequests.movesCard(interest, JSONObject().put("outcome", "not_interested")))
        assertFalse(FollowRequests.movesCard("/express/partner/requests/15/remove", JSONObject()))
        assertFalse(FollowRequests.movesCard("/express/partner/requests/15/no-show", JSONObject()))
        assertFalse(FollowRequests.movesCard("/express/partner/requests/15/deal-reject", JSONObject()))
        assertEquals("visits", FollowRequests.stageHint("/express/partner/requests/15/complete", JSONObject(), tabs))
        assertTrue(FollowRequests.refreshesWallet("/express/partner/requests/15/deal-close"))
        assertFalse(FollowRequests.refreshesWallet("/express/partner/requests/15/deal-confirm"))
        val singular = FollowRequests.tabs(JSONArray().put(JSONObject().put("id", "deal").put("title", "معامله")))
        assertEquals("deal", FollowRequests.stageHint("/express/partner/requests/15/deal-close", JSONObject(), singular))
        val done = FollowRequests.tabs(
            JSONArray()
                .put(JSONObject().put("id", "deals").put("title", "معامله"))
                .put(JSONObject().put("id", "done").put("title", "موفق")),
        )
        assertEquals("done", FollowRequests.stageHint("/express/partner/requests/15/deal-close", JSONObject(), done))
    }

    @Test
    fun everyStageStaysReachableWhenTheServerOmitsTabTitles() {
        val payload = JSONObject().put(
            "items_by_tab",
            JSONObject()
                .put("requests", JSONArray().put(JSONObject().put("id", 1)))
                .put("visits", JSONArray().put(JSONObject().put("id", 2)).put(JSONObject().put("id", 3))),
        )
        val derived = FollowRequests.tabs(null, payload)
        assertEquals(listOf("requests", "visits"), derived.map { it.id })
        assertEquals("درخواست", derived[0].title)
        assertEquals("بازدید", derived[1].title)
        assertEquals(2, derived[1].count)
        val named = FollowRequests.tabs(
            JSONArray().put(JSONObject().put("key", "deals").put("label", "معامله").put("count", 4)),
        )
        assertEquals("deals", named.single().id)
        assertEquals("معامله", named.single().title)
        assertEquals(4, named.single().count)
        val inbox = JSONObject().put(
            "inbox_items",
            JSONArray().put(
                JSONObject()
                    .put("request_id", 9)
                    .put("stage", "بازدید")
                    .put("customer", "سارا")
                    .put("customer_phone", "۰۹۱۲۱۲۳۴۵۶۷")
                    .put("property_code", "B2")
                    .put("visit_when", "پنجشنبه"),
            ),
        )
        val card = FollowRequests.card(FollowRequests.items(inbox, "visit").single())
        assertEquals("9", card.id)
        assertEquals("سارا", card.customer)
        assertEquals("پنجشنبه", card.whenText)
        assertEquals("۰۹۱۲۱۲۳۴۵۶۷", card.phone)
        assertEquals("B2", card.landCode)
        val both = JSONObject().put(
            "items_by_tab",
            JSONObject()
                .put("requests", JSONArray().put(JSONObject().put("id", 4)))
                .put("deals", JSONArray().put(JSONObject().put("id", 4))),
        )
        val tabs = FollowRequests.tabs(
            JSONArray()
                .put(JSONObject().put("id", "requests").put("title", "درخواست"))
                .put(JSONObject().put("id", "deals").put("title", "معامله")),
        )
        assertEquals("deals", FollowRequests.locateTab(both, "4", tabs))
        assertEquals(FollowRequests.Mark.NEUTRAL, FollowRequests.statusMark("بازدید"))
        assertEquals(FollowRequests.Mark.GOOD, FollowRequests.statusMark("معامله موفق"))
        assertEquals(FollowRequests.Mark.BAD, FollowRequests.statusMark("بی‌علاقه"))
        val visit = "/express/partner/requests/9/visit-result"
        assertEquals(
            "علاقه‌مندی ثبت شد.",
            FollowRequests.doneMessage(visit, JSONObject(), JSONObject().put("outcome", "interested")),
        )
        assertEquals(
            "پیگیری ثبت شد.",
            FollowRequests.doneMessage(visit, JSONObject(), JSONObject().put("outcome", "follow_up")),
        )
        assertEquals("بازدید انجام شد.", FollowRequests.doneMessage("/express/partner/requests/9/complete", JSONObject()))
        assertEquals("عدم حضور ثبت شد.", FollowRequests.doneMessage("/express/partner/requests/9/no-show", JSONObject()))
        assertEquals("معامله رد شد.", FollowRequests.doneMessage("/express/partner/requests/9/deal-reject", JSONObject()))
    }

    @Test
    fun silentDealResponsesUseStageCopy() {
        val confirm = "/express/partner/requests/8/deal-confirm"
        val close = "/express/partner/requests/8/deal-close"
        assertEquals("تأیید نهایی ثبت شد.", FollowRequests.doneMessage(confirm, JSONObject()))
        assertEquals(
            "معامله انجام شد. پورسانت به کیف پول واریز می‌شود.",
            FollowRequests.doneMessage(close, JSONObject()),
        )
        assertEquals("ثبت شد", FollowRequests.doneMessage(close, JSONObject().put("message", "ثبت شد")))
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
