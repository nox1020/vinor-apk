package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * زیرصفحات بازشده از «وینور من» باید به همان تب برگردند؛
 * برچسب «ویرایش» از ردیف هویت حذف شده و ردیف همچنان قابل لمس است.
 */
class ProfileSubpageBackTest {
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val d = dir ?: return@repeat
            listOf(File(d, "src/main"), File(d, "app/src/main")).firstOrNull {
                File(it, "AndroidManifest.xml").isFile
            }?.let { return it }
            dir = d.parentFile
        }
        throw AssertionError("app/src/main not found")
    }

    private fun src(name: String) = root().resolve("java/ir/vinor/app/$name").readText()
    private fun layout(name: String) = root().resolve("res/layout/$name.xml").readText()

    @Test
    fun identityRowHasChevronButNoEditLabel() {
        val xml = layout("fragment_profile")
        val rowAt = xml.indexOf("android:id=\"@+id/profileRow\"")
        assertTrue(rowAt > 0)
        val rowEnd = xml.indexOf("android:id=\"@+id/profileWorkSummary\"", rowAt)
        val row = xml.substring(rowAt, if (rowEnd > 0) rowEnd else xml.length)
        assertFalse(row.contains("profileEditLabel"))
        assertFalse(row.contains("android:text=\"ویرایش\""))
        assertTrue(row.contains("@+id/profileEditChevron"))
        val kt = src("ProfileFragment.kt")
        assertTrue(kt.contains("contentDescription = \"ویرایش پروفایل،"))
        assertTrue(kt.contains("setOnClickListener { openProfileEdit() }"))
        assertFalse(kt.contains("profileEditLabel"))
    }

    @Test
    fun profileHeaderStillGoesHome() {
        val kt = src("ProfileFragment.kt")
        assertTrue(
            kt.contains("binding.profileBack.setOnClickListener { (activity as? MainActivity)?.openPartnerHome() }"),
        )
    }

    @Test
    fun walletAndCustomersKeepProfileOnBackStackWhenOpenedFromProfile() {
        val main = src("MainActivity.kt")
        assertTrue(main.contains("if (navController.currentDestination?.id == R.id.profileFragment)"))
        assertTrue(
            main.contains("navController.navigate(R.id.commissionsFragment)") &&
                main.contains("navigateTab(R.id.commissionsFragment)"),
        )
        assertTrue(
            main.contains("navController.navigate(R.id.customersFragment, args)") &&
                main.contains("navigateTab(R.id.customersFragment, args)"),
        )
        assertTrue(main.contains("fun goBackFromSubpage()"))
        assertTrue(main.contains("fun onVinorChatClosed()"))
        assertTrue(main.contains("pendingChatReturnToProfile"))
        assertTrue(main.contains("fun openVinorChat(returnToProfile: Boolean"))
        assertTrue(src("DashboardFragment.kt").contains("onVinorChatClosed()"))
        assertTrue(src("CommissionsFragment.kt").contains("goBackFromSubpage()"))
        assertTrue(src("CustomersFragment.kt").contains("goBackFromSubpage()"))
        assertTrue(src("NativePartnerFragment.kt").contains("goBackFromSubpage()"))
        assertTrue(src("ConsultantOnboardingFragment.kt").contains("goBackFromSubpage()"))
        assertTrue(src("MyPropertiesFragment.kt").contains("returnToVinorMan()"))
        assertTrue(src("NativePartnerFragment.kt").contains("PAGE_SETTINGS || page == PAGE_FAVORITES"))
        assertTrue(src("NativePartnerFragment.kt").contains("returnToVinorMan()"))
        assertTrue(src("ProfileFragment.kt").contains("openMyProperty()"))
        assertTrue(src("ProfileFragment.kt").contains("openNotifications()"))
        assertTrue(src("ProfileFragment.kt").contains("openVinorChat()"))
    }
}
