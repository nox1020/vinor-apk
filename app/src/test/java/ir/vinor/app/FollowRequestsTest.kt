package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val visit = JSONObject()
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
            .put("can_remove", true)
        val payload = JSONObject()
            .put("items", JSONArray())
            .put(
                "inbox_items",
                JSONArray()
                    .put(visit)
                    .put(JSONObject().put("id", "9").put("pipeline_tab", "requests").put("title", "دیگر")),
            )
        assertEquals(2, FollowRequests.feed(payload).size)
        assertEquals("دیگر", FollowRequests.feed(payload, "requests").single().optString("title"))
        val card = FollowRequests.card(FollowRequests.feed(payload, "visits").single())
        assertEquals("visit", card.stage)
        assertEquals("بازدید", card.stageLabel)
        assertEquals("incoming", card.direction)
        assertEquals("دریافتی", card.directionLabel)
        assertEquals("15", card.id)
        assertEquals("آپارتمان جردن", card.title)
        assertEquals("بازدید", card.status)
        assertEquals("مریم", card.customer)
        assertEquals("فردا ۱۰", card.whenText)
        assertEquals("A1", card.landCode)
        assertEquals("علاقه‌مند", card.actions.first().label)
        assertEquals(FollowRequests.Tone.PRIMARY, card.actions.first().tone)
        assertTrue(card.actions.first().form)
        assertEquals("/express/partner/inbox-visits/15/result", card.actions.first().path)
        assertEquals("interested", card.actions.first().body.optString("outcome"))
        assertEquals("", card.actions.first().body.optString("notes"))
        val done = card.actions.first { it.label == "انجام شد" }
        assertEquals(FollowRequests.Tone.SECONDARY, done.tone)
        assertEquals("/express/partner/inbox-visits/15/complete", done.path)
        assertTrue(card.actions.none { it.label == "حذف" })
        assertTrue(card.actions.any { it.json && it.path == "/express/partner/api/requests/15/archive" })
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
        ).single { !it.json }
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
        val deposit = card.actions.single { !it.json }
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
        val deposit = card.actions.single { !it.json }
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
        val deposit = card.actions.single { !it.json }
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

    @Test
    fun cardsPayloadIsTheListAndLegacyRowsStayHidden() {
        val visit = JSONObject()
            .put("id", "7")
            .put("title", "کارت واحد")
            .put("stage", "visit")
            .put("stage_label", "بازدید")
            .put("direction", "outgoing")
            .put("direction_label", "ارسالی")
            .put("pipeline_tab", "visits")
            .put("owner_phone", "09120000000")
            .put("updated_at", "2026-10-16T09:48:00Z")
        val offer = JSONObject()
            .put("kind", "offer")
            .put("id", "off-9")
            .put("pipeline_tab", "deals")
            .put("land_code", "42")
            .put("title", "پیشنهاد قیمت · فایل 42")
            .put("amount", 250000000)
            .put("terms", "نقد")
            .put("status", "pending")
            .put("status_label", "پیشنهاد باز")
            .put("partner_phone", "09123333000")
            .put("buyer_phone", "09124444000")
            .put("can_accept", true)
            .put("can_reject", true)
            .put("can_counter", true)
            .put("return_to", "visits")
            .put(
                "actions",
                JSONObject()
                    .put("accept", "/express/partner/inbox-offers/off-9/accept")
                    .put("reject", "/express/partner/inbox-offers/off-9/reject")
                    .put("counter", "/express/partner/inbox-offers/off-9/counter"),
            )
            .put("updated_at", "2026-10-17T09:48:00Z")
        val payload = JSONObject()
            .put("cards", JSONArray().put(visit).put(offer))
            .put("items", JSONArray().put(JSONObject().put("id", "1").put("title", "قدیمی")))
            .put("inbox_items", JSONArray().put(JSONObject().put("id", "2").put("title", "صندوق")))
            .put(
                "offers",
                JSONArray().put(JSONObject().put("id", "off-legacy").put("title", "پیشنهاد جدا").put("amount", 1)),
            )
            .put(
                "tab_counts",
                JSONObject().put("requests", 1).put("visits", 4).put("deals", 2),
            )
        val rows = FollowRequests.feed(payload)
        assertEquals(listOf("off-9", "7"), rows.map { FollowRequests.card(it).id })
        assertTrue(rows.none { FollowRequests.card(it).id == "off-legacy" })
        assertTrue(rows.none { FollowRequests.card(it).id == "1" || FollowRequests.card(it).id == "2" })
        val card = FollowRequests.card(rows.first { FollowRequests.card(it).id == "7" })
        assertEquals("کارت واحد", card.title)
        assertEquals("visit", card.stage)
        assertEquals("بازدید", card.stageLabel)
        assertEquals("outgoing", card.direction)
        assertEquals("ارسالی", card.directionLabel)
        assertEquals("09120000000", card.ownerPhone)
        assertTrue(card.updatedAt.contains("۱۳:۱۸"))
        assertFalse(card.updatedAt.contains("۰۹:۴۸"))
        val deals = FollowRequests.feed(payload, "deals")
        assertEquals(1, deals.size)
        val offerCard = FollowRequests.card(deals.single())
        assertEquals("offer", offerCard.kind)
        assertEquals("deal", offerCard.stage)
        assertEquals("پیشنهاد قیمت · فایل 42", offerCard.title)
        assertEquals("۲۵۰،۰۰۰،۰۰۰ تومان", offerCard.amountText)
        assertEquals("نقد", offerCard.terms)
        assertEquals("پیشنهاد باز", offerCard.status)
        assertTrue(offerCard.phone.startsWith("همکار: "))
        assertTrue(offerCard.phone.contains("\u2066"))
        assertTrue(offerCard.phone.contains("\u2069"))
        assertTrue(offerCard.phone.contains("۰۹۱۲۳۳۳۳۰۰۰"))
        assertFalse(offerCard.phone.contains("0912"))
        assertTrue(offerCard.actions.none { it.json || it.label == FollowRequests.ARCHIVE_LABEL })
        val counts = FollowRequests.counters(payload)
        assertEquals(FollowRequests.feed(payload, "requests").size, counts.requests)
        assertEquals(FollowRequests.feed(payload, "visits").size, counts.visits)
        assertEquals(FollowRequests.feed(payload, "deals").size, counts.deals)
        assertEquals(0, counts.requests)
        assertEquals(1, counts.visits)
        assertEquals(1, counts.deals)
        assertEquals(rows.size, counts.all)
        assertEquals(listOf("", "requests", "visits", "deals"), FollowRequests.filters(counts).map { it.id })
        assertEquals("همه", FollowRequests.filters(counts).first().title)
        assertEquals(rows.size, FollowRequests.filters(counts).first().count)
        val chips = FollowRequests.requestChips(counts, 5)
        assertEquals(listOf("", "requests", "visits", "deals", FollowRequests.ARCHIVE_CHIP), chips.map { it.id })
        assertEquals("بایگانی", chips.last().title)
        assertEquals(5, chips.last().count)
        assertTrue(chips.none { it.title == "فعال" })
        assertEquals(
            "visits" to ListingArchive.ARCHIVED,
            FollowRequests.afterChip("visits", ListingArchive.ACTIVE, FollowRequests.ARCHIVE_CHIP),
        )
        assertEquals(
            "visits" to ListingArchive.ACTIVE,
            FollowRequests.afterChip("visits", ListingArchive.ARCHIVED, FollowRequests.ARCHIVE_CHIP),
        )
        assertEquals(
            "deals" to ListingArchive.ACTIVE,
            FollowRequests.afterChip("visits", ListingArchive.ARCHIVED, "deals"),
        )
        assertEquals(
            "" to ListingArchive.ACTIVE,
            FollowRequests.afterChip("deals", ListingArchive.ARCHIVED, FollowRequests.FILTER_ALL),
        )
        assertEquals(FollowRequests.FILTER_ALL, FollowRequests.listFilter(ListingArchive.ARCHIVED, "visits"))
        assertEquals("visits", FollowRequests.listFilter(ListingArchive.ACTIVE, "visits"))
        assertTrue(FollowRequests.chipSelected("visits", ListingArchive.ARCHIVED, FollowRequests.ARCHIVE_CHIP))
        assertFalse(FollowRequests.chipSelected("visits", ListingArchive.ARCHIVED, "visits"))
        assertTrue(FollowRequests.chipSelected("visits", ListingArchive.ACTIVE, "visits"))
        assertEquals(
            "/express/partner/api/requests/data?status=active&offers_in_cards=1",
            FollowRequests.dataPath(),
        )
        assertTrue(FollowRequests.cachePaths().contains(FollowRequests.dataPath(ListingArchive.ARCHIVED)))
    }

    @Test
    fun offerCardPostsInboxFormWithoutArchive() {
        val item = JSONObject()
            .put("kind", "offer")
            .put("id", "off-9")
            .put("status", "countered")
            .put("status_label", "پیشنهاد متقابل")
            .put("amount", 1200000)
            .put("terms", "چک")
            .put("buyer_phone", "09124444000")
            .put("can_accept", true)
            .put("can_reject", false)
            .put("can_counter", true)
        val card = FollowRequests.card(item)
        assertEquals("deal", card.stage)
        assertEquals("پیشنهاد متقابل", card.status)
        assertEquals("۱،۲۰۰،۰۰۰ تومان", card.amountText)
        assertTrue(card.phone.startsWith("خریدار: "))
        assertTrue(card.phone.contains("\u2066۰۹۱۲۴۴۴۴۰۰۰\u2069"))
        assertEquals(listOf("پذیرش", "پیشنهاد متقابل"), card.actions.map { it.label })
        val accept = card.actions.first()
        assertTrue(accept.form)
        assertFalse(accept.counter)
        assertEquals("/express/partner/inbox-offers/off-9/accept", accept.path)
        assertEquals("visits", accept.body.optString("return_to"))
        assertEquals("این پیشنهاد پذیرفته شود؟", accept.confirm)
        assertEquals("visits", FollowRequests.formFields(accept)["return_to"])
        assertFalse(FollowRequests.formFields(accept).containsKey("csrf_token"))
        val counter = card.actions[1]
        assertTrue(counter.form)
        assertTrue(counter.counter)
        assertNull(counter.confirm)
        assertEquals("/express/partner/inbox-offers/off-9/counter", counter.path)
        val posted = FollowRequests.withCounter(counter, "۲۵۰،۰۰۰", "  نقد ")
        assertNotNull(posted)
        assertEquals("250000", posted!!.body.optString("amount"))
        assertEquals("نقد", posted.body.optString("terms"))
        assertEquals("visits", posted.body.optString("return_to"))
        assertEquals("250000", FollowRequests.formFields(posted)["amount"])
        val bare = FollowRequests.withCounter(counter, "10", "  ")
        assertNotNull(bare)
        assertFalse(bare!!.body.has("terms"))
        assertNull(FollowRequests.withCounter(counter, "", "نقد"))
        assertNull(FollowRequests.withCounter(counter, "0", "نقد"))
        val quiet = FollowRequests.card(
            JSONObject().put("kind", "offer").put("id", "off-1").put("can_accept", false),
        )
        assertTrue(quiet.actions.isEmpty())
    }

    @Test
    fun missingStageFallsBackToPipelineThenStatus() {
        val fromPipeline = FollowRequests.card(
            JSONObject().put("id", "1").put("pipeline_tab", "deals").put("title", "معامله"),
        )
        assertEquals("deal", fromPipeline.stage)
        assertEquals("معامله", fromPipeline.stageLabel)
        val pending = FollowRequests.card(JSONObject().put("id", "2").put("status", "pending"))
        assertEquals("visit_request", pending.stage)
        assertEquals("درخواست بازدید", pending.stageLabel)
        val approved = FollowRequests.card(JSONObject().put("id", "3").put("status", "approved"))
        assertEquals("visit", approved.stage)
        assertEquals("بازدید", approved.stageLabel)
    }

    @Test
    fun sameIdIsOneCardAndNewerUpdateComesFirst() {
        val older = JSONObject()
            .put("id", "4")
            .put("title", "قدیم")
            .put("pipeline_tab", "requests")
            .put("updated_at", "2026-10-01T08:00:00Z")
        val newer = JSONObject()
            .put("id", "4")
            .put("title", "تازه")
            .put("pipeline_tab", "visits")
            .put("direction", "incoming")
            .put("updated_at", "2026-10-16T09:48:00Z")
        val other = JSONObject()
            .put("id", "8")
            .put("title", "دیگر")
            .put("pipeline_tab", "deals")
            .put("updated_at", "2026-10-10T09:00:00Z")
        val payload = JSONObject()
            .put("items", JSONArray().put(older).put(other))
            .put("inbox_items", JSONArray().put(newer))
        val rows = FollowRequests.feed(payload)
        assertEquals(listOf("4", "8"), rows.map { FollowRequests.card(it).id })
        assertEquals("تازه", FollowRequests.card(rows.first()).title)
        assertEquals("incoming", FollowRequests.card(rows.first()).direction)
        val dupCards = JSONObject().put(
            "cards",
            JSONArray()
                .put(JSONObject().put("id", "4").put("title", "اول"))
                .put(JSONObject().put("id", "4").put("title", "دوم")),
        )
        assertEquals("اول", FollowRequests.card(FollowRequests.feed(dupCards).single()).title)
    }

    @Test
    fun revisitCardKeepsHistoryAndCount() {
        val payload = JSONObject().put(
            "cards",
            JSONArray().put(
                JSONObject()
                    .put("id", "15")
                    .put("title", "آپارتمان")
                    .put("stage", "visit")
                    .put("revisit_count", 2)
                    .put("updated_at", "2026-10-16T09:48:00Z")
                    .put(
                        "history",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "h1")
                                .put("visit_date", "2026-10-01")
                                .put("visit_time", "10:30")
                                .put("status", "completed")
                                .put("updated_at", "2026-10-01T08:00:00Z")
                                .put("visit_outcome", "follow_up")
                                .put("notes", "تماس دوباره"),
                        ),
                    ),
            ),
        )
        val card = FollowRequests.card(FollowRequests.feed(payload).single())
        assertEquals(2, card.revisitCount)
        val past = card.history.single()
        assertEquals("h1", past.id)
        assertEquals("بازدید انجام شد", past.status)
        assertEquals("پیگیری دوباره", past.outcome)
        assertEquals("تماس دوباره", past.notes)
        assertTrue(past.whenText.contains("۱۰:۳۰"))
        assertTrue(past.updatedAt.contains("۱۱:۳۰"))
        assertTrue(card.updatedAt.contains("۱۳:۱۸"))
    }

    @Test
    fun offersJoinTheFallbackListAsDeals() {
        val payload = JSONObject()
            .put("items", JSONArray())
            .put("offers", JSONArray().put(JSONObject().put("id", "o1").put("title", "پیشنهاد")))
        val card = FollowRequests.card(FollowRequests.feed(payload, "deals").single())
        assertEquals("o1", card.id)
        assertEquals("deal", card.stage)
        assertEquals("معامله", card.stageLabel)
        assertEquals("دریافتی", card.directionLabel)
        assertEquals(0, FollowRequests.feed(payload, "visits").size)
    }

    @Test
    fun archiveToggleKeepsStageFiltersAndRestoreNotesTheListing() {
        val payload = JSONObject()
            .put("status", "archived")
            .put("archive_counts", JSONObject().put("active", 3).put("archived", 2))
            .put(
                "cards",
                JSONArray()
                    .put(JSONObject().put("id", "1").put("stage", "visit").put("archived", true))
                    .put(JSONObject().put("id", "2").put("stage", "deal").put("archived", true)),
            )
            .put("tab_counts", JSONObject().put("requests", 0).put("visits", 1).put("deals", 1))
        assertEquals(3, FollowRequests.archiveCounts(payload).active)
        assertEquals(2, FollowRequests.archiveCounts(payload).archived)
        assertEquals(1, FollowRequests.feed(payload, "visits").size)
        assertEquals(1, FollowRequests.counters(payload).visits)
        assertEquals(FollowRequests.EMPTY_FILTER, FollowRequests.emptyText("archived", "requests"))
        assertEquals(FollowRequests.EMPTY_ARCHIVED, FollowRequests.emptyText("archived", ""))
        assertEquals(FollowRequests.EMPTY, FollowRequests.emptyText("active", ""))
        val active = FollowRequests.card(JSONObject().put("id", "9").put("title", "بازدید"))
        assertFalse(active.archived)
        val archive = active.actions.single { it.json }
        assertEquals(FollowRequests.ARCHIVE_LABEL, archive.label)
        assertEquals("/express/partner/api/requests/9/archive", archive.path)
        assertFalse(archive.path.endsWith("/remove"))
        val archived = FollowRequests.card(
            JSONObject().put("id", "9").put("archived", true).put("is_archived", true),
        )
        assertTrue(archived.archived)
        val restore = archived.actions.single { it.json }
        assertEquals(FollowRequests.RESTORE_LABEL, restore.label)
        assertEquals("/express/partner/api/requests/9/restore", restore.path)
        val post = JSONObject()
            .put("ok", true)
            .put("http_status", 200)
            .put("message", "پیگیری به فهرست فعال برگشت.")
            .put("listing_archived", true)
        val note = FollowRequests.doneMessage(restore.path, post)
        assertTrue(note.contains("پیگیری به فهرست فعال برگشت."))
        assertTrue(note.contains(FollowRequests.LISTING_STILL_ARCHIVED))
        val forbidden = JSONObject()
            .put("ok", false)
            .put("http_status", 403)
            .put("error_code", "forbidden")
            .put("error", "دسترسی ندارید.")
        assertFalse(FollowRequests.isOk(forbidden))
        assertEquals("دسترسی ندارید.", FollowRequests.userMessage(forbidden))
        val missing = JSONObject()
            .put("ok", false)
            .put("http_status", 404)
            .put("error_code", "not_found")
            .put("error", "این پیگیری پیدا نشد.")
        assertEquals("این پیگیری پیدا نشد.", FollowRequests.userMessage(missing))
    }

    @Test
    fun incomingOwnerCardPostsInboxFormPerAction() {
        val item = JSONObject()
            .put("id", "15")
            .put("direction", "incoming")
            .put("is_own_request", false)
            .put("can_approve", true)
            .put("can_reject", true)
            .put("can_complete", true)
            .put("can_no_show", true)
            .put("can_reject_done", true)
            .put("can_register_result", true)
            .put("can_start_deal", true)
            .put("can_confirm_deal", true)
            .put("can_approve_deal", true)
            .put("can_close_deal", true)
            .put("can_reject_deal", true)
            .put("can_remove", true)
            .put("can_revisit", true)
        val card = FollowRequests.card(item)
        assertEquals("incoming", card.direction)
        val byLabel = card.actions.filter { !it.json }.associateBy { it.label }
        assertEquals("/express/partner/inbox-visits/15/approve", byLabel.getValue("تأیید بازدید").path)
        assertTrue(byLabel.getValue("تأیید بازدید").form)
        assertNull(byLabel.getValue("تأیید بازدید").confirm)
        val reject = byLabel.getValue("رد بازدید")
        assertEquals("/express/partner/inbox-visits/15/reject", reject.path)
        assertEquals(FollowRequests.Tone.DANGER, reject.tone)
        assertEquals("این بازدید رد شود؟", reject.confirm)
        assertEquals("/express/partner/inbox-visits/15/complete", byLabel.getValue("انجام شد").path)
        assertEquals("/express/partner/inbox-visits/15/no_show", byLabel.getValue("عدم حضور").path)
        assertEquals("عدم حضور مشتری ثبت شود؟", byLabel.getValue("عدم حضور").confirm)
        val redo = byLabel.getValue("رد انجام")
        assertEquals("/express/partner/inbox-visits/15/reject_done", redo.path)
        assertEquals("انجام بازدید رد شود؟", redo.confirm)
        listOf("interested" to "علاقه‌مند", "follow_up" to "پیگیری", "not_interested" to "بی‌علاقه").forEach { (outcome, label) ->
            val action = byLabel.getValue(label)
            assertEquals("/express/partner/inbox-visits/15/result", action.path)
            assertEquals(mapOf("outcome" to outcome, "notes" to ""), FollowRequests.formFields(action))
        }
        val start = byLabel.getValue("ثبت معامله")
        assertEquals("/express/partner/inbox-visits/15/start_deal", start.path)
        assertEquals("فروش نهایی برای این همکار ثبت شود؟ فایل در حال معامله می‌شود.", start.confirm)
        val sale = byLabel.getValue("تأیید فروش")
        assertEquals("/express/partner/inbox-visits/15/approve_deal", sale.path)
        assertEquals("فروش تأیید و فایل بسته شود؟ پورسانت به کیف پول همکار اضافه می‌شود.", sale.confirm)
        val dealReject = byLabel.getValue("رد معامله")
        assertEquals("/express/partner/inbox-visits/15/reject_deal", dealReject.path)
        assertEquals("این معامله رد شود؟", dealReject.confirm)
        assertTrue(card.actions.none { it.path.endsWith("/confirm_deal") || it.path.endsWith("/close_deal") })
        assertTrue(card.actions.none { it.path.endsWith("/decline_deal") || it.path.contains("/express/partner/requests/") })
        assertTrue(card.actions.none { it.label == "حذف" || it.label == "بازدید مجدد" })
        assertTrue(FollowRequests.refreshesWallet(sale.path))
    }

    @Test
    fun aliasedDealFlagsStayHiddenWhenTheSpecificFlagIsSet() {
        val confirmOnly = FollowRequests.actions(
            JSONObject().put("direction", "incoming").put("can_confirm_deal", true),
            "3",
        ).single { it.form }
        assertEquals("/express/partner/inbox-visits/3/confirm_deal", confirmOnly.path)
        assertEquals("تأیید نهایی", confirmOnly.label)
        val closeOnly = FollowRequests.actions(
            JSONObject().put("direction", "incoming").put("can_close_deal", true),
            "3",
        ).single { it.form }
        assertEquals("/express/partner/inbox-visits/3/close_deal", closeOnly.path)
        val decline = FollowRequests.actions(
            JSONObject().put("direction", "incoming").put("can_reject_deal", true),
            "3",
        ).single { it.form }
        assertEquals("/express/partner/inbox-visits/3/decline_deal", decline.path)
    }

    @Test
    fun outgoingCardsKeepJsonRoutesWhenIncomingFlagsArePresent() {
        val item = JSONObject()
            .put("id", "15")
            .put("direction", "outgoing")
            .put("is_own_request", false)
            .put("can_approve", true)
            .put("can_start_deal", true)
            .put("can_approve_deal", true)
            .put("can_register_result", true)
            .put("can_complete", true)
            .put("can_confirm_deal", true)
            .put("can_close_deal", true)
            .put("can_no_show", true)
            .put("can_remove", true)
        val actions = FollowRequests.card(item).actions
        assertTrue(actions.none { it.form || it.path.contains("/inbox-visits/") })
        assertTrue(actions.none { it.label == "تأیید بازدید" || it.label == "ثبت معامله" || it.label == "تأیید فروش" })
        assertEquals(
            "/express/partner/requests/15/visit-result",
            actions.first { it.label == "علاقه‌مند" }.path,
        )
        assertEquals("interested", actions.first { it.label == "علاقه‌مند" }.body.optString("outcome"))
        assertFalse(actions.first { it.label == "علاقه‌مند" }.form)
        assertEquals("/express/partner/requests/15/complete", actions.first { it.label == "انجام شد" }.path)
        assertEquals("/express/partner/requests/15/deal-confirm", actions.first { it.label == "تأیید نهایی" }.path)
        assertEquals("/express/partner/requests/15/deal-close", actions.first { it.label == "انجام معامله" }.path)
        assertEquals("/express/partner/requests/15/remove", actions.first { it.label == "حذف" }.path)
        val ownIncoming = JSONObject()
            .put("id", "8")
            .put("direction", "incoming")
            .put("is_own_request", true)
            .put("can_complete", true)
            .put("can_approve", true)
        val own = FollowRequests.card(ownIncoming).actions
        assertEquals("/express/partner/requests/8/complete", own.single { !it.json }.path)
        assertTrue(own.none { it.form || it.label == "تأیید بازدید" })
    }

    @Test
    fun missingDirectionUsesOwnRequestAndDoesNotAssumeOutgoing() {
        val blank = FollowRequests.card(JSONObject().put("id", "1").put("title", "بی‌جهت"))
        assertEquals("", blank.direction)
        assertEquals("", blank.directionLabel)
        val owned = FollowRequests.card(
            JSONObject().put("id", "2").put("is_own_request", true).put("can_complete", true),
        )
        assertEquals("outgoing", owned.direction)
        assertEquals("ارسالی", owned.directionLabel)
        assertEquals("/express/partner/requests/2/complete", owned.actions.single { !it.json }.path)
        val theirs = FollowRequests.card(
            JSONObject().put("id", "3").put("is_own_request", false).put("can_approve", true),
        )
        assertEquals("incoming", theirs.direction)
        assertEquals("دریافتی", theirs.directionLabel)
        assertEquals("/express/partner/inbox-visits/3/approve", theirs.actions.single { it.form }.path)
        val labeled = FollowRequests.card(
            JSONObject().put("id", "4").put("direction_label", "دریافتی").put("can_reject", true),
        )
        assertEquals("incoming", labeled.direction)
        assertEquals("/express/partner/inbox-visits/4/reject", labeled.actions.single { it.form }.path)
        val explicit = FollowRequests.card(
            JSONObject()
                .put("id", "5")
                .put("direction", "outgoing")
                .put("is_own_request", false)
                .put("can_complete", true),
        )
        assertEquals("outgoing", explicit.direction)
        assertEquals("/express/partner/requests/5/complete", explicit.actions.single { !it.json }.path)
    }

    @Test
    fun inboxHtmlRedirectIsSuccessUnlessAWarningFlashIsPresent() {
        val warning = """
            <div id="partnerRequestsPage">
              <div class="rounded-xl border px-4 py-3 text-sm border-amber-500/30 bg-amber-950/30 text-amber-100">
                اجازه مدیریت این درخواست را ندارید.
              </div>
            </div>
        """.trimIndent()
        val failed = FollowRequests.inboxResponse(200, warning, "/express/partner/requests", redirected = true)
        assertFalse(FollowRequests.isOk(failed))
        assertEquals("اجازه مدیریت این درخواست را ندارید.", FollowRequests.userMessage(failed))
        val success = """
            <div id="rqFeed">
              <div class="rounded-xl border px-4 py-3 text-sm border-emerald-500/30 bg-emerald-950/30 text-emerald-200">
                بازدید تأیید شد.
              </div>
            </div>
        """.trimIndent()
        val ok = FollowRequests.inboxResponse(200, success, "/express/partner/requests", redirected = true)
        assertTrue(FollowRequests.isOk(ok))
        assertEquals("بازدید تأیید شد.", FollowRequests.doneMessage("/express/partner/inbox-visits/15/approve", ok))
        val both = warning.replace(
            "اجازه مدیریت این درخواست را ندارید.",
            "اجازه مدیریت این درخواست را ندارید.",
        ) + success
        val mixed = FollowRequests.inboxResponse(200, both, "/express/partner/requests", redirected = true)
        assertFalse(FollowRequests.isOk(mixed))
        val quiet = FollowRequests.inboxResponse(
            200,
            "<div id=\"partnerRequestsPage\"></div>",
            "/express/partner/requests?status=active",
            redirected = true,
        )
        assertTrue(FollowRequests.isOk(quiet))
        assertEquals("بازدید تأیید شد.", FollowRequests.doneMessage("/express/partner/inbox-visits/15/approve", quiet))
        val garbage = FollowRequests.inboxResponse(200, "<html>nope</html>", "/express/partner/inbox-visits/15/approve", false)
        assertFalse(FollowRequests.isOk(garbage))
        val login = FollowRequests.inboxResponse(200, "<html></html>", "/express/partner/login", true)
        assertTrue(FollowRequests.isUnauthorized(login))
        val json = FollowRequests.inboxResponse(
            400,
            """{"ok":false,"error":"معاملهٔ در جریانی برای این کار پیدا نشد."}""",
            "/express/partner/inbox-visits/15/close_deal",
            false,
        )
        assertEquals("معاملهٔ در جریانی برای این کار پیدا نشد.", FollowRequests.userMessage(json))
    }

    @Test
    fun noShowOwnerArchivesByCardIdAndHiddenLandStaysClosed() {
        val owner = JSONObject()
            .put("id", "noshow-private")
            .put("direction", "incoming")
            .put("status", "no_show")
            .put("land_code", "42")
            .put("can_archive", true)
            .put("can_view_land", true)
            .put("land_available", true)
        val card = FollowRequests.card(owner)
        assertTrue(card.showLand)
        val archive = card.actions.single { it.json }
        assertEquals(FollowRequests.ARCHIVE_LABEL, archive.label)
        assertEquals("/express/partner/api/requests/noshow-private/archive", archive.path)
        assertTrue(archive.json)
        assertFalse(archive.form)

        val hidden = FollowRequests.card(
            JSONObject()
                .put("id", "noshow-hidden")
                .put("direction", "incoming")
                .put("land_code", "77")
                .put("can_archive", true)
                .put("can_view_land", false)
                .put("land_available", false),
        )
        assertFalse(hidden.showLand)
        assertEquals(
            "/express/partner/api/requests/noshow-hidden/archive",
            hidden.actions.single { it.json }.path,
        )

        val blocked = FollowRequests.card(
            JSONObject()
                .put("id", "other")
                .put("land_code", "1")
                .put("can_archive", false)
                .put("can_view_land", false),
        )
        assertFalse(blocked.showLand)
        assertTrue(blocked.actions.none { it.json || it.label == FollowRequests.ARCHIVE_LABEL })

        val legacy = FollowRequests.card(JSONObject().put("id", "9").put("land_code", "3"))
        assertTrue(legacy.showLand)
        assertEquals("/express/partner/api/requests/9/archive", legacy.actions.single { it.json }.path)

        val archived = FollowRequests.card(
            JSONObject()
                .put("id", "noshow-private")
                .put("archived", true)
                .put("can_archive", true)
                .put("can_view_land", false)
                .put("land_code", "42"),
        )
        assertFalse(archived.showLand)
        assertEquals(FollowRequests.RESTORE_LABEL, archived.actions.single { it.json }.label)
        assertEquals("/express/partner/api/requests/noshow-private/restore", archived.actions.single { it.json }.path)
    }
}
