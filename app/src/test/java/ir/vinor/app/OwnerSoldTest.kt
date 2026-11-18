package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerSoldTest {

    @Test
    fun soldRow_onlyWhenServerSaysOwnerCanMarkSold() {
        val owner = JSONObject().put("code", "12").put("can_mark_sold", true)
        assertEquals(MyPropertyManage.Action.SOLD, MyPropertyManage.actions(owner).last())
        assertEquals("فروخته شد", MyPropertyManage.label(MyPropertyManage.Action.SOLD))

        val notOwner = JSONObject().put("code", "12")
        assertFalse(MyPropertyManage.actions(notOwner).contains(MyPropertyManage.Action.SOLD))
        val alreadySold = JSONObject().put("can_mark_sold", true).put("express_status", "sold")
        assertFalse(MyPropertyManage.actions(alreadySold).contains(MyPropertyManage.Action.SOLD))
        // The default list (Divar order) is unchanged.
        assertFalse(MyPropertyManage.actions().contains(MyPropertyManage.Action.SOLD))
    }

    @Test
    fun options_listVisitsThenOutside() {
        val obj = JSONObject()
            .put("ok", true)
            .put("code", "12")
            .put("can_mark_sold", true)
            .put("question", "ملک از طریق بازدید بازاریاب وینور فروخته شد؟")
            .put("outside_label", "خودم بیرون از وینور فروختم")
            .put("outside_confirm_message", "پورسانت تا بررسی پشتیبانی آزاد نمی‌شود.")
            .put(
                "visits",
                JSONArray().put(
                    JSONObject()
                        .put("visit_id", "v1")
                        .put("label", "رضا · بازدید 2026-11-10")
                        .put("confirm_message", "فروش از طریق بازدید رضا ثبت شود؟"),
                ),
            )
        val options = OwnerSold.parseOptions(obj)
        assertEquals(2, options.choices.size)
        assertEquals(OwnerSold.CHANNEL_VINOR, options.choices[0].channel)
        assertEquals("v1", options.choices[0].visitId)
        assertEquals("از طریق بازدید رضا · بازدید 2026-11-10", options.choices[0].label)
        assertEquals(OwnerSold.CHANNEL_OUTSIDE, options.choices[1].channel)
        assertTrue(options.choices[1].confirmMessage.contains("بررسی"))

        val vinor = OwnerSold.body("12", options.choices[0])
        assertEquals("vinor", vinor.getString("channel"))
        assertEquals("v1", vinor.getString("visit_id"))
        val outside = OwnerSold.body(" 12 ", options.choices[1])
        assertEquals("12", outside.getString("code"))
        assertEquals("outside", outside.getString("channel"))
        assertFalse(outside.has("visit_id"))
        assertEquals("/express/partner/api/my-property/sold-options?code=12", OwnerSold.optionsPath("12"))
    }

    @Test
    fun options_withoutVisitsStillOfferOutside() {
        val options = OwnerSold.parseOptions(JSONObject().put("ok", true).put("code", "3"))
        assertEquals(1, options.choices.size)
        assertEquals(OwnerSold.DEFAULT_OUTSIDE_LABEL, options.choices[0].label)
        assertEquals(OwnerSold.DEFAULT_QUESTION, options.question)
    }

    @Test
    fun result_closesCardAndMessagesComeFromServer() {
        val ok = JSONObject()
            .put("ok", true).put("sold", true).put("archived", true)
            .put("archived_reason", "closed").put("message", "آگهی فروخته‌شده ثبت و بسته شد.")
        assertTrue(OwnerSold.succeeded(ok))
        val card = OwnerSold.applyResult(JSONObject().put("code", "12").put("can_mark_sold", true), ok)
        assertTrue(OwnerSold.isSold(card))
        assertFalse(OwnerSold.canMarkSold(card))
        assertTrue(ListingArchive.isArchived(card))
        assertEquals("آگهی فروخته‌شده ثبت و بسته شد.", OwnerSold.message(ok))

        val forbidden = JSONObject().put("ok", false).put("http_status", 403).put("error_code", "forbidden")
        assertFalse(OwnerSold.succeeded(forbidden))
        assertEquals("فقط مالک آگهی می‌تواند آن را فروخته‌شده اعلام کند.", OwnerSold.message(forbidden))
        val offline = JSONObject().put("ok", false).put("offline", true).put("http_status", 0)
        assertEquals("برای انجام این عملیات اینترنت لازم است.", OwnerSold.message(offline))
        val wallet = JSONObject().put("ok", false).put("http_status", 400)
            .put("error_code", "wallet_insufficient").put("error", "کیف پول را شارژ کنید.")
        assertTrue(OwnerSold.isWalletShort(wallet))
        assertEquals("کیف پول را شارژ کنید.", OwnerSold.message(wallet))
    }

    @Test
    fun soldPostIsNeverQueuedOffline() {
        assertFalse(VinorCachePolicy.isQueueablePost("https://vinor.ir" + OwnerSold.SOLD_PATH))
    }
}
