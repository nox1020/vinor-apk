package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/** ردیف افقی فایل پیشنهادی خانه. `suggested_files` اگر پر باشد، وگرنه همان آگهی‌های `divar_pro_ads`. */
object HomeSuggest {
    const val LIMIT = 12

    fun files(home: JSONObject): JSONArray {
        val suggested = home.optJSONArray("suggested_files")
        if (suggested != null && suggested.length() > 0) return suggested
        return home.optJSONArray("divar_pro_ads") ?: JSONArray()
    }

    fun title(ad: JSONObject): String {
        val text = ad.optString("title").trim()
        return if (text.isEmpty() || text == "null") "فایل" else text
    }

    fun subtitle(ad: JSONObject): String {
        val price = ad.optString("price").trim()
        if (price.isNotEmpty() && price != "null") return price
        val toman = when (val raw = ad.opt("price_toman")) {
            is Number -> raw.toLong()
            is String -> PartnerApi.asciiDigits(raw).toLongOrNull() ?: 0L
            else -> ad.optLong("price_toman")
        }
        if (toman > 0L) return PartnerFormat.toman(toman) + " تومان"
        val location = ad.optString("location").trim()
        return if (location == "null") "" else location
    }

    fun imageUrl(ad: JSONObject): String = PartnerImages.coverUrl(ad)

    fun landCode(ad: JSONObject): String {
        val token = ad.optString("token").trim()
        if (token.isEmpty() || token == "null") return ""
        val source = ad.optString("source").trim()
        val url = ad.optString("url")
        return if (source == "divar" || url.contains("/divar/")) "divar:$token" else token
    }
}
