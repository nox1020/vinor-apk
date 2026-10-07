package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSuggestTest {
    @Test
    fun files_preferSuggestedThenDivarAds() {
        val suggested = JSONArray().put(JSONObject().put("title", "پیشنهادی"))
        val ads = JSONArray().put(JSONObject().put("title", "دیوار"))
        val both = JSONObject().put("suggested_files", suggested).put("divar_pro_ads", ads)
        assertEquals("پیشنهادی", HomeSuggest.files(both).optJSONObject(0)?.optString("title"))
        val onlyAds = JSONObject().put("suggested_files", JSONArray()).put("divar_pro_ads", ads)
        assertEquals("دیوار", HomeSuggest.files(onlyAds).optJSONObject(0)?.optString("title"))
        assertEquals(0, HomeSuggest.files(JSONObject()).length())
    }

    @Test
    fun subtitle_usesPriceTextThenTomanThenLocation() {
        assertEquals(
            "۸۸۰ تومان",
            HomeSuggest.subtitle(JSONObject().put("price", "۸۸۰ تومان").put("price_toman", 1)),
        )
        assertTrue(HomeSuggest.subtitle(JSONObject().put("price_toman", 1500)).contains("تومان"))
        assertEquals("دماوند", HomeSuggest.subtitle(JSONObject().put("location", "در دماوند")))
    }

    @Test
    fun card_readsMetersNeighborhoodAndRealCommission() {
        val ad = JSONObject()
            .put("title", "550متر زمین در بافت ویلایی گرمابسرد دماوند")
            .put("location", "۳ ساعت پیش در دماوند")
            .put("price", "۱٬۵۵۰٬۰۰۰٬۰۰۰ تومان")
            .put("price_toman", 1_550_000_000L)
            .put("commission_amount", 31_000_000L)
        assertEquals("زمین ۵۵۰ متری", HomeSuggest.displayTitle(ad))
        assertEquals("دماوند - گرمابسرد", HomeSuggest.locationLine(ad))
        assertEquals("بافت ویلایی | ۵۵۰ م²", HomeSuggest.specLine(ad))
        assertEquals("۱٬۵۵۰٬۰۰۰٬۰۰۰ تومان", HomeSuggest.priceLine(ad))
        assertEquals(2, HomeSuggest.commissionPercent(ad))
        assertEquals("۲٪ پورسانت", HomeSuggest.commissionLabel(ad))
        assertEquals("جدید", HomeSuggest.badge(ad))
        assertEquals("new", HomeSuggest.badgeTone("جدید"))
    }

    @Test
    fun card_keepsFactsAreaAndRealBadgeOrPercent() {
        val titled = JSONObject()
            .put("title", "۲۰۰متربه بالا+آب برق+سند مادر +قسطی گرمابسرد")
            .put("location", "۲ هفته پیش در دماوند")
        assertEquals("زمین ۲۰۰ متری به بالا", HomeSuggest.displayTitle(titled))
        assertEquals("آب و برق | ۲۰۰ م²", HomeSuggest.specLine(titled))
        assertEquals("دماوند - گرمابسرد", HomeSuggest.locationLine(titled))
        assertEquals("پیشنهادی", HomeSuggest.badge(titled))
        assertEquals("green", HomeSuggest.badgeTone(HomeSuggest.badge(titled)))

        val facts = JSONObject()
            .put("title", "فایل")
            .put("area", "500")
            .put("facts", JSONArray().put("کاربری مسکونی"))
            .put("listed_label", "ویژه")
        assertEquals("زمین ۵۰۰ متری", HomeSuggest.displayTitle(facts))
        assertEquals("کاربری مسکونی | ۵۰۰ م²", HomeSuggest.specLine(facts))
        assertEquals("ویژه", HomeSuggest.badge(facts))
        assertEquals("gold", HomeSuggest.badgeTone("ویژه"))
        assertEquals(0, HomeSuggest.commissionPercent(JSONObject().put("price_toman", 100)))
        assertEquals("", HomeSuggest.commissionLabel(JSONObject().put("commission_amount", 10)))
    }

    @Test
    fun landCode_marksDivarAndKeepsVinor() {
        assertEquals(
            "divar:ga7",
            HomeSuggest.landCode(JSONObject().put("token", "ga7").put("source", "divar")),
        )
        assertEquals(
            "divar:ga7",
            HomeSuggest.landCode(
                JSONObject().put("token", "ga7").put("url", "/express/partner/divar/ga7"),
            ),
        )
        assertEquals("L12", HomeSuggest.landCode(JSONObject().put("token", "L12").put("source", "vinor")))
        assertEquals("", HomeSuggest.landCode(JSONObject().put("url", "/express/partner/lands/L12")))
    }
}
