package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingTest {
    @After
    fun reset() {
        ListingMemory.clearSession()
    }

    @Test
    fun published_matchesAnyLegacyFlag() {
        val byExtras = JSONObject().put(
            "extras",
            JSONObject().put("published_for_sale", true),
        )
        assertTrue(Listings.published(byExtras))

        val bySource = JSONObject()
            .put("published_for_sale", false)
            .put("source", "my_property_published")
        assertTrue(Listings.published(bySource))

        val byOwner = JSONObject()
            .put("public", true)
            .put("is_express", false)
            .put("source", "my_property")
            .put("extras", JSONObject().put("listed_by_owner", true).put("published_for_sale", false))
        assertTrue(Listings.published(byOwner))

        val privateHome = JSONObject()
            .put("published_for_sale", false)
            .put("source", "my_property")
        assertFalse(Listings.published(privateHome))
        assertTrue(Listings.isPrivateOwnerHome(privateHome))

        val badgeOnly = JSONObject().put("badge_label", "منتشرشده")
        assertTrue(Listings.published(badgeOnly))
    }

    @Test
    fun align_writesEveryFlagTogetherAndKeepsPreviousCode() {
        val row = JSONObject()
            .put("code", "15")
            .put("title", "زمین")
            .put("payment_method", "توافقی")
            .put("extras", JSONObject().put("previous_code", "2"))
        Listings.align(row, true)
        assertTrue(row.getBoolean("published_for_sale"))
        assertTrue(row.getBoolean("public"))
        assertTrue(row.getBoolean("is_express"))
        assertFalse(row.getBoolean("owner_home"))
        assertEquals("my_property_published", row.getString("source"))
        assertEquals("express", row.getString("ad_type"))
        assertTrue(row.getJSONObject("extras").getBoolean("published_for_sale"))
        assertTrue(row.getJSONObject("extras").getBoolean("listed_by_owner"))
        assertEquals("2", row.getJSONObject("extras").getString("previous_code"))
        assertEquals("توافقی", row.getString("payment_method"))
        assertEquals("زمین", row.getString("title"))

        Listings.align(row, false)
        assertFalse(row.getBoolean("published_for_sale"))
        assertFalse(row.getBoolean("public"))
        assertFalse(row.getBoolean("is_express"))
        assertTrue(row.getBoolean("owner_home"))
        assertEquals("my_property", row.getString("source"))
        assertEquals("owner_home", row.getString("ad_type"))
        assertEquals("hidden", row.getString("express_status"))
        assertFalse(row.getJSONObject("extras").getBoolean("published_for_sale"))
        assertFalse(row.getJSONObject("extras").getBoolean("public"))
        assertEquals("2", row.getJSONObject("extras").getString("previous_code"))
        assertEquals("توافقی", row.getString("payment_method"))
    }

    @Test
    fun previousCode_resolvesTheRenamedListing() {
        val renamed = JSONObject()
            .put("code", "15")
            .put("title", "مالک دوم")
            .put("extras", JSONObject().put("previous_code", "2").put("published_for_sale", false))
        ListingRepository.ingest(renamed)
        assertEquals("15", ListingRepository.currentCode("2"))
        assertEquals("15", ListingRepository.resolve("2")!!.getString("code"))
        assertEquals("مالک دوم", ListingRepository.resolve("15")!!.getString("title"))

        val card = JSONObject().put("code", "2").put("title", "قدیم")
        Listings.adoptCode(card, renamed)
        assertEquals("15", card.getString("code"))
        assertEquals("2", card.getJSONObject("extras").getString("previous_code"))
        assertEquals("قدیم", card.getString("title"))
        assertEquals(listOf("15", "3"), Listings.retargetCode(listOf("2", "3", "2"), "2", "15"))
    }

    @Test
    fun publishAndStopRewriteEveryLocalCopy() {
        val detail = JSONObject()
            .put("ok", true)
            .put("http_status", 200)
            .put("code", "4")
            .put("title", "زمین")
            .put("published_for_sale", false)
            .put("source", "my_property")
        LandDetailFragment.remember("4", detail)
        MyPropertiesFragment.remember(
            JSONObject()
                .put("ok", true)
                .put("http_status", 200)
                .put(
                    "items",
                    JSONArray().put(
                        JSONObject().put("code", "4").put("title", "زمین").put("published_for_sale", false),
                    ),
                ),
        )
        DashboardFragment.rememberHome(
            JSONObject().put(
                "divar_pro_ads",
                JSONArray().put(JSONObject().put("code", "4").put("title", "زمین").put("published_for_sale", false)),
            ),
        )
        VinorLandCardCache.put("4", JSONObject().put("code", "4").put("published_for_sale", false))

        ListingMemory.syncPublished("4", true, null)
        assertPublishedEverywhere(true)
        assertEquals("my_property_published", LandDetailFragment.peekRemembered("4")!!.getString("source"))
        assertTrue(LandDetailFragment.peekRemembered("4")!!.getBoolean("is_express"))

        ListingMemory.syncPublished("4", false, null)
        assertPublishedEverywhere(false)
        assertEquals("my_property", LandDetailFragment.peekRemembered("4")!!.getString("source"))
        assertFalse(LandDetailFragment.peekRemembered("4")!!.getBoolean("is_express"))
        assertFalse(LandDetailFragment.peekRemembered("4")!!.getJSONObject("extras").getBoolean("published_for_sale"))
    }

    @Test
    fun publicCatalog_hidesPrivateHomesAndKeepsRenamedPublishedRows() {
        val privateHome = JSONObject().put("code", "3").put("title", "خصوصی").put("source", "my_property")
        assertTrue(SearchAds.fromObjects(JSONArray().put(privateHome)).isEmpty())
        val disagreed = JSONObject()
            .put("code", "7")
            .put("title", "ناسازگار")
            .put("source", "my_property_published")
            .put("published_for_sale", false)
        assertEquals(1, SearchAds.fromObjects(JSONArray().put(disagreed)).size)
        val hidden = PublicSearchCatalog.fromDetail(
            JSONObject().put("published_for_sale", false).put("code", "9").put("title", "خصوصی"),
            "",
            0,
        )
        assertNull(hidden)
        val stillPublic = PublicSearchCatalog.fromDetail(disagreed, "", 1)
        assertEquals("7", stillPublic?.token)
    }

    private fun assertPublishedEverywhere(published: Boolean) {
        assertEquals(published, Listings.published(LandDetailFragment.peekRemembered("4")!!))
        val item = MyPropertiesFragment.peekRemembered()!!.getJSONArray("items").getJSONObject(0)
        assertEquals(published, Listings.published(item))
        val homeAd = DashboardFragment.peekRememberedHome()!!.getJSONArray("divar_pro_ads").getJSONObject(0)
        assertEquals(published, Listings.published(homeAd))
        assertEquals(published, Listings.published(VinorLandCardCache.get("4")!!))
    }
}
