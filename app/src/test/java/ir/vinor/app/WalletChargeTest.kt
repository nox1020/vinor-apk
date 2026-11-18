package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** منطق مبلغ و مراحل «افزایش اعتبار»؛ هم‌ارز data-wc-step در وب. */
class WalletChargeTest {

    private fun server(prefill: Long = 2_000_000L, ctx: String = "signed", ret: String = "listing"): JSONObject = JSONObject()
        .put("ok", true).put("flow_version", 1)
        .put("min_amount", 1000).put("max_amount", 100_000_000_000L)
        .put("prefill", prefill).put("shortfall", prefill)
        .put("reason_line", "برای انتشار این آگهی ۲٬۰۰۰٬۰۰۰ تومان کم دارید.")
        .put("ctx", ctx)
        .put("presets", JSONArray()
            .put(JSONObject().put("amount", 2_000_000).put("label", "۲٬۰۰۰٬۰۰۰ تومان"))
            .put(JSONObject().put("amount", 100_000).put("label", "۱۰۰٬۰۰۰ تومان"))
            .put(JSONObject().put("amount", 500_000).put("label", "۵۰۰٬۰۰۰ تومان")))
        .put("gateway", JSONObject().put("key", "zarinpal").put("label", "زرین‌پال"))
        .put("return", JSONObject().put("key", ret).put("code", "55").put("label", "بازگشت به آگهی"))
        .put("wallet", JSONObject().put("available", 3_500_000).put("blocked", 1_500_000).put("total", 5_000_000)
            .put("lines", JSONArray().put(JSONObject().put("key", "blocked").put("label", "بلوکه برای پورسانت")
                .put("value", "۱٬۵۰۰٬۰۰۰ تومان").put("hint", "h"))))
        .put("texts", JSONObject().put("amount_question", "چقدر می‌خواهید کیف پولتان را شارژ کنید؟"))
        .put("groups", JSONArray().put(JSONObject().put("key", "2026-11-18").put("label", "امروز")
            .put("entries", JSONArray().put(JSONObject().put("kind", "topup").put("title", "افزایش اعتبار")
                .put("amount_label", "+۳۰۰٬۰۰۰").put("tone", "muted")
                .put("chip", JSONObject().put("label", "لغو شد").put("tone", "red"))))))
        .put("csrf_token", "tok")

    @Test
    fun parsesServerOptionsAndPrefillsShortfall() {
        val o = WalletCharge.parseOptions(server())
        assertEquals(1000L, o.minAmount)
        assertEquals(2_000_000L, o.prefill)
        assertEquals("برای انتشار این آگهی ۲٬۰۰۰٬۰۰۰ تومان کم دارید.", o.reasonLine)
        assertEquals("زرین‌پال", o.gatewayLabel)
        assertEquals("listing", o.returnTarget.key)
        assertEquals(3_500_000L, o.header.available)
        assertEquals("tok", o.csrf)
        assertEquals("لغو شد", o.groups[0].items[0].chipLabel)
        assertEquals("red", o.groups[0].items[0].chipTone)
        assertEquals("چقدر می‌خواهید کیف پولتان را شارژ کنید؟", o.text("amount_question"))
        val flow = WalletChargeFlow(o)
        assertEquals(WalletChargeFlow.Step.AMOUNT, flow.step)
        assertEquals(2_000_000L, flow.amount())
        assertEquals(2_000_000L, flow.selectedPreset())
        assertEquals("۲ میلیون تومان", flow.words())
        assertTrue(flow.canContinue())
    }

    @Test
    fun oldServerFallsBackToDefaultsAndLegacyShortfall() {
        val o = WalletCharge.parseOptions(JSONObject().put("http_status", 404), legacyShortfall = 750_000L, fallbackAvailable = 10_000L)
        assertEquals(WalletCharge.DEFAULT_MIN, o.minAmount)
        assertEquals(750_000L, o.prefill)
        assertEquals("برای ادامه ۷۵۰٬۰۰۰ تومان کم دارید.", o.reasonLine)
        assertEquals(750_000L, o.presets.first().amount)
        assertEquals(5, o.presets.size)
        assertEquals(10_000L, o.header.available)
        assertEquals("wallet", o.returnTarget.key)
        assertEquals(2, o.header.lines.size)
        val tiny = WalletCharge.parseOptions(null, legacyShortfall = 300L)
        assertEquals(1000L, tiny.prefill)
    }

    @Test
    fun digitsAcceptPersianArabicAndSeparators() {
        assertEquals("1250000", WalletCharge.digitsOf("۱٬۲۵۰٬۰۰۰"))
        assertEquals("1250000", WalletCharge.digitsOf("1,250,000 تومان"))
        assertEquals("305", WalletCharge.digitsOf("٣٠٥"))
        assertEquals("5", WalletCharge.digitsOf("0005"))
        assertEquals("", WalletCharge.digitsOf("abc"))
        assertEquals(15, WalletCharge.digitsOf("9".repeat(30)).length)
        assertEquals("۱٬۲۵۰٬۰۰۰", WalletCharge.grouped("1250000"))
    }

    @Test
    fun amountWordsMatchWeb() {
        assertEquals("۲ میلیون و ۵۰۰ هزار تومان", WalletCharge.words(2_500_000L))
        assertEquals("۱ هزار و ۵۰۰ تومان", WalletCharge.words(1_500L))
        assertEquals("۱ میلیارد تومان", WalletCharge.words(1_000_000_000L))
        assertEquals("", WalletCharge.words(0L))
    }

    @Test
    fun minAndMaxAreEnforced() {
        val flow = WalletChargeFlow(WalletCharge.parseOptions(server(prefill = 0L)))
        assertFalse(flow.canContinue())
        flow.setInput("۵۰۰")
        assertEquals("مبلغ باید دست‌کم ۱٬۰۰۰ تومان باشد.", flow.error())
        assertFalse(flow.next())
        flow.setInput("1000")
        assertEquals("", flow.error())
        assertTrue(flow.canContinue())
        flow.setInput("1" + "0".repeat(12))
        assertEquals("این مبلغ بیش از حد مجاز است.", flow.error())
        assertFalse(flow.canContinue())
    }

    @Test
    fun chipsSelectAndTypingClearsSelection() {
        val flow = WalletChargeFlow(WalletCharge.parseOptions(server(prefill = 0L)))
        flow.pick(500_000L)
        assertEquals(500_000L, flow.selectedPreset())
        assertEquals("۵۰۰٬۰۰۰", flow.inputLabel())
        flow.setInput("۵۰۰۰۰۱")
        assertNull(flow.selectedPreset())
    }

    @Test
    fun stepsSummaryAndBack() {
        val flow = WalletChargeFlow(WalletCharge.parseOptions(server()))
        assertEquals("مرحلهٔ ۱ از ۲", flow.progressLabel())
        assertTrue(flow.next())
        assertEquals(WalletChargeFlow.Step.SUMMARY, flow.step)
        assertEquals("مرحلهٔ ۲ از ۲", flow.progressLabel())
        val rows = flow.summaryRows()
        assertEquals("مبلغ شارژ" to "۲٬۰۰۰٬۰۰۰ تومان", rows[0])
        assertEquals("درگاه پرداخت" to "زرین‌پال", rows[1])
        assertEquals("موجودی بعد از شارژ" to "۵٬۵۰۰٬۰۰۰ تومان", rows[2])
        assertTrue(flow.back())
        assertEquals(WalletChargeFlow.Step.AMOUNT, flow.step)
        assertFalse(flow.back()) // opened at amount: back closes
        val home = WalletChargeFlow(WalletCharge.parseOptions(server()), startAtAmount = false)
        assertEquals(WalletChargeFlow.Step.HOME, home.step)
        home.openAmount()
        assertTrue(home.back())
        assertEquals(WalletChargeFlow.Step.HOME, home.step)
    }

    @Test
    fun bodySendsAmountAndSignedContextOnly() {
        val flow = WalletChargeFlow(WalletCharge.parseOptions(server()))
        val body = flow.body()
        assertEquals(2_000_000L, body.getLong("amount"))
        assertEquals("signed", body.getString("ctx"))
        assertFalse(body.has("return"))
        val plain = WalletChargeFlow(WalletCharge.parseOptions(server(ctx = "", ret = "commissions")))
        assertEquals("commissions", plain.body().getString("return"))
        // a listing target without a signed ctx is never sent by the client
        val forged = WalletChargeFlow(WalletCharge.parseOptions(server(ctx = "", ret = "listing")))
        assertFalse(forged.body().has("return"))
    }

    @Test
    fun serverAnswerFillsOnlyEmptyAmount() {
        val flow = WalletChargeFlow(WalletCharge.parseOptions(null))
        assertEquals(0L, flow.amount())
        flow.applyOptions(WalletCharge.parseOptions(server()))
        assertEquals(2_000_000L, flow.amount())
        flow.setInput("700000")
        flow.applyOptions(WalletCharge.parseOptions(server(prefill = 9_000_000L)))
        assertEquals(700_000L, flow.amount())
    }

    @Test
    fun resultStates() {
        val ok = WalletCharge.parseResult(JSONObject().put("state", "success").put("title", "کیف پولتان شارژ شد")
            .put("amount_label", "۲٬۰۰۰٬۰۰۰ تومان").put("balance_label", "۵٬۵۰۰٬۰۰۰ تومان").put("ref_id", "7391")
            .put("return", JSONObject().put("key", "listing").put("label", "بازگشت به آگهی")))
        assertTrue(ok.isFinal)
        assertFalse(ok.canRetry)
        assertEquals("7391", ok.refId)
        assertTrue(WalletCharge.returnsToPreviousScreen(ok.returnTarget))
        val cancel = WalletCharge.parseResult(JSONObject().put("state", "cancelled"))
        assertEquals("پرداخت لغو شد", cancel.title)
        assertEquals("پرداخت را در درگاه لغو کردید و پولی از حسابتان کم نشد.", cancel.message)
        assertTrue(cancel.canRetry)
        val failed = WalletCharge.parseResult(JSONObject().put("state", "failed"))
        assertTrue(failed.note.contains("۷۲ ساعت"))
        val pending = WalletCharge.parseResult(JSONObject().put("state", "pending").put("can_recheck", true))
        assertFalse(pending.isFinal)
        assertTrue(pending.canRecheck)
        assertEquals("نتیجهٔ پرداخت هنوز مشخص نیست", pending.title)
        val missing = WalletCharge.parseResult(JSONObject().put("ok", false).put("http_status", 404))
        assertEquals("not_found", missing.state)
        assertTrue(missing.canRetry)
        assertFalse(WalletCharge.returnsToPreviousScreen(WalletCharge.Return("wallet", "", "")))
    }

    @Test
    fun flowResultAndRetry() {
        val flow = WalletChargeFlow(WalletCharge.parseOptions(server()))
        flow.next()
        flow.startedPayment(42L)
        assertEquals(WalletChargeFlow.Step.WAITING, flow.step)
        assertEquals(42L, flow.txnId)
        assertFalse(flow.back())
        flow.showResult(WalletCharge.parseResult(JSONObject().put("state", "failed")))
        assertEquals(WalletChargeFlow.Step.RESULT, flow.step)
        flow.retry()
        assertEquals(WalletChargeFlow.Step.AMOUNT, flow.step)
        assertEquals(0L, flow.txnId)
        assertEquals(2_000_000L, flow.amount())
    }

    @Test
    fun handOffAndDeepLinkTopupId() {
        CommissionsFragment.handOffShortfall(2_000_000L, JSONObject().put("charge_context", "ctx1"))
        assertEquals(2_000_000L, CommissionsFragment.pendingShortfall)
        assertEquals("ctx1", CommissionsFragment.pendingChargeContext)
        CommissionsFragment.handOffShortfall(10L, JSONObject())
        assertEquals("", CommissionsFragment.pendingChargeContext)
        CommissionsFragment.pendingShortfall = 0L
        assertEquals(17L, CommissionsFragment.topupIdFromUrl("https://vinor.ir/express/partner/commissions?topup=17"))
        assertEquals(0L, CommissionsFragment.topupIdFromUrl("https://vinor.ir/express/partner/commissions"))
        assertEquals(0L, CommissionsFragment.topupIdFromUrl("https://vinor.ir/express/partner/commissions?topup=x"))
    }
}
