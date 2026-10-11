package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAdsHtmlTest {
    @Test
    fun parse_readsLandCardAndKeepsPercentSeparateFromAmount() {
        val html = """
            <a class="vn-home-divar__card" href="/express/partner/lands/L9" data-land-code="L9" title="زمین">
              <img src="/img/a.jpg">
              <span class="vn-home-divar__name">زمین دماوند</span>
              <span class="vn-home-divar__price">۱۰۰ تومان</span>
              <span class="vn-home-divar__loc">در دماوند</span>
              <span class="vn-home-divar__comm">۲٪</span>
            </a>
            <a class="vn-home-divar__card" href="/express/partner/divar/ga7" data-divar-token="ga7">
              <span class="vn-home-divar__name">باغ</span>
              <span class="vn-home-divar__comm">۳٬۰۰۰٬۰۰۰٬۰۰۰</span>
            </a>
        """.trimIndent()
        val ads = HomeAdsHtml.parse(html)
        assertEquals(2, ads.length())
        val land = ads.getJSONObject(0)
        assertEquals("L9", land.optString("token"))
        assertEquals("vinor", land.optString("source"))
        assertEquals(2, land.optInt("commission_pct"))
        assertEquals(false, land.has("commission_amount"))
        val divar = ads.getJSONObject(1)
        assertEquals("ga7", divar.optString("token"))
        assertEquals("divar", divar.optString("source"))
        assertEquals(3_000_000_000L, divar.optLong("commission_amount"))
    }
}
