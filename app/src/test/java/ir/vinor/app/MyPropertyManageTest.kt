package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyPropertyManageTest {

    @Test
    fun actions_matchDivarOrder() {
        val labels = MyPropertyManage.actions().map { MyPropertyManage.label(it) }
        assertEquals(
            listOf("نمایش ملک", "ویرایش", "تعیین قیمت", "حذف آگهی", "اشتراک‌گذاری", "انتشار برای فروش"),
            labels,
        )
    }

    @Test
    fun publishLabel_becomesStopWhenAlreadyPublished() {
        assertEquals("انتشار برای فروش", MyPropertyManage.label(MyPropertyManage.Action.PUBLISH, published = false))
        assertEquals("توقف انتشار فروش", MyPropertyManage.label(MyPropertyManage.Action.PUBLISH, published = true))
        val body = MyPropertyManage.unpublishBody("4")
        assertEquals("4", body.getString("code"))
        assertFalse(body.getBoolean("published_for_sale"))
        assertTrue(body.getBoolean("unpublish"))
        assertEquals("نقدی", body.getString("payment_method"))
        assertEquals("2", body.getString("commission_pct"))
        assertFalse(body.getBoolean("auto_send_all_partners"))
        assertEquals(
            "/express/partner/api/my-property/publish?published_for_sale=0",
            MyPropertyManage.UNPUBLISH_PATH,
        )
    }

    @Test
    fun unpublishBody_keepsListingPaymentSoServerValidationPasses() {
        val rows = JSONArray().put(JSONObject().put("label", "نحوه پرداخت").put("value", "توافقی"))
        val card = JSONObject().put("code", "1").put("title", "ملک من")
        val detail = JSONObject()
            .put("ok", true)
            .put("title", "عنوان سرور")
            .put("commission_pct", 0)
            .put("payment_method", JSONObject.NULL)
            .put("rows", rows)
        val merged = MyPropertyManage.mergeDetail(card, detail)
        assertEquals("توافقی", merged.getString("payment_method"))
        assertEquals("0", merged.getString("commission_pct"))
        val body = MyPropertyManage.unpublishBody("1", merged)
        assertEquals("توافقی", body.getString("payment_method"))
        assertEquals("0", body.getString("commission_pct"))
        assertFalse(body.getBoolean("published_for_sale"))
        val placeholder = JSONObject().put("payment_method", "انتخاب کنید")
        assertEquals("نقدی", MyPropertyManage.unpublishBody("1", placeholder).getString("payment_method"))
    }

    @Test
    fun unpublishFailure_hidesSuccessTextWhenListingStaysLive() {
        val republished = JSONObject().put("ok", true).put("message", "ملک برای فروش منتشر شد.")
        assertEquals("توقف انتشار ناموفق بود.", MyPropertyManage.unpublishFailure(republished, "توقف انتشار ناموفق بود."))
        val rejected = JSONObject().put("ok", false).put("http_status", 400).put("error", "نحوه پرداخت را انتخاب کنید")
        assertEquals("نحوه پرداخت را انتخاب کنید", MyPropertyManage.unpublishFailure(rejected, "توقف انتشار ناموفق بود."))
    }

    @Test
    fun unpublishStopped_requiresServerToClearTheFlag() {
        val ok = JSONObject().put("ok", true).put("http_status", 200)
        val missingRoute = JSONObject().put("ok", false).put("http_status", 404).put("error", "Not Found")
        val stillLive = JSONObject().put("ok", true).put("published_for_sale", true)
        val stopped = JSONObject().put("ok", true).put("published_for_sale", false)
        assertFalse(MyPropertyManage.unpublishStopped(missingRoute, stopped))
        assertFalse(MyPropertyManage.unpublishStopped(ok, stillLive))
        assertFalse(MyPropertyManage.unpublishStopped(ok, null))
        assertTrue(MyPropertyManage.unpublishStopped(ok, stopped))
        val cachedLive = JSONObject()
            .put("ok", true)
            .put("http_status", 200)
            .put("from_cache", true)
            .put("published_for_sale", true)
        val proved = JSONObject()
            .put("ok", true)
            .put("http_status", 200)
            .put("code", "4")
            .put("items", JSONArray().put(JSONObject().put("code", "4").put("published_for_sale", false)))
        assertFalse(MyPropertyManage.unpublishStopped(ok, cachedLive))
        assertTrue(MyPropertyManage.unpublishStopped(proved, null))
        assertTrue(MyPropertyManage.unpublishStopped(proved, cachedLive))
        assertFalse(MyPropertyManage.unpublishStopped(proved, stillLive))
    }

    @Test
    fun shareUrl_usesLandPath() {
        assertEquals(
            "https://vinor.ir/express/partner/lands/12",
            MyPropertyManage.shareUrl("12"),
        )
        assertTrue(MyPropertyManage.shareMessage(JSONObject().put("code", "9").put("title", "زمین")).contains("/lands/9"))
    }

    @Test
    fun priceBody_keepsCodeAndAmount() {
        val card = JSONObject()
            .put("code", "4")
            .put("title", "زمین ویلایی در جردن")
            .put("city", "تهران")
            .put("location", "جردن")
            .put("size", "1000")
        val body = MyPropertyManage.priceBody(card, 15_000_000_000L)
        assertEquals("4", body.getString("code"))
        assertEquals("15000000000", body.getString("price_total"))
        assertEquals("15000000", body.getString("price_per_meter"))
        assertEquals("تهران", body.getString("city"))
        assertEquals("توافقی", body.getString("payment_method"))
        assertEquals("0", body.getString("express_commission_pct"))
    }

    @Test
    fun priceBody_keepsExistingPaymentAndCommission() {
        val card = JSONObject()
            .put("code", "4")
            .put("payment_method", "نقدی")
            .put("commission_pct", 2)
            .put("size", "500")
        val body = MyPropertyManage.priceBody(card, 1_000_000L)
        assertEquals("نقدی", body.getString("payment_method"))
        assertEquals("2", body.getString("express_commission_pct"))
        assertEquals("2000", body.getString("price_per_meter"))
    }

    @Test
    fun status_followsPublishFlagNotStaleBadge() {
        val live = JSONObject().put("published_for_sale", true).put("badge_label", "خصوصی")
        assertTrue(MyPropertyManage.isPublished(live))
        assertEquals("منتشرشده", MyPropertyManage.statusOf(live))
        val fromBadge = JSONObject().put("badge_label", "منتشرشده")
        assertTrue(MyPropertyManage.isPublished(fromBadge))
        assertEquals("4", MyPropertyManage.codeOf(JSONObject().put("id", 4)))
    }

    @Test
    fun edit_keepsKnownTerms() {
        val snap = JSONObject().put("payment_method", "نقدی").put("commission_pct", "2")
        assertEquals("نقدی", MyPropertyManage.editPayment(snap, "", publicListing = false))
        assertEquals("معاوضه", MyPropertyManage.editPayment(snap, "معاوضه", publicListing = true))
        assertEquals("", MyPropertyManage.editPayment(null, "", publicListing = true))
        assertEquals("2", MyPropertyManage.editCommission(snap, "", publicListing = false))
        assertEquals("0", MyPropertyManage.editCommission(null, "", publicListing = false))
    }

    @Test
    fun publishAccepted_rejectsWhenListingStaysPrivate() {
        val ok = JSONObject().put("ok", true).put("http_status", 200)
        val stillPrivate = JSONObject().put("ok", true).put("published_for_sale", false)
        val live = JSONObject().put("ok", true).put("published_for_sale", true)
        val cachedPrivate = JSONObject()
            .put("ok", true)
            .put("published_for_sale", false)
            .put("from_cache", true)
        assertFalse(MyPropertyManage.publishAccepted(ok, stillPrivate))
        assertTrue(MyPropertyManage.publishAccepted(ok, live))
        assertTrue(MyPropertyManage.publishAccepted(ok, null))
        assertTrue(MyPropertyManage.publishAccepted(ok, cachedPrivate))
    }

    @Test
    fun mergeDetail_copiesImageAndSizeFromRows() {
        val card = JSONObject().put("code", "1").put("title", "ملک من")
        val detail = JSONObject()
            .put("ok", true)
            .put("images", JSONArray().put("/uploads/a.jpg"))
            .put("rows", JSONArray().put(JSONObject().put("label", "متراژ").put("value", "1000 متر")))
        val merged = MyPropertyManage.mergeDetail(card, detail)
        assertEquals("/uploads/a.jpg", merged.getString("image_url"))
        assertEquals("1000", merged.getString("size"))
    }

    @Test
    fun priceAmount_readsPersianDigits() {
        assertEquals(250_000L, MyPropertyManage.priceAmount("۲۵۰٬۰۰۰"))
        assertEquals(0L, MyPropertyManage.priceAmount(""))
    }

    @Test
    fun mergeDetail_fillsBlanksAndKeepsCardTitle() {
        val card = JSONObject().put("code", "1").put("title", "ملک من").put("price_total", 0)
        val detail = JSONObject()
            .put("ok", true)
            .put("title", "عنوان سرور")
            .put("city", "تهران")
            .put("price_toman", 1500)
            .put("published_for_sale", true)
            .put("size", 80)
        val merged = MyPropertyManage.mergeDetail(card, detail)
        assertEquals("ملک من", merged.getString("title"))
        assertEquals("تهران", merged.getString("city"))
        assertEquals(1500L, merged.getLong("price_total"))
        assertTrue(merged.getBoolean("published_for_sale"))
        assertEquals("80", merged.getString("size"))
    }

    @Test
    fun mergeDetail_ignoresFailedPayload() {
        val card = JSONObject().put("title", "ملک")
        val merged = MyPropertyManage.mergeDetail(card, JSONObject().put("ok", false))
        assertEquals("ملک", merged.getString("title"))
        assertFalse(merged.has("city"))
    }

    @Test
    fun deleteUsesThePropertyPageInsteadOfTheMissingApi() {
        val html = """
            <form action="/express/partner/my-property" method="post">
              <input type="hidden" name="csrf_token" value="tok">
              <input type="hidden" name="title" value="ذخیره">
              <button type="submit">ذخیره</button>
            </form>
            <form action="/express/partner/my-property" method="post">
              <input type="hidden" name="csrf_token" value="tok">
              <input type="hidden" name="code" value="12">
              <button type="submit" name="action" value="delete">حذف ملک</button>
            </form>
            <form action="/express/partner/my-property">
              <input type="hidden" name="code" value="99">
              <button type="submit" name="action" value="delete">حذف</button>
            </form>
        """.trimIndent()
        val call = MyPropertyManage.deleteCall(html, "12")
        assertEquals("/express/partner/my-property", call.path)
        assertEquals("tok", call.csrf)
        assertEquals("12", call.body.getString("code"))
        assertEquals("delete", call.body.getString("action"))
        assertFalse(call.body.has("title"))
        val other = MyPropertyManage.deleteCall(html, "99")
        assertEquals("99", other.body.getString("code"))
    }

    @Test
    fun deleteWithoutAFormStillPostsActionAndCode() {
        val html = """<meta name="csrf-token" content="abc">"""
        val call = MyPropertyManage.deleteCall(html, "4")
        assertEquals(MyPropertyManage.PAGE_PATH, call.path)
        assertEquals("abc", call.csrf)
        assertEquals("delete", call.body.getString("action"))
        assertEquals("4", call.body.getString("code"))
        assertFalse(call.path.contains("/api/my-property/delete"))
    }

    @Test
    fun removedFromPortfolioRequiresTheListingToDisappear() {
        val gone = JSONObject().put("ok", true).put("http_status", 200).put("items", JSONArray())
        val stay = JSONObject().put("ok", true).put("items", JSONArray().put(JSONObject().put("code", "4")))
        val other = JSONObject().put("ok", true).put("items", JSONArray().put(JSONObject().put("code", "9")))
        val property = JSONObject().put("has_property", true).put("property", JSONObject().put("code", "4"))
        val unshaped = JSONObject().put("ok", true).put("message", "ملک حذف شد.")
        val offline = JSONObject().put("offline", true).put("items", JSONArray())
        assertTrue(MyPropertyManage.removedFromPortfolio(gone, "4"))
        assertTrue(MyPropertyManage.removedFromPortfolio(other, "4"))
        assertFalse(MyPropertyManage.removedFromPortfolio(stay, "4"))
        assertFalse(MyPropertyManage.removedFromPortfolio(property, "4"))
        assertFalse(MyPropertyManage.removedFromPortfolio(unshaped, "4"))
        assertFalse(MyPropertyManage.removedFromPortfolio(offline, "4"))
        val optimistic = JSONObject().put("ok", true).put("message", "آگهی حذف شد.")
        assertEquals("حذف آگهی انجام نشد.", MyPropertyManage.deleteFailure(optimistic, "حذف آگهی انجام نشد."))
        val html = JSONObject().put("http_status", 200).put("ok", false).put("error", "پاسخ نامعتبر")
        assertEquals("حذف آگهی انجام نشد.", MyPropertyManage.deleteFailure(html, "حذف آگهی انجام نشد."))
        val fields = MyPropertyManage.formFields(MyPropertyManage.deleteCall("", "4"))
        assertEquals("delete", fields["action"])
        assertEquals("4", fields["code"])
        assertFalse(fields.containsKey("csrf_token"))
    }

    @Test
    fun failureMessage_hidesRawNotFound() {
        val missing = JSONObject().put("http_status", 404).put("error", "Not Found")
        assertEquals("حذف آگهی ناموفق بود.", MyPropertyManage.failureMessage(missing, "حذف آگهی ناموفق بود."))
        val server = JSONObject().put("http_status", 400).put("error", "قیمت نامعتبر است")
        assertEquals("قیمت نامعتبر است", MyPropertyManage.failureMessage(server, "خطا"))
    }
}
