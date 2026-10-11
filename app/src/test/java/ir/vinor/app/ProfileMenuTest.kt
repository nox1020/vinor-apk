package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileMenuTest {

    @Test
    fun rowsKey_staysWhenOnlyFlagsThatDoNotChangeRowsStay() {
        val base = row()
        assertEquals(base, row())
        assertNotEquals(base, row(showCustomers = false))
        assertNotEquals(base, row(lawyerOn = true))
        assertNotEquals(base, row(showAppUpdate = true, apkUrl = "https://vinor.ir/a.apk", apkVersion = "9"))
        assertNotEquals(base, row(isApproved = false))
        assertNotEquals(base, ProfileMenu.guestKey(plusOn = true, lawyerOn = false))
    }

    private fun row(
        isApproved: Boolean = true,
        isSpecial: Boolean = false,
        hasPendingApp: Boolean = false,
        plusOn: Boolean = true,
        lawyerOn: Boolean = false,
        showAppUpdate: Boolean = false,
        apkUrl: String = "",
        apkVersion: String = "",
        showCustomers: Boolean = true,
    ) = ProfileMenu.rowsKey(
        isApproved, isSpecial, hasPendingApp, plusOn, lawyerOn,
        showAppUpdate, apkUrl, apkVersion, showCustomers,
    )
}