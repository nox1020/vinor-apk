package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ویزارد برداشت به سبک دیوار؛ هم‌ارز تست‌های وب tests/test_withdraw_wizard.py. */
class WalletWithdrawWizardTest {

    private val goodSheba = "062960000000100324200001"
    private val badSheba = "062960000000100324200002"

    private fun server(available: Long = 35_000_000L, can: Boolean = true) = JSONObject()
        .put("ok", true)
        .put("flow_version", 1)
        .put("min_amount", 10_000_000)
        .put("available", available)
        .put("blocked", 15_000_000)
        .put("pending_withdrawal", 10_000_000)
        .put("can_withdraw", can)
        .put("chips", JSONArray()
            .put(JSONObject().put("key", "10000000").put("amount", 10_000_000).put("label", "۱۰ میلیون").put("all", false))
            .put(JSONObject().put("key", "20000000").put("amount", 20_000_000).put("label", "۲۰ میلیون").put("all", false))
            .put(JSONObject().put("key", "all").put("amount", available).put("label", "همهٔ موجودی").put("sub", "۳۵٬۰۰۰٬۰۰۰ تومان").put("all", true)))
        .put("notes", JSONArray().put(JSONObject().put("key", "blocked").put("text", "۱۵٬۰۰۰٬۰۰۰ تومان بلوکه است")))
        .put("destinations", JSONArray()
            .put(JSONObject().put("id", "acc1").put("bank_name", "بانک ملت").put("holder_name", "علی رضایی")
                .put("sheba_masked", "IR06 •••• •••• 0001").put("is_default", true)))
        .put("requests", JSONArray().put(JSONObject().put("id", "abc123def456").put("tracking_code", "W-ABC123DE")
            .put("amount", 10_000_000).put("amount_label", "۱۰٬۰۰۰٬۰۰۰ تومان").put("status", "pending")
            .put("status_label", "در انتظار بررسی").put("tone", "amber").put("cancellable", true)))
        .put("texts", JSONObject().put("amount_question", "چقدر می‌خواهید برداشت کنید؟"))
        .put("errors", JSONObject().put("min_amount", "کمترین مبلغ برداشت ۱۰٬۰۰۰٬۰۰۰ تومان است."))
        .put("csrf_token", "tok")

    @Test
    fun parsesOptionsAndPreselectsDefaultAccount() {
        val o = WalletWithdrawWizard.parseOptions(server())
        assertTrue(o.loaded && o.canWithdraw)
        assertEquals(35_000_000L, o.available)
        assertEquals(3, o.chips.size)
        assertTrue(o.chips.last().all)
        assertEquals("tok", o.csrf)
        assertEquals("W-ABC123DE", o.requests[0].tracking)
        assertEquals("چقدر می‌خواهید برداشت کنید؟", o.text("amount_question"))
        val flow = WalletWithdrawFlow(WalletWithdrawWizard.parseOptions(null))
        assertFalse(flow.options.loaded)
        flow.applyOptions(o)
        assertEquals("acc1", flow.destId)
    }

    @Test
    fun oldServerOrErrorIsNotLoaded() {
        assertFalse(WalletWithdrawWizard.parseOptions(JSONObject().put("http_status", 404)).loaded)
        assertFalse(WalletWithdrawWizard.parseOptions(JSONObject().put("ok", true)).loaded)
    }

    @Test
    fun belowMinimumShowsExplanationNotSteps() {
        val flow = WalletWithdrawFlow(WalletWithdrawWizard.parseOptions(server(available = 2_000_000L, can = false)))
        assertTrue(flow.belowMin)
        flow.setInput("10000000")
        assertFalse(flow.canContinue())
    }

    @Test
    fun amountStepChipsAllAndValidation() {
        val flow = WalletWithdrawFlow(WalletWithdrawWizard.parseOptions(server()))
        assertFalse(flow.canContinue())
        flow.setInput("۵٬۰۰۰٬۰۰۰")
        assertEquals("کمترین مبلغ برداشت ۱۰٬۰۰۰٬۰۰۰ تومان است.", flow.amountError())
        flow.setInput("40000000")
        assertEquals("این مبلغ از موجودی قابل برداشت شما بیشتر است.", flow.amountError())
        flow.setInput("20000000")
        assertEquals("20000000", flow.selectedChip())
        assertTrue(flow.canContinue())
        flow.pick(flow.options.chips.last())
        assertTrue(flow.takeAll)
        assertEquals("all", flow.selectedChip())
        assertEquals(35_000_000L, flow.amount())
        assertTrue(flow.next())
        assertEquals(WalletWithdrawFlow.Step.DEST, flow.step)
        assertEquals("مرحلهٔ ۲ از ۳", flow.progressLabel())
    }

    @Test
    fun shebaChecksumAndHolder() {
        assertEquals("", WalletWithdrawWizard.shebaError(goodSheba))
        assertEquals("sheba_checksum", WalletWithdrawWizard.shebaError(badSheba))
        assertEquals("sheba_format", WalletWithdrawWizard.shebaError("1234"))
        assertEquals(goodSheba, WalletWithdrawWizard.shebaDigits("IR06 2960-0000 0010 0324 2000 01"))
        assertEquals(goodSheba, WalletWithdrawWizard.shebaDigits("IR۰۶۲۹۶۰۰۰۰۰۰۰۱۰۰۳۲۴۲۰۰۰۰۱"))
        assertEquals("IR06 •••• •••• 0001", WalletWithdrawWizard.maskSheba(goodSheba))
        assertEquals("", WalletWithdrawWizard.holderError("علی رضایی"))
        assertEquals("invalid_holder", WalletWithdrawWizard.holderError("ع"))
        assertEquals("invalid_holder", WalletWithdrawWizard.holderError("<b>x</b>"))
        assertEquals("invalid_holder", WalletWithdrawWizard.holderError("Ali 123"))
    }

    @Test
    fun newShebaDestinationBuildsBodyAndSummary() {
        val flow = WalletWithdrawFlow(WalletWithdrawWizard.parseOptions(server()), requestId = "rid-1")
        flow.setInput("20000000")
        assertTrue(flow.next())
        flow.chooseDest(WalletWithdrawFlow.NEW)
        flow.setSheba("IR" + badSheba)
        assertEquals("این شمارهٔ شبا معتبر نیست. دوباره بررسی کنید.", flow.shebaMessage())
        flow.setSheba("0629")
        assertEquals("", flow.shebaMessage()) // تا ۲۴ رقم کامل نشده خطای قالب نشان نده
        assertFalse(flow.canContinue())
        flow.setSheba(goodSheba)
        flow.setHolder("  علی   رضایی ")
        assertTrue(flow.canContinue())
        assertTrue(flow.next())
        assertEquals(WalletWithdrawFlow.Step.SUMMARY, flow.step)
        val rows = flow.summaryRows()
        assertEquals("۲۰٬۰۰۰٬۰۰۰ تومان", rows[0].second)
        assertEquals("IR06 •••• •••• 0001", rows[1].second)
        assertEquals("علی رضایی", rows[2].second)
        assertEquals("۱۵٬۰۰۰٬۰۰۰ تومان", rows[3].second)
        val body = flow.body()
        assertEquals("rid-1", body.getString("client_request_id"))
        assertEquals(20_000_000L, body.getLong("amount"))
        assertEquals("IR$goodSheba", body.getString("sheba"))
        assertEquals("علی رضایی", body.getString("holder_name"))
        assertFalse(body.has("account_id"))
        assertTrue(flow.back())
        assertEquals(WalletWithdrawFlow.Step.DEST, flow.step)
    }

    @Test
    fun savedAccountAndTakeAllBodyUsesServerBalance() {
        val flow = WalletWithdrawFlow(WalletWithdrawWizard.parseOptions(server()), requestId = "rid-2")
        flow.applyOptions(flow.options)
        flow.pick(flow.options.chips.last())
        flow.next()
        flow.chooseDest("acc1")
        assertTrue(flow.next())
        val body = flow.body()
        assertTrue(body.getBoolean("all"))
        assertFalse(body.has("amount"))
        assertEquals("acc1", body.getString("account_id"))
        assertEquals("rid-2", flow.body().getString("client_request_id")) // ثابت برای تلاش دوباره
    }

    @Test
    fun outcomeParsing() {
        val o = WalletWithdrawWizard.parseOptions(server())
        val ok = WalletWithdrawWizard.parseOutcome(JSONObject().put("ok", true).put("available", 15_000_000)
            .put("request", JSONObject().put("id", "x1").put("tracking_code", "W-X1").put("amount", 20_000_000)
                .put("status", "pending").put("status_label", "در انتظار بررسی")), o)
        assertTrue(ok is WalletWithdrawWizard.Outcome.Submitted)
        assertEquals("W-X1", (ok as WalletWithdrawWizard.Outcome.Submitted).request.tracking)
        val bad = WalletWithdrawWizard.parseOutcome(JSONObject().put("ok", false).put("error", "insufficient_balance"), o)
        assertEquals("این مبلغ از موجودی قابل برداشت شما بیشتر است.", (bad as WalletWithdrawWizard.Outcome.Failed).message)
        val off = WalletWithdrawWizard.parseOutcome(JSONObject().put("ok", false).put("offline", true)
            .put("message", "برای انجام این عملیات اینترنت لازم است."), o)
        assertTrue((off as WalletWithdrawWizard.Outcome.Failed).offline)
        val flow = WalletWithdrawFlow(o)
        flow.done(ok.request)
        assertEquals(WalletWithdrawFlow.Step.DONE, flow.step)
        assertFalse(flow.back())
    }

    @Test
    fun walletHomeDropsTotalLineAndReadsWithdrawBlock() {
        val oldServer = JSONObject().put("ok", true).put("min_amount", 1000)
            .put("wallet", JSONObject().put("available", 3_500_000).put("blocked", 1_500_000).put("total", 5_000_000)
                .put("lines", JSONArray()
                    .put(JSONObject().put("key", "blocked").put("label", "بلوکه برای پورسانت").put("value", "۱٬۵۰۰٬۰۰۰ تومان"))
                    .put(JSONObject().put("key", "total").put("label", "کل موجودی").put("value", "۵٬۰۰۰٬۰۰۰ تومان"))))
        val o = WalletCharge.parseOptions(oldServer)
        assertEquals(listOf("blocked"), o.header.lines.map { it.key })
        assertFalse(o.withdraw.known)
        val fallback = WalletCharge.parseOptions(null, fallbackAvailable = 10L)
        assertEquals(listOf("blocked", "pending_withdrawal"), fallback.header.lines.map { it.key })
        assertTrue(WalletCharge.DEFAULT_TEXTS.values.none { it == "کل موجودی" })
        val withBlock = WalletCharge.parseOptions(JSONObject(oldServer.toString())
            .put("withdraw", JSONObject().put("enabled", true).put("can_withdraw", true).put("min_amount", 10_000_000)
                .put("requests", JSONArray().put(JSONObject().put("id", "r1").put("status", "rejected")
                    .put("status_label", "رد شده").put("tone", "red").put("admin_note", "شبا اشتباه است")))))
        assertTrue(withBlock.withdraw.known && withBlock.withdraw.enabled && withBlock.withdraw.canWithdraw)
        assertEquals("شبا اشتباه است", withBlock.withdraw.requests[0].adminNote)
    }
}
