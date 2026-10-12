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
    fun deleteUsesJsonApi() {
        val body = MyPropertyManage.deleteBody("12")
        assertEquals(MyPropertyManage.DELETE_PATH, "/express/partner/api/my-property/delete")
        assertEquals("12", body.getString("code"))
        assertFalse(body.has("action"))
        assertFalse(body.has("csrf_token"))
        val ok = JSONObject().put("ok", true).put("http_status", 200).put("code", "12").put("message", "ملک حذف شد.")
        val missing = JSONObject().put("ok", false).put("http_status", 404).put("error", "Not Found")
        assertTrue(MyPropertyManage.deleteSucceeded(ok))
        assertFalse(MyPropertyManage.deleteSucceeded(missing))
        assertTrue(MyPropertyManage.deleteRouteMissing(missing))
        assertFalse(MyPropertyManage.deleteRouteMissing(ok))
        val gone = JSONObject()
            .put("ok", false)
            .put("http_status", 404)
            .put("error_code", "not_found")
            .put("error", "این ملک پیدا نشد.")
        assertFalse(MyPropertyManage.deleteRouteMissing(gone))
        assertTrue(MyPropertyManage.deleteNotFound(gone))
        assertFalse(MyPropertyManage.claimAction(true))
        assertTrue(MyPropertyManage.claimAction(false))
        assertTrue(MyPropertyManage.showPublishProgress(true))
        assertFalse(MyPropertyManage.showPublishProgress(false))
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
    }

    @Test
    fun unpublishedSnapshotSurvivesViewRecreation() {
        val card = JSONObject()
            .put("code", "4")
            .put("title", "زمین ویلایی")
            .put("published_for_sale", true)
            .put("badge_label", "منتشرشده")
        val post = JSONObject()
            .put("ok", true)
            .put("http_status", 200)
            .put("code", "4")
            .put("items", JSONArray().put(JSONObject().put("code", "4").put("published_for_sale", false)))
        val land = JSONObject()
            .put("ok", false)
            .put("http_status", 200)
            .put("published_for_sale", false)
            .put("can_publish", true)
        val next = MyPropertyManage.finishUnpublish(card, post, land)
        assertTrue(next != null)
        val restored = MyPropertyManage.readSnapshot(MyPropertyManage.commitSnapshot(next!!))
        assertFalse(MyPropertyManage.isPublished(restored))
        assertEquals("خصوصی", MyPropertyManage.statusOf(restored))
        assertEquals("زمین ویلایی", restored.getString("title"))
        assertTrue(restored.getBoolean("can_publish"))
    }

    @Test
    fun privateLandDetailUpdatesStatusWhenOkIsFalse() {
        val card = JSONObject()
            .put("code", "4")
            .put("title", "زمین ویلایی")
            .put("city", "تهران")
            .put("published_for_sale", true)
            .put("badge_label", "منتشرشده")
        val detail = JSONObject()
            .put("ok", false)
            .put("http_status", 200)
            .put("published_for_sale", false)
            .put("can_publish", true)
            .put("title", "")
        assertTrue(MyPropertyManage.detailAccepted(detail))
        val merged = MyPropertyManage.applyListingDetail(card, detail)
        assertFalse(merged.getBoolean("published_for_sale"))
        assertTrue(merged.getBoolean("can_publish"))
        assertEquals("خصوصی", MyPropertyManage.statusOf(merged))
        assertEquals("زمین ویلایی", merged.getString("title"))
        assertEquals("تهران", merged.getString("city"))
    }

    @Test
    fun alreadyPublishedMarksSnapshotLive() {
        val card = JSONObject().put("code", "4").put("published_for_sale", false).put("badge_label", "خصوصی")
        val post = JSONObject()
            .put("ok", false)
            .put("http_status", 400)
            .put("error_code", "already_published")
            .put("error", "این ملک قبلاً برای فروش منتشر شده است.")
        assertTrue(MyPropertyManage.absorbAlreadyPublished(card, post))
        assertTrue(MyPropertyManage.isPublished(card))
        assertEquals("منتشرشده", MyPropertyManage.statusOf(card))
        val other = JSONObject().put("ok", false).put("error_code", "payment_required")
        assertFalse(MyPropertyManage.absorbAlreadyPublished(JSONObject(card.toString()), other))
    }

    @Test
    fun savedEditKeepsPublishFlagAndUpdatesFields() {
        val card = JSONObject().put("code", "4").put("title", "عنوان قدیمی").put("published_for_sale", true)
        val body = JSONObject().put("code", "4").put("title", "عنوان تازه ده حرفی").put("price_total", "2500").put("city", "دماوند")
        val next = MyPropertyManage.applySavedEdit(card, body)
        val restored = MyPropertyManage.readSnapshot(MyPropertyManage.commitSnapshot(next))
        assertEquals("عنوان تازه ده حرفی", restored.getString("title"))
        assertEquals("دماوند", restored.getString("city"))
        assertEquals(2500L, restored.getLong("price_total"))
        assertTrue(restored.getBoolean("published_for_sale"))
    }

    @Test
    fun failureMessage_hidesRawNotFound() {
        val missing = JSONObject().put("http_status", 404).put("error", "Not Found")
        assertEquals("حذف آگهی ناموفق بود.", MyPropertyManage.failureMessage(missing, "حذف آگهی ناموفق بود."))
        val server = JSONObject().put("http_status", 400).put("error", "قیمت نامعتبر است")
        assertEquals("قیمت نامعتبر است", MyPropertyManage.failureMessage(server, "خطا"))
    }
}
