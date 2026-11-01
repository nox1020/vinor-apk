package ir.vinor.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AuthResponseGuardTest {
    @Test fun guest401NeverExpiresOtp() {
        val guard = AuthResponseGuard()
        assertFalse(guard.shouldExpire(false, false, 401, false))
        assertFalse(guard.shouldExpire(false, false, 200, true))
        assertFalse(guard.shouldExpire(true, true, 401, false))
    }

    @Test fun authenticatedSessionFailuresExpire() {
        val guard = AuthResponseGuard()
        assertTrue(guard.shouldExpire(true, false, 401, false))
        assertTrue(guard.shouldExpire(true, false, 200, true))
        assertFalse(guard.shouldExpire(true, false, 403, false))
    }

    @Test fun lateResponseIsInvalidatedByOtpAndLogin() {
        val guard = AuthResponseGuard()
        val guest = guard.capture()
        guard.beginCredentials()
        val pending = guard.capture()
        assertFalse(guard.isCurrent(guest))
        guard.beginCredentials()
        assertFalse(guard.isCurrent(pending))
        val verifying = guard.capture()
        guard.stateChanged()
        assertFalse(guard.isCurrent(verifying))
    }

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val d = dir ?: return@repeat
            listOf(File(d, rel), File(d, "app/$rel")).firstOrNull { it.isFile }?.let {
                return it.readText()
            }
            dir = d.parentFile
        }
        throw AssertionError("missing $rel")
    }

    @Test fun guestTabsKeepAuthenticationInTheirOwnScreens() {
        val main = source("src/main/java/ir/vinor/app/MainActivity.kt")
        val customers = main.substringAfter("private fun openCustomers(uri: Uri)").substringBefore("\n    }")
        val follow = main.substringAfter("private fun openFollowTab()").substringBefore("\n    }")
        assertTrue(customers.contains("navigateTab(R.id.customersFragment, args)"))
        assertTrue(follow.contains("openNativePage(NativePartnerFragment.PAGE_REQUESTS, asTab = true)"))
        assertFalse(customers.contains("openLogin()"))
        assertFalse(follow.contains("openLogin()"))
    }

    @Test fun networkCodeProtectsOtpCookiesAndIgnoresStaleReplies() {
        val api = source("src/main/java/ir/vinor/app/PartnerApi.kt")
        assertTrue(api.contains("val requestEpoch = authGuard.capture()"))
        assertTrue(api.contains("if (!authGuard.isCurrent(requestEpoch)) return staleAuthResponse()"))
        assertTrue(api.contains("saveCookies(resp, requestEpoch)"))
        assertTrue(api.contains("authGuard.shouldExpire(hadSession, credential, code, silent)"))
        assertTrue(api.contains("if (isCredentialStartPath(url)) authGuard.beginCredentials()"))
    }
}
