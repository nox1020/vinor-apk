package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerCrmTest {

    @Test
    fun hiddenWhenUnapproved_evenIfFlagOn() {
        assertFalse(PartnerCrm.showMenu(isApproved = false, profileFlag = true, homeFlag = true))
    }

    @Test
    fun profileFlagWinsOverHome() {
        assertTrue(PartnerCrm.showMenu(isApproved = true, profileFlag = true, homeFlag = false))
        assertFalse(PartnerCrm.showMenu(isApproved = true, profileFlag = false, homeFlag = true))
    }

    @Test
    fun homeFlagUsedWhenProfileOmitsKey() {
        assertTrue(PartnerCrm.showMenu(isApproved = true, profileFlag = null, homeFlag = true))
        assertFalse(PartnerCrm.showMenu(isApproved = true, profileFlag = null, homeFlag = false))
    }

    @Test
    fun hiddenWhenNeitherPayloadHasFlag() {
        assertFalse(PartnerCrm.showMenu(isApproved = true, profileFlag = null, homeFlag = null))
    }

    @Test
    fun freshHome_isFinalAndSkipsRefresh() {
        val on = PartnerCrm.decide(true, null, homeFlag = true, homeFresh = true, remembered = false)
        assertTrue(on.visible)
        assertFalse(on.refresh)
        val off = PartnerCrm.decide(true, null, homeFlag = false, homeFresh = true, remembered = true)
        assertFalse(off.visible)
        assertFalse(off.refresh)
    }

    @Test
    fun unknownFlag_showsRowImmediatelyAndRefreshes() {
        val decision = PartnerCrm.decide(true, null, homeFlag = null, homeFresh = false, remembered = null)
        assertTrue(decision.visible)
        assertTrue(decision.refresh)
    }

    @Test
    fun staleHomeFlag_isShownWhileRefreshRuns() {
        val on = PartnerCrm.decide(true, null, homeFlag = true, homeFresh = false, remembered = null)
        assertTrue(on.visible)
        assertTrue(on.refresh)
        val remembered = PartnerCrm.decide(true, null, homeFlag = null, homeFresh = false, remembered = true)
        assertTrue(remembered.visible)
        assertTrue(remembered.refresh)
    }

    @Test
    fun profileFlag_doesNotWaitForHome() {
        val decision = PartnerCrm.decide(true, profileFlag = true, homeFlag = null, homeFresh = false, remembered = null)
        assertTrue(decision.visible)
        assertFalse(decision.refresh)
    }
}
