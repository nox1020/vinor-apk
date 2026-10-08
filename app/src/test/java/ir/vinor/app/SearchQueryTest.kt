package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    @Test
    fun persianDigits_becomeLatinInQueryString() {
        val query = SearchQuery(
            priceMin = SearchQuery.parseAmount("۲٬۰۰۰٬۰۰۰٬۰۰۰"),
            priceMax = SearchQuery.parseAmount("۵،۰۰۰،۰۰۰،۰۰۰"),
            areaMin = SearchQuery.parseAmount("۷۵"),
            areaMax = SearchQuery.parseAmount("١٢٠"),
            sort = SearchSort.PRICE_ASC,
        )
        assertEquals(2_000_000_000L, query.priceMin)
        assertEquals(5_000_000_000L, query.priceMax)
        assertEquals(75L, query.areaMin)
        assertEquals(120L, query.areaMax)
        assertEquals(5_000_000_000L, SearchQuery.parseAmount("٥٬٠٠٠٬٠٠٠٬٠٠٠"))
        assertEquals(2_000_000_000L, SearchQuery.parseAmount("2,000,000,000"))
        assertNull(SearchQuery.parseAmount(""))
        assertNull(SearchQuery.parseAmount("توافقی"))
        assertNull(SearchQuery.parseAmount("abc"))
        assertEquals(
            "price_min=2000000000&price_max=5000000000&area_min=75&area_max=120&sort=price_asc",
            query.toQueryString(),
        )
        assertFalse(query.toQueryString().contains("deal"))
    }

    @Test
    fun minGreaterThanMax_isSwapped() {
        val query = SearchQuery(priceMin = 5_000_000_000, priceMax = 2_000_000_000, areaMin = 120, areaMax = 75)
        assertEquals(
            "price_min=2000000000&price_max=5000000000&area_min=75&area_max=120",
            query.toQueryString(),
        )
        assertEquals("۲–۵ میلیارد", query.priceChipLabel())
        assertEquals("۷۵–۱۲۰ متر", query.areaChipLabel())
    }

    @Test
    fun newestSort_isOmitted() {
        assertEquals("", SearchQuery(sort = SearchSort.NEWEST).toQueryString())
        val query = SearchQuery(q = "ونک", city = "تهران", sort = SearchSort.NEWEST)
        val wire = query.toQueryString()
        assertEquals("q=ونک&city=تهران", wire)
        assertFalse(wire.contains("sort"))
        assertFalse(wire.contains("deal"))
    }

    @Test
    fun clearAll_keepsDealAndCity() {
        val query = SearchQuery(
            q = "پارک",
            city = "تهران",
            districts = listOf("ونک", "جردن"),
            priceMin = 2_000_000_000,
            priceMax = 5_000_000_000,
            areaMin = 75,
            areaMax = 120,
            sort = SearchSort.PRICE_DESC,
        )
        val cleared = query.clearAll()
        assertEquals("buy", cleared.deal)
        assertEquals("تهران", cleared.city)
        assertEquals("", cleared.q)
        assertTrue(cleared.districts.isEmpty())
        assertNull(cleared.priceMin)
        assertNull(cleared.priceMax)
        assertNull(cleared.areaMin)
        assertNull(cleared.areaMax)
        assertEquals(SearchSort.NEWEST, cleared.sort)
        assertEquals("city=تهران", cleared.toQueryString())
        assertFalse(cleared.toQueryString().contains("deal"))
        assertFalse(cleared.hasClearableFilters())
    }

    @Test
    fun chipLabels_usePersianDigitsAndUnits() {
        assertEquals(
            "۲–۵ میلیارد",
            SearchQuery(priceMin = 2_000_000_000, priceMax = 5_000_000_000).priceChipLabel(),
        )
        assertEquals("تا ۵ میلیارد", SearchQuery(priceMax = 5_000_000_000).priceChipLabel())
        assertEquals("از ۲ میلیارد", SearchQuery(priceMin = 2_000_000_000).priceChipLabel())
        assertEquals("از ۲٫۵ میلیارد", SearchQuery(priceMin = 2_500_000_000).priceChipLabel())
        assertEquals("تا ۵۰۰ میلیون", SearchQuery(priceMax = 500_000_000).priceChipLabel())
        assertEquals("۷۵–۱۲۰ متر", SearchQuery(areaMin = 75, areaMax = 120).areaChipLabel())
        assertEquals("ونک +۲", SearchQuery(districts = listOf("ونک", "جردن", "الهیه")).districtChipLabel())
    }

    @Test
    fun reducer_emptyListAndNetworkError() {
        val loading = SearchStateReducer.loading()
        assertEquals(SearchPhase.LOADING, loading.phase)

        val empty = SearchStateReducer.onLoaded(emptyList(), filtersActive = true)
        assertEquals(SearchPhase.EMPTY, empty.phase)
        assertTrue(empty.items.isEmpty())
        assertTrue(empty.filtersActive)

        val ad = SearchAd(title = "خانه", priceRaw = "۳٬۰۰۰٬۰۰۰٬۰۰۰", index = 0)
        val content = SearchStateReducer.onLoaded(listOf(ad), filtersActive = false)
        assertEquals(SearchPhase.CONTENT, content.phase)
        assertEquals(listOf(ad), content.items)

        val failed = SearchStateReducer.onNetworkError(content)
        assertEquals(SearchPhase.ERROR, failed.phase)
        assertEquals(listOf(ad), failed.items)

        val failedFresh = SearchStateReducer.onNetworkError(loading)
        assertEquals(SearchPhase.ERROR, failedFresh.phase)
        assertTrue(failedFresh.items.isEmpty())

        val failedEmpty = SearchStateReducer.onNetworkError(empty)
        assertEquals(SearchPhase.ERROR, failedEmpty.phase)
        assertTrue(failedEmpty.items.isEmpty())
    }

    @Test
    fun acceptance_priceRangeAscending_noPriceLast() {
        val query = SearchQuery(
            priceMin = 2_000_000_000,
            priceMax = 5_000_000_000,
            sort = SearchSort.PRICE_ASC,
        )
        assertEquals(
            "price_min=2000000000&price_max=5000000000&sort=price_asc",
            query.toQueryString(),
        )
        assertEquals("۲–۵ میلیارد", query.priceChipLabel())

        val ads = listOf(
            SearchAd(title = "گران", priceRaw = "۶٬۰۰۰٬۰۰۰٬۰۰۰", index = 0),
            SearchAd(title = "توافقی", priceRaw = "توافقی", index = 1),
            SearchAd(title = "میانه", priceRaw = "۳٬۰۰۰٬۰۰۰٬۰۰۰", index = 2),
            SearchAd(title = "ارزان", priceRaw = "۱٬۰۰۰٬۰۰۰٬۰۰۰ تومان", index = 3),
            SearchAd(title = "چهار", priceRaw = "۴٬۰۰۰٬۰۰۰٬۰۰۰", index = 4),
            SearchAd(title = "بدون", priceRaw = "", index = 5),
        )
        assertNull(SearchAds.parseToman("توافقی"))
        assertNull(SearchAds.parseToman("قیمت توافقی"))
        assertEquals(3_000_000_000L, SearchAds.parseToman("۳٬۰۰۰٬۰۰۰٬۰۰۰"))

        val filtered = SearchAds.apply(ads, query)
        assertEquals(listOf("میانه", "چهار"), filtered.map { it.title })

        val sorted = SearchAds.apply(ads, SearchQuery(sort = SearchSort.PRICE_ASC))
        assertEquals(listOf("ارزان", "میانه", "چهار", "گران", "توافقی", "بدون"), sorted.map { it.title })

        val descending = SearchAds.apply(ads, SearchQuery(sort = SearchSort.PRICE_DESC))
        assertEquals("گران", descending.first().title)
        assertEquals("بدون", descending.last().title)

        val areas = listOf(
            SearchAd(title = "کوچک", areaRaw = "۶۰ متر", priceRaw = "۳٬۰۰۰٬۰۰۰٬۰۰۰", index = 0),
            SearchAd(title = "مناسب", areaRaw = "۹۰", priceRaw = "۳٬۰۰۰٬۰۰۰٬۰۰۰", index = 1),
            SearchAd(title = "نامشخص", areaRaw = "", priceRaw = "۳٬۰۰۰٬۰۰۰٬۰۰۰", index = 2),
        )
        val byArea = SearchAds.apply(areas, SearchQuery(areaMin = 75, areaMax = 120))
        assertEquals(listOf("مناسب"), byArea.map { it.title })
    }

    @Test
    fun defaultCity_isAllProvincesAndNewestKeepsServerOrder() {
        assertEquals("همه استان‌ها", SearchQuery.ALL_PROVINCES)
        assertEquals("", SearchQuery().city)
        assertEquals(SearchSort.NEWEST, SearchQuery().sort)
        val plain = listOf(
            SearchAd(title = "دوم", index = 1),
            SearchAd(title = "اول", index = 0),
        )
        assertEquals(listOf("اول", "دوم"), SearchAds.newest(plain).map { it.title })
        assertEquals(listOf("اول", "دوم"), SearchAds.apply(plain, SearchQuery()).map { it.title })
    }

    @Test
    fun newest_sortsCreatedAtDescending() {
        val ads = listOf(
            SearchAd(title = "قدیم", createdAt = 1_000, index = 0),
            SearchAd(title = "بدون", index = 1),
            SearchAd(title = "جدید", createdAt = 5_000, index = 2),
        )
        assertEquals(listOf("جدید", "قدیم", "بدون"), SearchAds.newest(ads).map { it.title })
        assertEquals(1_710_000_000_000L, SearchAds.parseTime("1710000000"))
        assertEquals(1_710_000_000_000L, SearchAds.parseTime("1710000000000"))
        assertTrue(SearchAds.parseTime("2026-03-01T10:00:00") > 0L)
        assertTrue(SearchAds.parseTime("2026-03-01") > 0L)
        assertEquals(0L, SearchAds.parseTime(""))
        assertEquals(0L, SearchAds.parseTime("null"))
    }

    @Test
    fun catalog_prefersPublishedSearchRowsNewestFirst() {
        val html = """
            <a class="vn-search-row" href="/express/partner/lands/20" data-land-code="20" data-city="اصفهان" data-created="2026-04-02T08:00:00">
              <img src="/a.jpg">
              <p class="vn-search-name">جدید اصفهان</p>
              <p class="vn-search-price">۴٬۰۰۰٬۰۰۰٬۰۰۰</p>
              <p class="vn-search-loc">در نقش‌جهان</p>
              <p class="vn-search-comm">پورسانت ۱۲۰۰۰۰۰</p>
              <span class="vn-search-shots">۲</span>
            </a>
            <a class="vn-search-row" href="/express/partner/divar/tok" data-divar-token="tok" data-city="تهران" data-created="2026-01-01">
              <p class="vn-search-name">قدیم تهران</p>
              <p class="vn-search-price">توافقی</p>
            </a>
            <a class="vn-search-row" href="/express/partner/lands/9" data-land-code="9" data-published-for-sale="false">
              <p class="vn-search-name">خصوصی</p>
            </a>
        """.trimIndent()
        val rows = SearchPageHtml.parse(html)
        assertEquals(listOf("جدید اصفهان", "قدیم تهران"), rows.map { it.title })
        assertEquals("vinor", rows[0].source)
        assertEquals("20", rows[0].token)
        assertEquals("اصفهان", rows[0].city)
        assertEquals("نقش‌جهان", rows[0].district)
        assertEquals(1_200_000, rows[0].commissionAmount)
        assertEquals(2, rows[0].imageCount)
        assertEquals("divar", rows[1].source)
        assertTrue(rows[0].createdAt > rows[1].createdAt)
        val home = listOf(SearchAd(title = "فقط خانه", index = 0))
        val chosen = SearchAds.catalog(rows, home, emptyList())
        assertEquals(listOf("جدید اصفهان", "قدیم تهران"), SearchAds.apply(chosen, SearchQuery()).map { it.title })
    }

    @Test
    fun homeJson_dropsUnpublishedAndSortsByCreatedAt() {
        val array = JSONArray()
            .put(
                JSONObject()
                    .put("title", "خصوصی")
                    .put("published_for_sale", false)
                    .put("token", "1"),
            )
            .put(
                JSONObject()
                    .put("title", "قدیمی")
                    .put("published_for_sale", true)
                    .put("created_at", "2026-01-01T00:00:00")
                    .put("token", "2")
                    .put("price_toman", 3_000_000_000L),
            )
            .put(
                JSONObject()
                    .put("title", "تازه")
                    .put("created_at", "2026-06-01T00:00:00")
                    .put("token", "3"),
            )
        val ads = SearchAds.fromObjects(array)
        assertEquals(listOf("قدیمی", "تازه"), ads.map { it.title })
        assertEquals(listOf("تازه", "قدیمی"), SearchAds.apply(ads, SearchQuery()).map { it.title })
        val again = SearchAds.dedupe(ads + ads.first())
        assertEquals(2, again.size)
    }
}
