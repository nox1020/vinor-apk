package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VinorCachePolicyTest {

    @Test
    fun partnerPath_detected() {
        assertTrue(VinorCachePolicy.isPartnerPath("https://vinor.ir/express/partner/"))
        assertTrue(VinorCachePolicy.isPartnerPath("https://vinor.ir/express/partner/lands/A1"))
        assertFalse(VinorCachePolicy.isPartnerPath("https://vinor.ir/public"))
    }

    @Test
    fun staticAssets_detected() {
        assertTrue(VinorCachePolicy.isStaticAssetPath("https://vinor.ir/static/express-partner/express-nav.js"))
        assertTrue(VinorCachePolicy.isStaticAssetPath("https://vinor.ir/uploads/x.png"))
        assertFalse(VinorCachePolicy.isStaticAssetPath("https://vinor.ir/express/partner/api/notes"))
    }

    @Test
    fun safeGetApi_allowsDisplayEndpoints_blocksAuthAndVersion() {
        assertTrue(VinorCachePolicy.isSafeGetApi("https://vinor.ir/express/partner/api/notes"))
        assertTrue(VinorCachePolicy.isSafeGetApi("https://vinor.ir/express/partner/profile/data"))
        assertTrue(VinorCachePolicy.isSafeGetApi("https://vinor.ir/express/partner/commissions/data"))
        assertFalse(VinorCachePolicy.isSafeGetApi("https://vinor.ir/express/partner/api/verify"))
        assertFalse(VinorCachePolicy.isSafeGetApi("https://vinor.ir/api/express/app-version"))
        assertFalse(VinorCachePolicy.isSafeGetApi("https://evil.example/express/partner/api/notes"))
    }

    @Test
    fun sensitive_blocksFinanceAndAuth_allowsCommissionDataGet() {
        assertTrue(VinorCachePolicy.isSensitivePath("/express/partner/api/verify"))
        assertTrue(VinorCachePolicy.isSensitivePath("/express/partner/api/logout"))
        assertTrue(VinorCachePolicy.isSensitivePath("/express/partner/ai/chat"))
        assertFalse(VinorCachePolicy.isSensitivePath("/express/partner/commissions/data"))
        assertFalse(VinorCachePolicy.isSensitivePath("/express/partner/profile/data"))
    }

    @Test
    fun interceptCharset_binaryHasNoUtf8() {
        assertEquals("", VinorCachePolicy.interceptCharset("font/woff2", "utf-8"))
        assertEquals("", VinorCachePolicy.interceptCharset("image/png"))
        assertEquals("", VinorCachePolicy.interceptCharset("application/octet-stream", "utf-8"))
        assertEquals("utf-8", VinorCachePolicy.interceptCharset("text/css", "utf-8"))
        assertEquals("utf-8", VinorCachePolicy.interceptCharset("application/javascript"))
        assertEquals("utf-8", VinorCachePolicy.interceptCharset("image/svg+xml"))
        assertTrue(VinorCachePolicy.isBinaryMime("font/woff2; charset=utf-8"))
        assertFalse(VinorCachePolicy.isBinaryMime("text/html"))
    }

    @Test
    fun interceptCharset_binaryHasNoUtf8() {
        assertEquals("", VinorCachePolicy.interceptCharset("font/woff2", "utf-8"))
        assertEquals("", VinorCachePolicy.interceptCharset("image/png"))
        assertEquals("", VinorCachePolicy.interceptCharset("application/octet-stream", "utf-8"))
        assertEquals("utf-8", VinorCachePolicy.interceptCharset("text/css", "utf-8"))
        assertEquals("utf-8", VinorCachePolicy.interceptCharset("application/javascript"))
        assertEquals("utf-8", VinorCachePolicy.interceptCharset("image/svg+xml"))
        assertTrue(VinorCachePolicy.isBinaryMime("font/woff2; charset=utf-8"))
        assertFalse(VinorCachePolicy.isBinaryMime("text/html"))
    }

    @Test
    fun queueablePost_notesYes_verifyNo() {
        assertTrue(VinorCachePolicy.isQueueablePost("https://vinor.ir/express/partner/api/notes"))
        assertTrue(VinorCachePolicy.isQueueablePost("https://vinor.ir/express/partner/api/notifications/abc/read"))
        assertFalse(VinorCachePolicy.isQueueablePost("https://vinor.ir/express/partner/api/verify"))
        assertFalse(VinorCachePolicy.isQueueablePost("https://vinor.ir/express/partner/api/profile/update"))
    }

    @Test
    fun atomicWrite_doesNotLeavePartialFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "vinor-cache-test")
        dir.mkdirs()
        val target = File(dir, "page.html")
        assertTrue(VinorCacheIo.writeAtomicText(target, "<html><body>ok</body></html>"))
        assertEquals("<html><body>ok</body></html>", VinorCacheIo.readText(target))
        target.delete()
        File(dir, "page.html.tmp").delete()
        dir.delete()
    }

    @Test
    fun lruTrim_respectsCount() {
        val dir = File(System.getProperty("java.io.tmpdir"), "vinor-lru-test")
        dir.mkdirs()
        val files = (1..5).map { i ->
            File(dir, "f$i.bin").also { it.writeText("x".repeat(10)); it.setLastModified(i * 1000L) }
        }
        val removed = VinorCacheIo.lruTrim(files, maxCount = 2, maxTotalBytes = 10_000)
        assertEquals(3, removed.size)
        files.forEach { it.delete() }
        dir.delete()
    }
}
