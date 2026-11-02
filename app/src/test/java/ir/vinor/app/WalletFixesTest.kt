package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** موارد ۵، ۶ و ۱۲ بازبینی کیف پول. */
class WalletFixesTest {
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
    fun defaultMinimumMatchesServer() {
        assertEquals(10_000_000L, WalletWithdraw.DEFAULT_MIN_WITHDRAWAL)
        assertNotNull(WalletWithdraw.amountError("5000000", 50_000_000L))
    }

    @Test
    fun walletIsNeverCachedAndWithdrawNeedsNetwork() {
        assertTrue(VinorCachePolicy.isSensitivePath("/express/partner/commissions/data"))
        assertFalse(VinorCachePolicy.isSafeGetApi("https://vinor.ir/express/partner/commissions/data"))
        val frag = src("src/main/java/ir/vinor/app/CommissionsFragment.kt")
        val submit = frag.substringAfter("private fun submitWithdraw()").substringBefore("private fun stableWithdrawRequestId")
        assertTrue(submit.indexOf("VinorNetworkStatus.isOnline") in 0 until submit.indexOf("postFinance("))
    }

    @Test
    fun retryReusesTheSameRequestId() {
        val frag = src("src/main/java/ir/vinor/app/CommissionsFragment.kt")
        assertTrue(frag.contains("stableWithdrawRequestId(\"\$amount|\${account.id}\")"))
        assertFalse(frag.substringAfter("private fun submitWithdraw()").substringBefore("private fun stableWithdrawRequestId")
            .contains("UUID.randomUUID()"))
    }
}
