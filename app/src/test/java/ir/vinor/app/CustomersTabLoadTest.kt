package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** تب «مشتریان» باید مثل «خانه» و «پیگیری» لود شود: یک درخواست JSON، پارس خارج از main، بدون پرش. */
class CustomersTabLoadTest {
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val d = dir ?: return@repeat
            listOf(File(d, "src/main"), File(d, "app/src/main")).firstOrNull { File(it, "AndroidManifest.xml").isFile }?.let { return it }
            dir = d.parentFile
        }
        throw AssertionError("app/src/main not found")
    }

    private fun src(name: String) = root().resolve("java/ir/vinor/app/$name").readText()

    private val base = "https://vinor.ir/express/partner/customers"

    @Test
    fun jsonOnlyForMainPageAndSupportedServer() {
        assertTrue(CustomersLoad.useJson(base, base, jsonUnsupported = false))
        assertTrue(CustomersLoad.useJson("$base/", base, jsonUnsupported = false))
        assertFalse(CustomersLoad.useJson("$base?q=ali", base, jsonUnsupported = false))
        assertFalse(CustomersLoad.useJson(base, base, jsonUnsupported = true))
    }

    private fun payload(): JSONObject = JSONObject()
        .put("ok", true)
        .put("csrf_token", "tok")
        .put("submit_label", "ذخیره مشتری")
        .put("action_path", "/express/partner/customers")
        .put("empty_text", "هنوز مشتری‌ای ثبت نشده")
        .put(
            "fields",
            JSONArray()
                .put(JSONObject().put("name", "first_name").put("label", "نام").put("required", true))
                .put(JSONObject().put("name", "last_name").put("label", "نام خانوادگی").put("required", true))
                .put(JSONObject().put("name", "customer_phone").put("label", "شماره مشتری").put("phone", true)),
        )
        .put(
            "customers",
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "c1")
                        .put("display_name", "علی رضایی")
                        .put("customer_phone", "+989122222222")
                        .put("source", "visit")
                        .put("visit_count", 2)
                        .put("recent_calls", JSONArray().put(
                            JSONObject().put("called_at", "2026-11-20T10:00:00").put("outcome", "answered").put("notes", "هماهنگ"),
                        ))
                        .put("pending_followups", JSONArray().put(JSONObject().put("due_at", "2026-12-01"))),
                )
                .put(JSONObject().put("id", "c2").put("first_name", "سارا").put("last_name", "ک").put("customer_phone", "09123333333")),
        )

    @Test
    fun parsesServerJsonIntoSamePage() {
        val page = PartnerCustomers.parseData(payload())
        assertNotNull(page)
        page!!
        assertEquals(2, page.rows.size)
        val ali = page.rows[0]
        assertEquals("c1", ali.id)
        assertEquals("علی رضایی", ali.title)
        assertEquals("09122222222", ali.phone)
        assertEquals("پیگیری 2026-12-01", ali.status)
        assertTrue(ali.facts.any { it.label == "منبع" && it.value == "بازدید" })
        assertTrue(ali.facts.any { it.label == "تماس" && it.value.contains("پاسخ داد") })
        assertEquals("سارا ک", page.rows[1].title)
        assertEquals(listOf("first_name", "last_name", "customer_phone"), page.fields.map { it.name })
        assertTrue(page.fields[2].phone)
        assertEquals("/express/partner/customers", page.actionPath)
        assertFalse(page.canDelete)
        // data class equality lets the fragment skip an identical rebind (no flash/jump)
        assertEquals(page, PartnerCustomers.parseData(payload()))
    }

    @Test
    fun malformedOrOldServerResponses() {
        assertNull(PartnerCustomers.parseData(JSONObject().put("ok", true)))
        assertTrue(PartnerCustomers.dataUnsupported(JSONObject().put("http_status", 404)))
        assertTrue(PartnerCustomers.dataUnsupported(JSONObject().put("non_json", true).put("http_status", 200)))
        assertFalse(PartnerCustomers.dataUnsupported(JSONObject().put("offline", true)))
        assertFalse(PartnerCustomers.dataUnsupported(payload()))
    }

    @Test
    fun customerDataNeverHitsDiskCache() {
        assertFalse(VinorCachePolicy.isSafeGetApi("https://vinor.ir" + PartnerCustomers.DATA_PATH))
        assertTrue(VinorCachePolicy.isSensitivePath(PartnerCustomers.DATA_PATH))
    }

    @Test
    fun fragmentLoadsLikeOtherTabs() {
        val f = src("CustomersFragment.kt")
        // one JSON request on IO, parsing inside fetch() (IO), not on the main thread
        assertTrue(f.contains("withContext(Dispatchers.IO) { fetch(url, ctx) }"))
        assertTrue(f.contains("PartnerApi.get(PartnerCustomers.DATA_PATH, ctx)"))
        val fetch = f.substringAfter("private fun fetch(").substringBefore("\n    private fun showMessage")
        assertTrue(fetch.contains("PartnerCustomers.parse(doc.body)"))
        val loadBody = f.substringAfter("private fun load(pull: Boolean = false) {").substringBefore("private sealed class Fetch")
        assertFalse("parse on main thread", loadBody.contains("PartnerCustomers.parse("))
        // profile/data only as a fallback when the HTML has no CSRF token (was: every load)
        val profileAt = f.indexOf("/express/partner/profile/data")
        assertTrue(profileAt > f.indexOf("if (token.isBlank())"))
        assertFalse(f.contains("coroutineScope {"))
        // no identical rebind; pull-to-refresh like Tracking
        assertTrue(f.contains("if (!same) bindPage()"))
        assertTrue(f.contains("customersRefresh.setOnRefreshListener { load(pull = true) }"))
        assertTrue(f.contains("remembered[url]"))
    }

    @Test
    fun layoutHasPullRefreshAndTrackingSpinner() {
        val xml = root().resolve("res/layout/fragment_customers.xml").readText()
        assertTrue(xml.contains("<ir.vinor.app.VinorSwipeRefreshLayout"))
        assertTrue(xml.contains("android:id=\"@+id/customersRefresh\""))
        val pb = xml.substringAfter("android:id=\"@+id/customersProgress\"").substringBefore("/>")
        assertTrue(pb.contains("android:layout_width=\"24dp\""))
        assertTrue(pb.contains("android:layout_height=\"24dp\""))
    }
}
