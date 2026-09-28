package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun sanitize_blocksExternalHosts() {
        val safe = UrlUtils.sanitizeVinorHttpsUrl("https://evil.example/phish")
        assertTrue(safe.startsWith("https://vinor.ir"))
        assertFalse(safe.contains("evil.example"))
    }

    @Test
    fun sanitize_upgradesHttpAndAllowsVinor() {
        val u = UrlUtils.sanitizeVinorHttpsUrl("http://vinor.ir/express/partner/notifications")
        assertEquals("https://vinor.ir/express/partner/notifications", u)
    }

    @Test
    fun sanitize_relativePaths() {
        val u = UrlUtils.sanitizeVinorHttpsUrl("/express/partner/lands/A1")
        assertEquals("https://vinor.ir/express/partner/lands/A1", u)
    }

    @Test
    fun trustedHttps_rejectsHttp() {
        assertFalse(UrlUtils.isTrustedVinorHttps("http://vinor.ir/home"))
        assertTrue(UrlUtils.isTrustedVinorHttps("https://vinor.ir/home"))
    }

    @Test
    fun trustedHttps_allowsVinorHostVariants_rejectsLookalikes() {
        assertTrue(UrlUtils.isTrustedVinorHttps("https://VINOR.IR/express/partner/notifications"))
        assertTrue(UrlUtils.isTrustedVinorHttps("https://www.vinor.ir/help"))
        assertTrue(UrlUtils.isTrustedVinorHttps("https://cdn.vinor.ir/static/app.js"))
        assertTrue(UrlUtils.isTrustedVinorHttps("https://vinor.ir/express/partner/login?next=/express/partner/profile"))
        assertFalse(UrlUtils.isTrustedVinorHttps("https://vinor.ir.evil.com/express/partner/"))
        assertFalse(UrlUtils.isTrustedVinorHttps("https://notvinor.ir/"))
        assertFalse(UrlUtils.isTrustedVinorHttps("https://vinor.ir@evil.com/path"))
        assertFalse(UrlUtils.isTrustedVinorHttps("https://evil.example/phish"))
        assertFalse(UrlUtils.isTrustedVinorHttps("javascript:https://vinor.ir"))
        assertFalse(UrlUtils.isTrustedVinorHttps("data:text/html,https://vinor.ir"))
        assertFalse(UrlUtils.isTrustedVinorHttps(""))
        assertFalse(UrlUtils.isTrustedVinorHttps("   "))
    }

    @Test
    fun safeDownload_allowsUploadsApk_blocksApi() {
        assertTrue(UrlUtils.isSafePublicDownloadUrl("https://vinor.ir/uploads/apk/vinor_1.0.1.apk"))
        assertTrue(UrlUtils.isSafePublicDownloadUrl("https://vinor.ir/d"))
        assertTrue(UrlUtils.isSafePublicDownloadUrl("https://vinor.ir/d/"))
        assertTrue(UrlUtils.isApkDownloadUrl("https://vinor.ir/d/"))
        assertTrue(UrlUtils.isSafePublicDownloadUrl("https://vinor.ir/d/vinor-1.0.253.apk"))
        assertTrue(UrlUtils.isApkDownloadUrl("https://vinor.ir/d/vinor-1.0.253.apk"))
        assertFalse(UrlUtils.isSafePublicDownloadUrl("https://vinor.ir/api/express/partner/secret"))
        assertFalse(UrlUtils.isSafePublicDownloadUrl("https://evil.test/uploads/x.apk"))
    }
}
