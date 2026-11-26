package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «وینور من» همان نوار بالای «مشتریان» (fragment_customers.xml) و «پیگیری» (include_page_header.xml
 * در fragment_native_partner.xml) را دارد: ساختار، ارتفاع، پس‌زمینه، تایپوگرافی، فاصله‌ها، خط زیر و برگشت.
 */
class ProfileHeaderParityTest {
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val d = dir ?: return@repeat
            listOf(File(d, "src/main"), File(d, "app/src/main")).firstOrNull { File(it, "AndroidManifest.xml").isFile }?.let { return it }
            dir = d.parentFile
        }
        throw AssertionError("app/src/main not found")
    }

    private fun layout(name: String) = root().resolve("res/layout/$name.xml").readText()

    private fun tag(xml: String, id: String): String {
        val at = xml.indexOf("android:id=\"@+id/$id\"")
        assertTrue("missing @+id/$id", at > 0)
        return xml.substring(xml.lastIndexOf('<', at), xml.indexOf('>', at))
    }

    private fun attrs(tag: String, vararg names: String): Map<String, String?> =
        names.associateWith { n -> Regex("""$n="([^"]*)"""").find(tag)?.groupValues?.get(1) }

    /** The row that holds back + title: minHeight/padding/gravity. */
    private fun row(xml: String, backId: String): String {
        val back = xml.indexOf("android:id=\"@+id/$backId\"")
        val open = xml.lastIndexOf("<LinearLayout", back)
        return xml.substring(open, xml.indexOf('>', open))
    }

    @Test
    fun profileTopBarMatchesCustomersAndTracking() {
        val profile = layout("fragment_profile")
        val tracking = layout("include_page_header")
        val customers = layout("fragment_customers")
        assertTrue(layout("fragment_native_partner").contains("layout=\"@layout/include_page_header\""))

        val rowAttrs = arrayOf("android:minHeight", "android:paddingHorizontal", "android:paddingVertical", "android:gravity", "android:orientation")
        val pRow = attrs(row(profile, "profileBack"), *rowAttrs)
        assertEquals(attrs(row(tracking, "pageBack"), *rowAttrs), pRow)
        assertEquals(attrs(row(customers, "customersBack"), *rowAttrs), pRow)
        assertEquals("56dp", pRow["android:minHeight"])

        val backAttrs = arrayOf("android:layout_width", "android:layout_height", "android:padding", "android:src", "app:tint", "android:contentDescription", "android:background")
        val pBack = attrs(tag(profile, "profileBack"), *backAttrs)
        assertEquals(attrs(tag(tracking, "pageBack"), *backAttrs), pBack)
        assertEquals(attrs(tag(customers, "customersBack"), *backAttrs), pBack)

        val titleAttrs = arrayOf("android:layout_width", "android:layout_weight", "android:layout_marginStart", "android:textSize", "android:textColor", "android:maxLines", "android:ellipsize", "android:fontFamily", "android:textStyle")
        val pTitle = attrs(tag(profile, "profileTitle"), *titleAttrs)
        assertEquals(attrs(tag(tracking, "pageTitle"), *titleAttrs), pTitle)
        assertEquals("17sp", pTitle["android:textSize"])
        assertTrue(tag(profile, "profileTitle").contains("android:text=\"وینور من\""))
        // end slot is the same 48dp box as Tracking's pageHeaderEnd
        assertEquals(attrs(tag(tracking, "pageHeaderEnd"), "android:layout_width", "android:layout_height"),
            attrs(tag(profile, "profileHeaderEnd"), "android:layout_width", "android:layout_height"))

        // chrome background + 1dp vinor_border hairline, like both references
        assertTrue(tag(profile, "profileHeader").contains("android:background=\"@color/vinor_chrome\""))
        val header = profile.substringAfter("android:id=\"@+id/profileHeader\"").substringBefore("<ScrollView")
        val hairline = "android:layout_height=\"1dp\"\n            android:background=\"@color/vinor_border\""
        assertTrue(header.contains(hairline))
        assertTrue(customers.contains("android:layout_height=\"1dp\"\n            android:background=\"@color/vinor_border\""))
        assertTrue(tracking.contains("android:layout_height=\"1dp\"\n        android:background=\"@color/vinor_border\""))
    }

    @Test
    fun topBarStaysPinnedAndOnlyContentScrolls() {
        val profile = layout("fragment_profile")
        assertTrue(profile.trimStart().substringAfter("-->").trimStart().startsWith("<LinearLayout"))
        assertTrue(profile.indexOf("@+id/profileHeader\"") < profile.indexOf("<ScrollView"))
        val scroll = tag(profile, "profileScroll")
        assertTrue(scroll.contains("android:scrollbars=\"none\""))
        assertTrue(scroll.contains("android:layout_height=\"0dp\"") && scroll.contains("android:layout_weight=\"1\""))
        // existing controls kept
        for (id in listOf("profileNotifications", "profileNotificationsBadge", "profileSkeleton", "profileSummaryWallet", "profileQuickFollow")) {
            assertTrue(id, profile.contains("@+id/$id\""))
        }
    }

    @Test
    fun backIsWiredToHome() {
        val f = root().resolve("java/ir/vinor/app/ProfileFragment.kt").readText()
        assertTrue(f.contains("binding.profileBack.setOnClickListener { (activity as? MainActivity)?.openPartnerHome() }"))
        assertTrue(f.contains("binding.profileNotifications.setOnClickListener"))
    }
}
