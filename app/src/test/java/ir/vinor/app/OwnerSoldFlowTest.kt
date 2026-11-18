package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** منطق مراحل پرسش‌نامهٔ «فروخته شد»؛ هم‌ارز data-sw-step در وب. */
class OwnerSoldFlowTest {

    private fun serverOptions(review: Boolean = true, visits: Boolean = true): JSONObject {
        val obj = JSONObject()
            .put("ok", true).put("code", "12").put("can_mark_sold", true).put("title", "آپارتمان گوهردشت")
            .put("wizard_version", 1)
            .put("review_required_if_outside", review)
            .put("review_warning", if (review) "هشدار بررسی" else "")
            .put("outside_summary", if (review) "تا بررسی پشتیبانی آزاد نمی‌شود." else "به کیف پول شما برمی‌گردد.")
            .put("texts", JSONObject().put("channel_question", "ملک‌تان از چه راهی فروخته شد؟"))
            .put("channels", JSONArray()
                .put(JSONObject().put("value", "vinor").put("label", "با بازدیدی که در وینور هماهنگ شد").put("enabled", visits))
                .put(JSONObject().put("value", "outside").put("label", "خودم بیرون از وینور فروختم").put("enabled", true))
                .put(JSONObject().put("value", "not_final").put("label", "هنوز قطعی نشده / منصرف شدم").put("enabled", true)))
            .put("sources", JSONArray()
                .put(JSONObject().put("value", "agency").put("label", "مشاور املاک"))
                .put(JSONObject().put("value", "other").put("label", "سایر")))
            .put("rating_labels", JSONObject().put("5", "عالی"))
        val rows = JSONArray()
        if (visits) {
            rows.put(JSONObject().put("visit_id", "v1").put("partner_name", "رضا").put("visit_date_label", "۲۹ آبان")
                .put("status", "done_pending").put("status_label", "منتظر تأیید انجام بازدید")
                .put("summary", "پورسانت به کیف پول رضا منتقل می‌شود."))
        }
        return obj.put("visits", rows)
    }

    private fun flow(review: Boolean = true, visits: Boolean = true) =
        OwnerSoldFlow(OwnerSold.parseOptions(serverOptions(review, visits)))

    @Test
    fun vinorPath_hasFiveStepsInOrder() {
        val f = flow()
        assertEquals(OwnerSoldFlow.Step.CHANNEL, f.step)
        assertFalse(f.canNext())
        assertTrue(f.selectChannel("vinor"))
        assertEquals(5, f.total())
        assertEquals("مرحلهٔ ۱ از ۵", f.progressLabel())
        assertTrue(f.next())
        assertEquals(OwnerSoldFlow.Step.VISIT, f.step)
        assertFalse(f.canNext())
        assertFalse(f.selectVisit("nope"))
        assertTrue(f.selectVisit("v1"))
        assertTrue(f.next())
        assertEquals(OwnerSoldFlow.Step.PRICE, f.step)
        assertTrue(f.isOptional())
        f.setPrice("۲٬۴۵۰٬۰۰۰٬۰۰۰")
        assertTrue(f.next())
        assertEquals(OwnerSoldFlow.Step.RATING, f.step)
        f.setRating(5)
        f.setComment("پیگیر بود")
        assertTrue(f.next())
        assertEquals(OwnerSoldFlow.Step.SUMMARY, f.step)
        assertTrue(f.isLast())
        assertEquals("مرحلهٔ ۵ از ۵", f.progressLabel())
        assertFalse(f.next())
        assertEquals("پورسانت به کیف پول رضا منتقل می‌شود.", f.escrowText())
        assertFalse(f.escrowIsReview())
        val body = f.body("12")
        assertEquals("vinor", body.getString("channel"))
        assertEquals("v1", body.getString("visit_id"))
        assertEquals(2_450_000_000L, body.getLong("final_price"))
        assertEquals(5, body.getInt("marketer_rating"))
        assertEquals("پیگیر بود", body.getString("comment"))
        assertFalse(body.has("sold_source"))
        val rows = f.summaryRows().toMap()
        assertEquals("۲٬۴۵۰٬۰۰۰٬۰۰۰ تومان", rows["قیمت نهایی"])
        assertEquals("★★★★★ عالی", rows["رضایت از بازاریاب"])
        assertEquals("رضا · ۲۹ آبان", rows["بازدید"])
    }

    @Test
    fun outsidePath_hasFourStepsAndAmberWarningOnSource() {
        val f = flow(review = true)
        f.selectChannel("outside")
        assertEquals(4, f.total())
        f.next()
        assertEquals(OwnerSoldFlow.Step.SOURCE, f.step)
        assertTrue(f.showReviewWarning())
        assertTrue(f.canNext()) // optional
        assertTrue(f.selectSource("agency"))
        f.next()
        assertEquals(OwnerSoldFlow.Step.PRICE, f.step)
        assertFalse(f.showReviewWarning())
        f.next()
        assertEquals(OwnerSoldFlow.Step.SUMMARY, f.step)
        assertTrue(f.escrowIsReview())
        assertEquals("تا بررسی پشتیبانی آزاد نمی‌شود.", f.escrowText())
        val body = f.body("12")
        assertEquals("outside", body.getString("channel"))
        assertEquals("agency", body.getString("sold_source"))
        assertFalse(body.has("visit_id") || body.has("marketer_rating") || body.has("final_price") || body.has("comment"))
        assertEquals("ثبت نشد", f.summaryRows().toMap()["قیمت نهایی"])
    }

    @Test
    fun noReview_noWarning_and_skipClearsAnswer() {
        val f = flow(review = false)
        f.selectChannel("outside")
        f.next()
        assertFalse(f.showReviewWarning())
        f.selectSource("other")
        assertTrue(f.skip())
        assertEquals("", f.source)
        assertEquals(OwnerSoldFlow.Step.PRICE, f.step)
        f.setPrice("12")
        assertTrue(f.skip())
        assertEquals("", f.priceDigits)
        assertFalse(f.escrowIsReview())
        assertEquals("به کیف پول شما برمی‌گردد.", f.escrowText())
    }

    @Test
    fun notFinal_closesWithoutSubmitting() {
        val f = flow()
        assertTrue(f.selectChannel("not_final"))
        assertTrue(f.closesWithoutChange())
        assertTrue(f.canNext())
        assertFalse(f.next())
        assertEquals(OwnerSoldFlow.Step.CHANNEL, f.step)
    }

    @Test
    fun vinorDisabledWithoutVisits() {
        val f = flow(visits = false)
        assertFalse(f.channelEnabled("vinor"))
        assertFalse(f.selectChannel("vinor"))
        assertEquals("", f.channel)
        assertTrue(f.selectChannel("outside"))
    }

    @Test
    fun backWalksTheTrailAndChangingChannelClearsAnswers() {
        val f = flow()
        f.selectChannel("vinor"); f.next(); f.selectVisit("v1"); f.next(); f.next(); f.setRating(4); f.setComment("x")
        assertEquals(OwnerSoldFlow.Step.RATING, f.step)
        assertTrue(f.back()); assertEquals(OwnerSoldFlow.Step.PRICE, f.step)
        assertTrue(f.back()); assertEquals(OwnerSoldFlow.Step.VISIT, f.step)
        assertTrue(f.back()); assertEquals(OwnerSoldFlow.Step.CHANNEL, f.step)
        assertFalse(f.back())
        f.selectChannel("outside")
        assertEquals("", f.visitId)
        assertEquals(0, f.rating)
        assertEquals("", f.comment)
        f.setRating(5) // ignored outside
        assertEquals(0, f.rating)
        assertFalse(f.selectVisit("v1"))
    }

    @Test
    fun priceParsingAndValidation() {
        assertEquals("2450000000", OwnerSoldFlow.digitsOf("۲٬۴۵۰,۰۰۰ ٠٠٠ تومان"))
        assertEquals("۱٬۰۰۰", OwnerSoldFlow.formatPrice("0001000"))
        assertEquals("", OwnerSoldFlow.formatPrice("abc"))
        val f = flow()
        f.selectChannel("outside"); f.next(); f.next()
        f.setPrice("99999999999999")
        assertFalse(f.priceValid())
        assertFalse(f.canNext())
        assertFalse(f.body("12").has("final_price"))
        f.setPrice("0")
        assertTrue(f.priceValid())
        assertEquals("", f.priceDigits)
    }

    @Test
    fun ratingTapTogglesAndCommentIsCapped() {
        val f = flow()
        f.selectChannel("vinor")
        f.setRating(3); assertEquals(3, f.rating)
        f.setRating(3); assertEquals(0, f.rating)
        f.setRating(9); assertEquals(0, f.rating)
        f.setComment("ا".repeat(400))
        assertEquals(300, f.comment.length)
    }

    @Test
    fun oldServerWithoutWizardFieldsStillBuildsTheFlow() {
        val old = JSONObject().put("ok", true).put("code", "7").put("can_mark_sold", true)
            .put("outside_confirm_message", "آگهی بسته می‌شود.")
            .put("visits", JSONArray().put(JSONObject().put("visit_id", "v2").put("label", "رضا · بازدید 2026-11-10")
                .put("partner_name", "رضا").put("visit_date", "2026-11-10").put("confirm_message", "ثبت شود؟")))
        val options = OwnerSold.parseOptions(old)
        assertEquals(listOf("vinor", "outside", "not_final"), options.channels.map { it.value })
        assertTrue(options.channels.first().enabled)
        assertEquals(4, options.sources.size)
        assertEquals("ملک‌تان از چه راهی فروخته شد؟", OwnerSoldFlow(options).text("channel_question"))
        assertEquals("ثبت شود؟", options.visits.first().summary)
        assertEquals("2026-11-10", options.visits.first().dateLabel)
        assertEquals("آگهی بسته می‌شود.", options.outsideSummary)
        // old choices list (previous dialog) unchanged
        assertEquals(listOf("vinor", "outside"), options.choices.map { it.channel })
    }

    @Test
    fun successAndNoopParsing() {
        val ok = JSONObject().put("ok", true).put("sold", true).put("success_title", "فروش ثبت شد")
            .put("next_steps", JSONArray().put("مبلغ بلوکه‌شده به کیف پول شما برگشت.").put("آگهی بسته شد."))
        val s = OwnerSold.success(ok)
        assertEquals("فروش ثبت شد", s.title)
        assertEquals(2, s.steps.size)
        val old = OwnerSold.success(JSONObject().put("ok", true).put("sold", true).put("message", "ثبت شد."))
        assertEquals(listOf("ثبت شد."), old.steps)
        val noop = JSONObject().put("ok", true).put("sold", false).put("noop", true).put("message", "تغییری ثبت نشد.")
        assertTrue(OwnerSold.isNoop(noop))
        assertFalse(OwnerSold.succeeded(noop))
        assertEquals("تغییری ثبت نشد.", OwnerSold.message(noop))
        assertNull(null)
    }
}
