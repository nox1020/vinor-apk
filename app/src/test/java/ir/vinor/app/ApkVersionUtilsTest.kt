package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
