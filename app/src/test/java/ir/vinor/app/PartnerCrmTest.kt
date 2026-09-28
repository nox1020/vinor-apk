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
}
