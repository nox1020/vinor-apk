package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VinorAvatarCacheTest {
    @Test
    fun resolve_keepsAbsoluteAndPrefixesRelative() {
        assertEquals(
            "https://vinor.ir/express/partner/api/profile/avatar?v=4",
            VinorAvatarCache.resolve("/express/partner/api/profile/avatar?v=4"),
        )
        assertEquals(
            "https://vinor.ir/a.jpg?v=1",
            VinorAvatarCache.resolve("https://vinor.ir/a.jpg?v=1"),
        )
        assertEquals("", VinorAvatarCache.resolve("  "))
    }

    @Test
    fun samePhoto_ignoresQueryOrderAndTracksVersion() {
        val a = "/express/partner/api/profile/avatar?v=9"
        val b = "https://vinor.ir/express/partner/api/profile/avatar?x=1&v=9"
        assertTrue(VinorAvatarCache.samePhoto(a, b))
        assertEquals(9, VinorAvatarCache.version(b))
        assertFalse(
            VinorAvatarCache.samePhoto(a, "/express/partner/api/profile/avatar?v=10"),
        )
        assertTrue(
            VinorAvatarCache.samePhoto(
                "https://vinor.ir/a.jpg",
                "https://vinor.ir/a.jpg",
            ),
        )
    }

    @Test
    fun disk_reusesBytesUntilVersionChanges() {
        val dir = File.createTempFile("avatar", "dir")
        dir.delete()
        dir.mkdirs()
        try {
            val url = "https://vinor.ir/express/partner/api/profile/avatar?v=2"
            val bytes = byteArrayOf(1, 2, 3, 4)
            assertTrue(VinorAvatarCache.writeStored(dir, url, bytes))
            assertTrue(VinorAvatarCache.readStored(dir, "/express/partner/api/profile/avatar?v=2")!!.contentEquals(bytes))
            assertNull(VinorAvatarCache.readStored(dir, "/express/partner/api/profile/avatar?v=3"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
