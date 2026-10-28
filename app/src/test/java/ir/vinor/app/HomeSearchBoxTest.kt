package ir.vinor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeSearchBoxTest {
    @Test
    fun homeContainsSearchFiltersWithoutSeparateSearchDestination() {
        val home = source("src/main/res/layout/fragment_dashboard.xml")
        val xml = home.readText()
        assertTrue(xml.contains("dashboardSearchContainer"))
        assertTrue(xml.contains("dashboardShortcuts"))
        val dash = source("src/main/java/ir/vinor/app/DashboardFragment.kt").readText()
        assertTrue(dash.contains("installHomeSearch()"))
        val search = source("src/main/res/layout/fragment_search.xml").readText()
        assertTrue(search.contains("searchChipPrice"))
        assertTrue(search.contains("searchChipArea"))
        assertTrue(search.contains("searchChipDistrict"))
        val graph = source("src/main/res/navigation/nav_graph.xml").readText()
        assertFalse(graph.contains("android:id=\"@+id/searchFragment\""))
    }

    @Test
    fun reparentedHeaderKeepsXmlHeightsSoBorderCannotCoverFeed() {
        val search = source("src/main/java/ir/vinor/app/SearchFragment.kt").readText()
        val attach = search.substringAfter("fun attachToHomeHeader(host: LinearLayout)").substringBefore("fun acceptHomeListings")
        assertTrue(attach.contains("control.layoutParams?.height"))
        assertFalse(attach.contains("ViewGroup.LayoutParams.WRAP_CONTENT,\n"))
        val xml = source("src/main/res/layout/fragment_search.xml").readText()
        val border = xml.substringAfter("android:id=\"@+id/searchHeaderBorder\"").substringBefore("/>")
        assertTrue(border.contains("android:layout_height=\"1dp\""))
    }

    @Test
    fun listingRowGrowsSoPlaceLineIsNeverClipped() {
        val row = source("src/main/res/layout/item_ads_all_row.xml").readText()
        val column = row.substringBefore("android:id=\"@+id/adsAllTitle\"")
            .substringAfterLast("<LinearLayout")
        assertTrue(column.contains("android:layout_height=\"wrap_content\""))
        assertTrue(column.contains("android:minHeight=\"104dp\""))
        assertTrue(row.contains("android:id=\"@+id/adsAllLoc\""))
    }

    @Test
    fun homeHasFixedSearchHeaderAndOneScrollRefreshOwner() {
        val home = source("src/main/res/layout/fragment_dashboard.xml").readText()
        val dashboard = source("src/main/java/ir/vinor/app/DashboardFragment.kt").readText()
        val search = source("src/main/java/ir/vinor/app/SearchFragment.kt").readText()
        assertTrue(home.contains("dashboardSearchHeaderHost"))
        assertTrue(home.contains("dashboardAccountBlock"))
        assertTrue(dashboard.contains("setOnChildScrollUpCallback"))
        assertTrue(dashboard.contains("attachToHomeHeader"))
        val searchXml = source("src/main/res/layout/fragment_search.xml").readText()
        assertTrue(searchXml.contains("searchResultsContent"))
        assertFalse(searchXml.contains("android:id=\"@+id/searchScroll\""))
        assertFalse(searchXml.contains("android:id=\"@+id/searchRefresh\""))
        assertTrue(search.contains("attachToHomeHeader(host: LinearLayout)"))
        assertTrue(search.contains("homeScroll?.viewTreeObserver?.addOnScrollChangedListener"))
        assertTrue(search.contains("fun acceptHomeListings(home: JSONObject)"))
        assertTrue(search.contains("SearchAds.fromObjects(home.optJSONArray(\"public_lands\")"))
        assertTrue(dashboard.contains("?.acceptHomeListings(obj)"))
        assertTrue(dashboard.contains("binding.dashboardAccountBlock.isVisible = false"))
    }

    @Test
    fun homeAutomaticallyLoadsPublishedAdsAndSupportsSwipeRefreshWithoutExtraToolbar() {
        val xml = source("src/main/res/layout/fragment_dashboard.xml").readText()
        val dash = source("src/main/java/ir/vinor/app/DashboardFragment.kt").readText()
        val search = source("src/main/java/ir/vinor/app/SearchFragment.kt").readText()
        assertTrue(xml.contains("dashboardSearchContainer"))
        assertTrue(xml.contains("dashboardSearchHeaderHost"))
        assertFalse(xml.contains("dashboardRefreshButton"))
        assertFalse(xml.contains("android:text=\"آگهی‌های ملک\""))
        assertFalse(dash.contains("binding.dashboardRefreshButton"))
        assertTrue(dash.contains("setOnRefreshListener { onPullRefresh() }"))
        assertTrue(dash.contains("search.refreshAds"))
        assertTrue(search.contains("load(forceSkeleton = rememberedAds == null)"))
        assertTrue(search.contains("PartnerApi.getFresh(\"/express/partner/api/public-lands\", ctx)"))
        assertTrue(search.contains("PartnerApi.getFresh(\"/express/partner/dashboard/data\", ctx)"))
        assertTrue(search.contains("SearchAds.fromObjects(publicPayload.optJSONArray(\"public_lands\")"))
        assertTrue(search.contains("SearchAds.fromObjects(dashboardPayload.optJSONArray(\"public_lands\")"))
        assertTrue(search.contains("SearchAds.fromObjects(home.optJSONArray(\"public_lands\")"))
    }

    @Test
    fun vinorManHoldsAccountOverviewAndNotifications() {
        val profile = source("src/main/res/layout/fragment_profile.xml").readText()
        val profileCode = source("src/main/java/ir/vinor/app/ProfileFragment.kt").readText()
        assertTrue(profile.contains("profileNotifications"))
        assertTrue(profile.contains("profileSummaryWallet"))
        assertTrue(profile.contains("profileSummaryCustomers"))
        assertTrue(profile.contains("profileSummaryVisits"))
        assertTrue(profile.contains("profileSummaryDeals"))
        assertTrue(profile.contains("profileQuickTraining"))
        assertTrue(profile.contains("profileQuickNews"))
        assertTrue(profile.contains("profileQuickFollow"))
        assertTrue(profileCode.contains("refreshWorkSummary"))
    }

    private fun source(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val roots = ArrayList<File>()
        var dir: File? = cwd
        repeat(5) {
            val current = dir ?: return@repeat
            roots.add(current)
            dir = current.parentFile
        }
        for (root in roots) {
            val direct = File(root, relative)
            if (direct.isFile) return direct
            val underApp = File(root, "app/$relative")
            if (underApp.isFile) return underApp
        }
        throw AssertionError("missing $relative from $cwd")
    }
}
