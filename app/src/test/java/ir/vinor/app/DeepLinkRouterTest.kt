package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkRouterTest {

    @Test
    fun propertyPath_mapsToLandDetail_guestAllowed() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/property/ABC123")
        assertEquals(DeepLinkRouter.DestinationType.PROPERTY_DETAIL, r.type)
        assertEquals("ABC123", r.propertyId)
        assertFalse(r.requiresAuth)
        assertTrue(r.targetUrl.contains("/express/partner/lands/ABC123"))
    }

    @Test
    fun wwwHost_accepted() {
        val r = DeepLinkRouter.resolve("https://www.vinor.ir/express/XYZ")
        assertEquals(DeepLinkRouter.DestinationType.EXPRESS_PUBLIC, r.type)
        assertFalse(r.requiresAuth)
    }

    @Test
    fun blank_fallsBackHome() {
        val r = DeepLinkRouter.resolve("   ")
        assertEquals(DeepLinkRouter.DestinationType.UNKNOWN_FALLBACK, r.type)
        assertFalse(r.requiresAuth)
    }

    @Test
    fun foreignHost_fallsBackHome() {
        val r = DeepLinkRouter.resolve("https://evil.example/express/partner")
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
    fun downloadAppLegacyPath_isApkDownload() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/express/partner/download-app")
        assertEquals(DeepLinkRouter.DestinationType.APK_DOWNLOAD, r.type)
        assertFalse(r.requiresAuth)
        assertTrue(r.targetUrl.contains("/d"))
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

    @Test
    fun requests_andRequestDetails_areNativePartnerFlow_andRequireAuth() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/express/partner/requests")
        assertEquals(DeepLinkRouter.DestinationType.PARTNER_REQUESTS, r.type)
        assertTrue(r.requiresAuth)
        assertTrue(r.targetUrl.contains("/requests"))
        val detail = DeepLinkRouter.resolve("https://vinor.ir/express/partner/requests/15?tab=visits")
        assertEquals(DeepLinkRouter.DestinationType.PARTNER_REQUESTS, detail.type)
        assertTrue(detail.requiresAuth)
        assertEquals("requests", detail.logLabel)
    }

    @Test
    fun customers_isPartnerWeb_requiresAuth() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/express/partner/customers")
        assertEquals(DeepLinkRouter.DestinationType.PARTNER_CUSTOMERS, r.type)
        assertTrue(r.requiresAuth)
        assertEquals("customers", r.logLabel)
        assertTrue(r.targetUrl.contains("/express/partner/customers"))
    }

    @Test
    fun customersQueryAndNested_stayInApp() {
        val query = DeepLinkRouter.resolve("https://vinor.ir/express/partner/customers?tab=active")
        assertEquals(DeepLinkRouter.DestinationType.PARTNER_CUSTOMERS, query.type)
        assertTrue(query.requiresAuth)
        assertTrue(query.targetUrl.contains("/express/partner/customers?tab=active"))
        val nested = DeepLinkRouter.resolve("https://www.vinor.ir/express/partner/customers/12/")
        assertEquals(DeepLinkRouter.DestinationType.PARTNER_CUSTOMERS, nested.type)
        assertTrue(nested.requiresAuth)
        assertTrue(nested.targetUrl.contains("/express/partner/customers/12"))
    }

    @Test
    fun training_isPublicPartnerWeb() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/express/partner/training")
        assertEquals(DeepLinkRouter.DestinationType.PARTNER_WEB, r.type)
        assertFalse(r.requiresAuth)
    }

    @Test
    fun homeLandQuery_opensProperty() {
        val r = DeepLinkRouter.resolve("https://vinor.ir/express/partner/home?land=JB12")
        assertEquals(DeepLinkRouter.DestinationType.PROPERTY_DETAIL, r.type)
        assertEquals("JB12", r.propertyId)
        assertFalse(r.requiresAuth)
    }
}
