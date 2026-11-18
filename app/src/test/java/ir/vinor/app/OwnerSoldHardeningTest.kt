package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** هم‌ارز سخت‌سازی سرور: بازگرداندن فروخته‌شده (M2)، هشدار کهربایی بررسی (H1)، تلاش دوباره (L4). */
class OwnerSoldHardeningTest {

    private fun archivedSold() = JSONObject()
        .put("code", "12").put("archived", true).put("archived_reason", "closed")
        .put("express_status", "sold").put("sold", true).put("can_restore", false)

    @Test
    fun m2_soldArchivedListingHasNoRestoreAction() {
        val sold = archivedSold()
        assertFalse(ListingArchive.canRestore(sold))
        assertFalse(ListingArchive.canToggle(sold))
        assertFalse(MyPropertyManage.actions(sold).contains(MyPropertyManage.Action.ARCHIVE))

        // Old server without can_restore: sold flag alone hides it.
        val legacy = JSONObject().put("archived", true).put("express_status", "sold")
        assertFalse(ListingArchive.canRestore(legacy))
        // Server says not restorable even if sold flag is missing.
        val flagOnly = JSONObject().put("archived", true).put("can_restore", false)
        assertFalse(ListingArchive.canToggle(flagOnly))
    }

    @Test
    fun m2_plainArchivedAndActiveListingsKeepTheButton() {
        val archived = JSONObject().put("archived", true).put("can_restore", true)
        assertTrue(ListingArchive.canRestore(archived))
        assertTrue(MyPropertyManage.actions(archived).contains(MyPropertyManage.Action.ARCHIVE))
        assertEquals("بازگردوندن به فعال", MyPropertyManage.label(MyPropertyManage.Action.ARCHIVE, archived = true))

        val active = JSONObject().put("archived", false)
        assertFalse(ListingArchive.canRestore(active))
        assertTrue(ListingArchive.canToggle(active))
        assertEquals(MyPropertyManage.actions(), MyPropertyManage.actions(active))
    }

    @Test
    fun m2_soldLockedRestoreResponseIsRecognised() {
        val post = JSONObject().put("ok", false).put("http_status", 409).put("error_code", "sold_locked")
            .put("error", "این آگهی فروخته‌شده ثبت شده و به فهرست فعال برنمی‌گردد.")
        assertTrue(ListingArchive.isSoldLocked(post))
        assertEquals(ListingArchive.Outcome.FAILED, ListingArchive.outcome(post))
        assertEquals("این آگهی فروخته‌شده ثبت شده و به فهرست فعال برنمی‌گردد.", ListingArchive.message(post, "x"))
    }

    @Test
    fun soldResultHidesRestore() {
        val ok = JSONObject().put("ok", true).put("sold", true).put("archived", true).put("archived_reason", "closed")
        val card = OwnerSold.applyResult(JSONObject().put("code", "12").put("can_mark_sold", true), ok)
        assertFalse(ListingArchive.canRestore(card))
        assertFalse(MyPropertyManage.actions(card).contains(MyPropertyManage.Action.ARCHIVE))
        assertFalse(MyPropertyManage.actions(card).contains(MyPropertyManage.Action.SOLD))
    }

    @Test
    fun h1_amberWarningShownOnlyWhenOutsideGoesToReview() {
        val review = JSONObject().put("ok", true).put("code", "12")
            .put("review_required_if_outside", true)
            .put("review_warning", "بازاریاب وینور برای این ملک بازدید ثبت‌شده دارد؛ …")
            .put(
                "visits",
                JSONArray().put(
                    JSONObject().put("visit_id", "v9").put("status", "done_pending")
                        .put("label", "رضا · بازدید 2026-11-10 (تأیید انجام بازدید هم ثبت می‌شود)"),
                ),
            )
        val options = OwnerSold.parseOptions(review)
        assertTrue(options.reviewRequiredIfOutside)
        assertEquals("بازاریاب وینور برای این ملک بازدید ثبت‌شده دارد؛ …", options.reviewWarning)
        assertEquals("v9", options.choices.first().visitId)
        assertEquals(0xFFFCD34D.toInt(), OwnerSold.REVIEW_WARNING_COLOR)

        // Server flags review without text (older build): default copy, same as web.
        val bare = OwnerSold.parseOptions(JSONObject().put("review_required_if_outside", true))
        assertEquals(OwnerSold.DEFAULT_REVIEW_WARNING, bare.reviewWarning)

        val none = OwnerSold.parseOptions(JSONObject().put("review_required_if_outside", false).put("review_warning", "x"))
        assertFalse(none.reviewRequiredIfOutside)
        assertEquals("", none.reviewWarning)
    }

    @Test
    fun l4_dealStartedErrorShowsServerRetryMessage() {
        val post = JSONObject().put("ok", false).put("http_status", 400)
            .put("error_code", "wallet_insufficient").put("deal_started", true).put("retry", true)
            .put("error", "معامله با بازاریاب ثبت شد ولی پرداخت پورسانت انجام نشد. کیف پول را شارژ کنید، سپس دوباره «فروخته شد» را بزنید.")
            .put("shortfall", 1000)
        assertFalse(OwnerSold.succeeded(post))
        assertTrue(OwnerSold.dealStarted(post))
        assertTrue(OwnerSold.isWalletShort(post))
        assertTrue(OwnerSold.message(post).startsWith("معامله با بازاریاب ثبت شد"))
        assertFalse(OwnerSold.dealStarted(JSONObject().put("ok", false)))
    }

    @Test
    fun l1_csrfErrorFromServerIsShownInPersian() {
        val post = JSONObject().put("ok", false).put("http_status", 400).put("error_code", "csrf_failed")
            .put("error", "نشست صفحه منقضی شده است. صفحه را تازه کنید و دوباره تلاش کنید.")
        assertEquals("نشست صفحه منقضی شده است. صفحه را تازه کنید و دوباره تلاش کنید.", OwnerSold.message(post))
        // The APK posts JSON bodies only, which the server accepts without a CSRF token.
        val body = OwnerSold.body("12", OwnerSold.Choice(OwnerSold.CHANNEL_OUTSIDE, "", "x", "y"))
        assertEquals("""{"code":"12","channel":"outside"}""", body.toString())
    }
}
