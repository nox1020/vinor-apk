package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ListingMemoryTest {

    @Before
    @After
    fun reset() {
        ListingMemory.clearSession()
    }

    @Test
    fun publishSuccessDoesNotReopenTheOldPrivateState() {
        val detail = land(published = false, canPublish = true)
        val list = portfolio(published = false)
        LandDetailFragment.remember("12", detail)
        LandDetailFragment.remember("99", land(published = false, canPublish = true).put("code", "99"))
        MyPropertiesFragment.remember(list)
        assertFalse(ListingMemory.detailOnOpen("12", online = true, disk = null, cleared = emptyList())!!.optBoolean("published_for_sale"))
        assertTrue(ListingMemory.detailOnOpen("12", online = false, disk = null, cleared = emptyList())!!.optBoolean("can_publish"))

        DashboardFragment.rememberHome(JSONObject().put("lands", JSONArray().put(JSONObject().put("code", "12").put("published_for_sale", false))))
        val cleared = ListingMemory.invalidateLand("12", null, MyPropertyManage.landDataPath("12"))
        assertReopenCleared("12", detail, list, cleared)
        assertNull(ListingMemory.homeOnOpen(online = false, disk = JSONObject().put("stale", true), cleared = cleared))
        assertTrue(cleared.contains(DashboardFragment.HOME_DATA))
        assertFalse(LandDetailFragment.peekRemembered("99")!!.optBoolean("published_for_sale"))
    }

    @Test
    fun unpublishSuccessDoesNotReopenTheOldLiveState() {
        val detail = land(published = true, canPublish = false)
        val list = portfolio(published = true)
        LandDetailFragment.remember("12", detail)
        MyPropertiesFragment.remember(list)
        assertTrue(ListingMemory.detailOnOpen("12", online = true, disk = null, cleared = emptyList())!!.optBoolean("published_for_sale"))
        assertTrue(ListingMemory.portfolioOnOpen(online = false, disk = null, cleared = emptyList())!!.optJSONArray("items")!!.getJSONObject(0).optBoolean("published_for_sale"))

        val cleared = ListingMemory.invalidateLand("12", null)
        assertReopenCleared("12", detail, list, cleared)
    }

    @Test
    fun deleteSuccessDoesNotReopenTheRemovedLand() {
        val detail = land(published = true, canPublish = false)
        val list = portfolio(published = true)
        LandDetailFragment.remember("12", detail)
        MyPropertiesFragment.remember(list)

        val cleared = ListingMemory.invalidateLand("12", null)
        assertReopenCleared("12", detail, list, cleared)
        assertTrue(cleared.contains(MyPropertyManage.landDataPath("12")))
        assertTrue(cleared.contains(MyPropertyManage.PORTFOLIO_PATH))
    }

    @Test
    fun logoutAndSessionExpiryClearEveryRememberedLand() {
        LandDetailFragment.remember("12", land(published = false, canPublish = true))
        LandDetailFragment.remember("99", land(published = true, canPublish = false).put("code", "99"))
        MyPropertiesFragment.remember(portfolio(published = true))
        DashboardFragment.rememberHome(JSONObject().put("ok", true))

        ListingMemory.clearSession()

        assertNull(LandDetailFragment.peekRemembered("12"))
        assertNull(LandDetailFragment.peekRemembered("99"))
        assertNull(MyPropertiesFragment.peekRemembered())
        assertNull(DashboardFragment.peekRememberedHome())
        assertNull(ListingMemory.detailOnOpen("12", online = true, disk = land(false, true), cleared = emptyList()))
        assertNull(ListingMemory.portfolioOnOpen(online = true, disk = portfolio(true), cleared = emptyList()))
    }

    private fun assertReopenCleared(code: String, detail: JSONObject, list: JSONObject, cleared: List<String>) {
        assertNull(ListingMemory.detailOnOpen(code, online = true, disk = detail, cleared = cleared))
        assertNull(ListingMemory.detailOnOpen(code, online = false, disk = detail, cleared = cleared))
        assertNull(ListingMemory.portfolioOnOpen(online = true, disk = list, cleared = cleared))
        assertNull(ListingMemory.portfolioOnOpen(online = false, disk = list, cleared = cleared))
    }

    private fun land(published: Boolean, canPublish: Boolean): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("http_status", 200)
            .put("code", "12")
            .put("published_for_sale", published)
            .put("can_publish", canPublish)
    }

    private fun portfolio(published: Boolean): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("http_status", 200)
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("code", "12")
                        .put("published_for_sale", published)
                        .put("can_publish", !published),
                ),
            )
    }
}
