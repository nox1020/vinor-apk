package ir.vinor.app

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
    fun failureMessage_hidesRawNotFound() {
        val missing = JSONObject().put("http_status", 404).put("error", "Not Found")
        assertEquals("حذف آگهی ناموفق بود.", MyPropertyManage.failureMessage(missing, "حذف آگهی ناموفق بود."))
        val server = JSONObject().put("http_status", 400).put("error", "قیمت نامعتبر است")
        assertEquals("قیمت نامعتبر است", MyPropertyManage.failureMessage(server, "خطا"))
    }
}
