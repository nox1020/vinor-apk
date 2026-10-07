package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FooterTabsTest {

    private val ids = FooterTabs.Ids(
        dashboard = 1,
        search = 2,
        profile = 3,
        myProperties = 4,
        propertyForm = 5,
    )

    @Test
    fun homeAndChat_followDashboardOnly() {
        assertEquals(FooterTabs.Tab.ADS, FooterTabs.selected(listOf(1), chatOpen = false, ids))
        assertEquals(FooterTabs.Tab.CHAT, FooterTabs.selected(listOf(1), chatOpen = true, ids))
        assertEquals(FooterTabs.Tab.ADS, FooterTabs.selected(emptyList(), chatOpen = false, ids))
    }

    @Test
    fun childOfHome_keepsAds_evenIfChatWasOpen() {
        val news = 9
        assertEquals(
            FooterTabs.Tab.ADS,
            FooterTabs.selected(listOf(1, news), chatOpen = true, ids),
        )
    }

    @Test
    fun searchStack_staysSearch() {
        val land = 8
        assertEquals(FooterTabs.Tab.SEARCH, FooterTabs.selected(listOf(1, 2), chatOpen = false, ids))
        assertEquals(FooterTabs.Tab.SEARCH, FooterTabs.selected(listOf(1, 2, land), chatOpen = false, ids))
    }

    @Test
    fun profileChildren_stayOnMe_includingMyProperties() {
        val notes = 7
        assertEquals(FooterTabs.Tab.ME, FooterTabs.selected(listOf(1, 3, notes), chatOpen = false, ids))
        assertEquals(FooterTabs.Tab.ME, FooterTabs.selected(listOf(1, 3, 4), chatOpen = false, ids))
    }

    @Test
    fun propertyScreensOpenedAsTabRoot_selectPost() {
        assertEquals(FooterTabs.Tab.POST, FooterTabs.selected(listOf(1, 4), chatOpen = false, ids))
        assertEquals(FooterTabs.Tab.POST, FooterTabs.selected(listOf(1, 5), chatOpen = false, ids))
    }
}
