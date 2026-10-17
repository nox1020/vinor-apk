package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreListingTest {

    @Test
    fun onlySideloadInstallsPackages() {
        assertTrue(StoreListing.allowsPackageInstall(StoreListing.SIDELOAD))
        assertFalse(StoreListing.allowsPackageInstall(StoreListing.PLAY))
        assertFalse(StoreListing.allowsPackageInstall(StoreListing.BAZAAR))
    }

    @Test
    fun listingsPointAtTheMatchingStore() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=ir.vinor.app",
            StoreListing.httpsListing(StoreListing.PLAY),
        )
        assertEquals(
            "https://cafebazaar.ir/app/ir.vinor.app",
            StoreListing.httpsListing(StoreListing.BAZAAR),
        )
        assertEquals("https://vinor.ir/d", StoreListing.httpsListing(StoreListing.SIDELOAD))
        assertEquals(
            "bazaar://details?id=ir.vinor.app",
            StoreListing.storeUri(StoreListing.BAZAAR),
        )
        assertEquals(
            StoreListing.httpsListing(StoreListing.PLAY),
            StoreListing.storeUri(StoreListing.PLAY),
        )
    }

    @Test
    fun storeVersionCodeFollowsTheName() {
        assertEquals(10004, StoreVersion.versionCode("1.0.4"))
        assertEquals(11472, StoreVersion.versionCode("1.0.1472"))
        assertEquals(11492, StoreVersion.versionCode("1.0.1492"))
        assertEquals(11492, StoreVersion.versionCode("1.0.1492\n"))
    }
}
