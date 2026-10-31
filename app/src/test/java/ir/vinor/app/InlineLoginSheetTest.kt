package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InlineLoginSheetTest {
    private fun src(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val d = dir ?: return@repeat
            listOf(File(d, rel), File(d, "app/$rel")).firstOrNull { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("missing $rel")
    }

    @Test
    fun openLoginShowsSheetOnSamePage() {
        val main = src("src/main/java/ir/vinor/app/MainActivity.kt")
        val open = main.substringAfter("fun openLogin(").substringBefore("fun continueAfterInlineLogin")
        assertTrue(open.contains("LoginSheet.show(supportFragmentManager, sessionExpired)"))
        assertTrue(main.contains("refreshCurrentDestination()"))
        assertFalse(main.substringAfter("fun continueAfterInlineLogin").substringBefore("fun loginSheetCancelled").contains("openPartnerHome()"))
    }

    @Test
    fun sheetReusesCanonicalLoginSteps() {
        val sheet = src("src/main/java/ir/vinor/app/LoginSheet.kt")
        assertTrue(sheet.contains("InlineTabLogin.show("))
        assertTrue(sheet.contains("continueAfterInlineLogin()"))
        val profile = src("src/main/java/ir/vinor/app/ProfileFragment.kt")
        assertFalse(profile.contains("action_profile_to_login"))
    }
}
