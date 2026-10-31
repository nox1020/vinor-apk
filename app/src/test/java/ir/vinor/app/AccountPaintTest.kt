package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccountPaintTest {

    @Test
    fun paintedListIsStaleAfterTheSessionGenerationMoves() {
        assertFalse(AccountPaint.isStale(-1, NativePartnerFragment.generation()))
        val visitsBefore = NativePartnerFragment.generation()
        assertFalse(AccountPaint.isStale(visitsBefore, visitsBefore))
        NativePartnerFragment.clearRemembered()
        val visitsAfter = NativePartnerFragment.generation()
        assertTrue(visitsAfter != visitsBefore)
        assertTrue(AccountPaint.isStale(visitsBefore, visitsAfter))

        val notesBefore = NotificationsFragment.generation()
        NotificationsFragment.clearRemembered()
        assertTrue(AccountPaint.isStale(notesBefore, NotificationsFragment.generation()))
    }

    @Test
    fun loginDropsRememberedVisitsAndNotificationsBeforeNavigation() {
        val login = source("src/main/java/ir/vinor/app/LoginStep2Fragment.kt").readText()
        val forgetAt = login.indexOf("forgetPreviousAccount()")
        val navigateAt = login.indexOf("continueAfterLogin()")
        assertTrue(forgetAt >= 0)
        assertTrue(navigateAt > forgetAt)
        val host = source("src/main/java/ir/vinor/app/MainActivity.kt").readText()
        val start = host.indexOf("fun forgetPreviousAccount()")
        val end = host.indexOf("private fun clearPageMemory()")
        assertTrue(start >= 0 && end > start)
        val forget = host.substring(start, end)
        assertTrue(forget.contains("clearPageMemory()"))
        assertTrue(forget.contains("dropChatForSignedOutAccount()"))
        assertTrue(source("src/main/java/ir/vinor/app/NativePartnerFragment.kt").readText().contains("AccountPaint.isStale"))
        assertTrue(source("src/main/java/ir/vinor/app/NotificationsFragment.kt").readText().contains("AccountPaint.isStale"))
    }

    private fun source(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val roots = ArrayList<File>()
        var dir: File? = cwd
        repeat(5) {
            val current = dir ?: return@repeat
            roots.add(current)
            dir = current.parentFile
        }
        for (root in roots) {
            val direct = File(root, relative)
            if (direct.isFile) return direct
            val underApp = File(root, "app/$relative")
            if (underApp.isFile) return underApp
        }
        throw AssertionError("missing $relative from $cwd")
    }
}
