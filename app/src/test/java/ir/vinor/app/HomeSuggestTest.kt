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
        assertEquals("در دماوند", HomeSuggest.subtitle(JSONObject().put("location", "در دماوند")))
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
