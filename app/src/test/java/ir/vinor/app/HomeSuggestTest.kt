package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSuggestTest {
    @Test
    fun files_preferCatalogThenSuggested() {
        val suggested = JSONArray().put(JSONObject().put("code", "S").put("title", "پیشنهادی"))
        val ads = JSONArray().put(JSONObject().put("token", "d1").put("source", "divar").put("title", "دیوار"))
        val both = JSONObject().put("suggested_files", suggested).put("divar_pro_ads", ads)
        assertEquals("دیوار", HomeSuggest.files(both).optJSONObject(0)?.optString("title"))
        val onlySuggested = JSONObject().put("suggested_files", suggested).put("divar_pro_ads", JSONArray())
        assertEquals("پیشنهادی", HomeSuggest.files(onlySuggested).optJSONObject(0)?.optString("title"))
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
        assertEquals("", HomeSuggest.badge(titled))
        assertEquals("آب و برق", HomeSuggest.cardSpec(titled))
        assertEquals("green", HomeSuggest.badgeTone("پیشنهادی"))

        val facts = JSONObject()
            .put("title", "فایل")
            .put("area", "500")
            .put("facts", JSONArray().put("کاربری مسکونی"))
            .put("listed_label", "ویژه")
        assertEquals("زمین ۵۰۰ متری", HomeSuggest.displayTitle(facts))
        assertEquals("کاربری مسکونی | ۵۰۰ م²", HomeSuggest.specLine(facts))
        assertEquals("کاربری مسکونی", HomeSuggest.cardSpec(facts))
        assertEquals("", HomeSuggest.cardSpec(JSONObject().put("title", "زمین ۵۰۰ متری")))
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
        assertEquals("L12", HomeSuggest.landCode(JSONObject().put("url", "/express/partner/lands/L12")))
        assertEquals("L12", HomeSuggest.landCode(JSONObject().put("code", "L12")))
        assertEquals("L12", HomeSuggest.landCode(JSONObject().put("land_code", "L12")))
    }

    @Test
    fun files_skipUnpublishedAndDuplicatesThenFallBack() {
        val hidden = JSONObject()
            .put("suggested_files", JSONArray().put(JSONObject().put("code", "A").put("published_for_sale", false)))
            .put("divar_pro_ads", JSONArray().put(JSONObject().put("token", "d1").put("title", "دیوار").put("source", "divar")))
        assertEquals("دیوار", HomeSuggest.files(hidden).optJSONObject(0)?.optString("title"))

        val mixed = JSONObject().put(
            "suggested_files",
            JSONArray()
                .put(JSONObject().put("code", "A").put("title", "مخفی").put("published_for_sale", false))
                .put(JSONObject().put("code", "B").put("title", "دوم"))
                .put(JSONObject().put("land_code", "B").put("title", "تکرار"))
                .put(JSONObject().put("title", "سوم").put("url", "/express/partner/lands/C")),
        )
        val files = HomeSuggest.files(mixed)
        assertEquals(2, files.length())
        assertEquals("دوم", files.optJSONObject(0)?.optString("title"))
        assertEquals("سوم", files.optJSONObject(1)?.optString("title"))
    }

    @Test
    fun commission_usesPriceTextOrExplicitPercent() {
        val fromText = JSONObject()
            .put("price", "۱٬۵۵۰٬۰۰۰٬۰۰۰ تومان")
            .put("commission_amount", 31_000_000L)
        assertEquals(2, HomeSuggest.commissionPercent(fromText))
        assertEquals("۲٪ پورسانت", HomeSuggest.commissionLabel(fromText))
        val explicit = JSONObject()
            .put("price_toman", 1_000_000)
            .put("commission_amount", 10)
            .put("commission_pct", "3.2")
        assertEquals(3, HomeSuggest.commissionPercent(explicit))
        assertEquals(
            PartnerFormat.toman(1500) + " تومان",
            HomeSuggest.priceLine(JSONObject().put("price", "1500")),
        )
    }

    @Test
    fun displayTitle_keepsApartmentAndHouse() {
        assertEquals(
            "آپارتمان ۸۵ متری",
            HomeSuggest.displayTitle(JSONObject().put("title", "آپارتمان ۸۵ متری جردن")),
        )
        assertEquals(
            "خانه ۱۲۰ متری",
            HomeSuggest.displayTitle(JSONObject().put("title", "خانه ۱۲۰ متری دماوند")),
        )
    }

    @Test
    fun startScroll_keepsFirstCardAtTheRtlEdge() {
        assertEquals(400, HomeSuggest.startScrollX(700, 300, rtl = true))
        assertEquals(0, HomeSuggest.startScrollX(700, 300, rtl = false))
        assertEquals(0, HomeSuggest.startScrollX(200, 300, rtl = true))
    }

    @Test
    fun fingerprint_changesWhenPriceChanges() {
        val first = JSONArray().put(JSONObject().put("code", "B").put("title", "زمین").put("price_toman", 1000))
        val second = JSONArray().put(JSONObject().put("code", "B").put("title", "زمین").put("price_toman", 2000))
        assertTrue(HomeSuggest.fingerprint(first) != HomeSuggest.fingerprint(second))
    }
}
