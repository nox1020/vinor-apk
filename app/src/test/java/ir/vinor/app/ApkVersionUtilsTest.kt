package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkVersionUtilsTest {

    @Test
    fun serverNewer_comparesSemverParts() {
        assertTrue(ApkVersionUtils.isServerNewer("1.0.253", "1.0.250"))
        assertTrue(ApkVersionUtils.isServerNewer("1.0.253", "1.0"))
        assertFalse(ApkVersionUtils.isServerNewer("1.0.253", "1.0.253"))
        assertFalse(ApkVersionUtils.isServerNewer("1.0.253", "1.0.258"))
        assertFalse(ApkVersionUtils.isServerNewer("", "1.0.250"))
        assertFalse(ApkVersionUtils.isServerNewer("1.0.253", ""))
    }

    @Test
    fun versionCodeFromName_matchesGradleFormula() {
        assertEquals(10004L, ApkVersionUtils.versionCodeFromName("1.0.4"))
        assertEquals(10272L, ApkVersionUtils.versionCodeFromName("1.0.272"))
        assertEquals(10273L, ApkVersionUtils.versionCodeFromName("1.0.273"))
    }

    @Test
    fun remoteNewer_prefersVersionCode() {
        assertTrue(ApkVersionUtils.isRemoteNewer(10273, 10272, "1.0.273", "1.0.272"))
        assertFalse(ApkVersionUtils.isRemoteNewer(10272, 10272, "1.0.272", "1.0.272"))
        assertFalse(ApkVersionUtils.isRemoteNewer(10271, 10272, "1.0.271", "1.0.272"))
        // CI code can be unrelated to name — code still wins
        assertTrue(ApkVersionUtils.isRemoteNewer(5000, 4000, "1.0.1", "1.0.9"))
        assertFalse(ApkVersionUtils.isRemoteNewer(4000, 5000, "1.0.9", "1.0.1"))
    }

    @Test
    fun remoteNewer_fallsBackToNameWhenCodeMissing() {
        assertTrue(ApkVersionUtils.isRemoteNewer(0, 0, "1.0.273", "1.0.272"))
        assertTrue(ApkVersionUtils.isRemoteNewer(0, 10272, "1.0.273", "1.0.272"))
        assertFalse(ApkVersionUtils.isRemoteNewer(0, 0, "1.0.272", "1.0.272"))
    }
}

class VinorApkHashesTest {

    @Test
    fun sha256_knownVector() {
        val hex = VinorApkHashes.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }

    @Test
    fun matches_emptyExpectedAlwaysTrue() {
        val f = File.createTempFile("vinor-hash", ".bin")
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(VinorApkHashes.matches(f, ""))
        f.delete()
    }

    @Test
    fun matches_detectsMismatchAndMatch() {
        val f = File.createTempFile("vinor-hash", ".bin")
        f.writeText("abc", Charsets.UTF_8)
        val want = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(VinorApkHashes.matches(f, want))
        assertTrue(VinorApkHashes.matches(f, want.uppercase()))
        assertFalse(VinorApkHashes.matches(f, "00".repeat(32)))
        f.delete()
    }
}

class VinorAppUpdateRangeTest {

    @Test
    fun resume206_appends() {
        assertEquals(
            VinorAppUpdateRange.Action.APPEND,
            VinorAppUpdateRange.decide(8_000_000, 206, 12_000_000, 20_000_000),
        )
    }

    @Test
    fun serverIgnoresRange_restarts() {
        assertEquals(
            VinorAppUpdateRange.Action.RESTART,
            VinorAppUpdateRange.decide(8_000_000, 200, 20_000_000, 20_000_000),
        )
    }

    @Test
    fun fresh200_startsFromZeroAsAppend() {
        assertEquals(
            VinorAppUpdateRange.Action.APPEND,
            VinorAppUpdateRange.decide(0, 200, 20_000_000, 20_000_000),
        )
    }

    @Test
    fun rangeComplete_when416AndFull() {
        assertEquals(
            VinorAppUpdateRange.Action.COMPLETE,
            VinorAppUpdateRange.decide(20_000_000, 416, 0, 20_000_000),
        )
    }

    @Test
    fun http500_transient() {
        assertEquals(
            VinorAppUpdateRange.Action.FAIL_TRANSIENT,
            VinorAppUpdateRange.decide(100, 500, -1, 0),
        )
        assertEquals(
            VinorAppUpdateRange.Action.FAIL_TRANSIENT,
            VinorAppUpdateRange.decide(0, 404, 0, 0),
        )
    }

    @Test
    fun rangeHeader() {
        assertEquals("bytes=8388608-", VinorAppUpdateRange.rangeHeader(8_388_608))
        assertEquals(null, VinorAppUpdateRange.rangeHeader(0))
    }
}
