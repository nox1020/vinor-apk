package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ورود یک صفحهٔ داخلی واحد است (نه مودال) و بعد از ورود به صفحهٔ مبدأ برمی‌گردد. */
class LoginPageReturnTest {
    private fun src(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val d = dir ?: return@repeat
            listOf(File(d, rel), File(d, "app/$rel")).firstOrNull { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("missing $rel")
    }

    private val main get() = src("src/main/java/ir/vinor/app/MainActivity.kt")

    @Test
    fun openLoginNavigatesToTheSingleLoginPage() {
        val open = main.substringAfter("fun openLogin(").substringBefore("fun continueAfterLogin")
        assertTrue(open.contains("navController.navigate(R.id.loginStep1Fragment, args)"))
        assertTrue(open.contains("captureAuthReturn()"))
        assertFalse(main.contains("LoginSheet"))
        assertFalse(File(File(System.getProperty("user.dir") ?: "."), "src/main/java/ir/vinor/app/LoginSheet.kt").exists())
    }

    @Test
    fun successReturnsToOriginBeforeFallingBackHome() {
        val cont = main.substringAfter("fun continueAfterLogin()").substringBefore("private fun returnFromLogin")
        assertTrue(cont.indexOf("returnFromLogin()") in 1 until cont.indexOf("openPartnerHome()"))
        val step2 = src("src/main/java/ir/vinor/app/LoginStep2Fragment.kt")
        assertTrue(step2.contains("host.continueAfterLogin()"))
    }

    @Test
    fun guestTabsOpenTheLoginPage() {
        for (fn in listOf("private fun openCustomers(", "private fun openFollowTab(")) {
            val body = main.substringAfter(fn).substringBefore("\n    }\n")
            assertTrue(fn, body.contains("openLogin()"))
        }
    }
}
