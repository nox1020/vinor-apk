package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkRouterTest {

    @Test
    fun propertyPath_mapsToLandDetail_requiresAuth() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/property/ABC123")
        assertEquals(DeepLinkRouter.DestinationType.PROPERTY_DETAIL, r.type)
        assertEquals("ABC123", r.propertyId)
        assertTrue(r.requiresAuth)
        assertTrue(r.targetUrl.contains("/express/partner/lands/ABC123"))
    }

    @Test
    fun wwwHost_accepted() {
        val r = DeepLinkRouter.resolve("https://www.vinor.ir/express/XYZ")
        assertEquals(DeepLinkRouter.DestinationType.EXPRESS_PUBLIC, r.type)
        assertFalse(r.requiresAuth)
    }

    @Test
    fun unknown_fallsBackHome() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/some/unknown/path")
        assertEquals(DeepLinkRouter.DestinationType.UNKNOWN_FALLBACK, r.type)
    }

    @Test
    fun profile_requiresAuth() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/express/partner/profile")
        assertEquals(DeepLinkRouter.DestinationType.PROFILE, r.type)
        assertTrue(r.requiresAuth)
    }

    @Test
    fun profileShortPath_mapsToPartnerProfile() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/profile")
        assertEquals(DeepLinkRouter.DestinationType.PROFILE, r.type)
        assertTrue(r.requiresAuth)
        assertTrue(r.targetUrl.contains("/express/partner/profile"))
    }

    @Test
    fun apkShortLink_isApkDownload() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/d")
        assertEquals(DeepLinkRouter.DestinationType.APK_DOWNLOAD, r.type)
        assertFalse(r.requiresAuth)
    }

    @Test
    fun apkUploadPath_isApkDownload() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/uploads/apk/vinor_1.0.70.apk")
        assertEquals(DeepLinkRouter.DestinationType.APK_DOWNLOAD, r.type)
    }

    @Test
    fun loginUrl_encodesNext() {
        val login = DeepLinkRouter.loginUrlFor("https://vinor.ir/express/partner/lands/A1")
        assertTrue(login.startsWith("https://vinor.ir/express/partner/login?next="))
        assertTrue(login.contains("lands"))
    }
}
