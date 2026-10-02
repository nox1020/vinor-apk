package ir.vinor.app

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
}
