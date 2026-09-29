package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsultantOnboardingTest {

    @Test
    fun livePathsStayOnConsultantApiWithoutDryRun() {
        assertEquals("consultant", ConsultantOnboarding.ROLE)
        assertEquals("بازاریاب وینور", ConsultantOnboarding.UI_LABEL)
        ConsultantOnboarding.LIVE_PATHS.forEach { path ->
            assertTrue(ConsultantOnboarding.isLiveConsultantPath(path))
            assertFalse(path.contains("simulate"))
        }
        assertFalse(ConsultantOnboarding.isLiveConsultantPath("/api/consultant/apply?simulate=approved"))
        assertFalse(ConsultantOnboarding.isLiveConsultantPath("/api/consultant/status?dry_run=1"))
        assertFalse(ConsultantOnboarding.isLiveConsultantPath("/express/partner/api/apply/submit"))
        listOf(
            ConsultantOnboarding.PATH_APPLY,
            ConsultantOnboarding.PATH_PROFILE,
            ConsultantOnboarding.PATH_DOCUMENTS,
            ConsultantOnboarding.PATH_AVAILABILITY,
        ).forEach { path ->
            assertFalse(ConsultantOnboarding.requiresCsrf(path))
        }
    }

    @Test
    fun cityIdsComeOnlyFromGetCities() {
        assertTrue(ConsultantOnboarding.parseCities(null).isEmpty())
        assertTrue(ConsultantOnboarding.parseCities(JSONObject().put("ok", true)).isEmpty())

        val remote = JSONObject().put(
            "cities",
            JSONArray()
                .put(JSONObject().put("id", "tehran").put("label", "تهران"))
                .put(JSONObject().put("id", "rey").put("label", "شهرری")),
        )
        val parsed = ConsultantOnboarding.parseCities(remote)
        assertEquals(listOf("tehran", "rey"), parsed.map { it.id })
        assertEquals(
            "city",
            (ConsultantOnboarding.profileBody("مریم", "pardis", listOf("buy"), parsed) as ConsultantOnboarding.Draft.Invalid).error.field,
        )
    }

    @Test
    fun profileAcceptsBuySellOnlyInsideLoadedCities() {
        val cities = ConsultantOnboarding.parseCities(
            JSONObject().put(
                "cities",
                JSONArray()
                    .put(JSONObject().put("id", "pardis").put("label", "پردیس"))
                    .put(JSONObject().put("id", "tehran").put("label", "تهران")),
            ),
        )
        val ok = ConsultantOnboarding.profileBody("مریم رضایی", "pardis", listOf("sell", "buy", "buy"), cities)
        assertTrue(ok is ConsultantOnboarding.Draft.Ok)
        val json = (ok as ConsultantOnboarding.Draft.Ok).json
        assertEquals("مریم رضایی", json.getString("name"))
        assertEquals("pardis", json.getString("city"))
        assertEquals(listOf("buy", "sell"), (0 until json.getJSONArray("specialties").length()).map {
            json.getJSONArray("specialties").getString(it)
        })

        assertEquals("name", (ConsultantOnboarding.profileBody("ا", "tehran", listOf("buy"), cities) as ConsultantOnboarding.Draft.Invalid).error.field)
        assertEquals("city", (ConsultantOnboarding.profileBody("مریم", "karaj", listOf("buy"), cities) as ConsultantOnboarding.Draft.Invalid).error.field)
        assertEquals(
            "specialties",
            (ConsultantOnboarding.profileBody("مریم", "tehran", listOf("rent"), cities) as ConsultantOnboarding.Draft.Invalid).error.field,
        )
        assertEquals(
            "specialties",
            (ConsultantOnboarding.profileBody("مریم", "tehran", listOf("lease"), cities) as ConsultantOnboarding.Draft.Invalid).error.field,
        )
        assertEquals(
            "specialties",
            (ConsultantOnboarding.profileBody("مریم", "tehran", emptyList(), cities) as ConsultantOnboarding.Draft.Invalid).error.field,
        )
    }

    @Test
    fun documentsRequireNationalIdAndAllowOptionalLicense() {
        assertEquals("کارت ملی الزامی است.", ConsultantOnboarding.validateDocument("national_id", "", "", -1))
        assertNull(ConsultantOnboarding.validateDocument("license", "", "", -1))
        assertNull(ConsultantOnboarding.validateDocument("national_id", "card.jpg", "image/jpeg", 1200))
        assertNull(ConsultantOnboarding.validateDocument("license", "lic.pdf", "application/pdf", 20))
        assertNull(ConsultantOnboarding.validateDocument("national_id", "scan.PNG", "application/octet-stream", -1))
        assertEquals(
            "فقط jpg، png یا pdf مجاز است.",
            ConsultantOnboarding.validateDocument("national_id", "card.webp", "image/webp", 100),
        )
        assertEquals(
            "حجم هر فایل حداکثر ۵ مگابایت است.",
            ConsultantOnboarding.validateDocument("national_id", "card.jpg", "image/jpeg", ConsultantOnboarding.MAX_DOC_BYTES + 1),
        )
        assertEquals("فایل خالی است.", ConsultantOnboarding.validateDocument("license", "a.png", "image/png", 0))
    }

    @Test
    fun screenFollowsNextStepWhileStatusStaysPending() {
        assertEquals(
            ConsultantOnboarding.Screen.PROFILE,
            ConsultantOnboarding.resolveScreen("profile", "pending", false),
        )
        assertEquals(
            ConsultantOnboarding.Screen.DOCUMENTS,
            ConsultantOnboarding.resolveScreen("documents", "pending", false),
        )
        assertEquals(
            ConsultantOnboarding.Screen.STATUS,
            ConsultantOnboarding.resolveScreen("status", "pending", false),
        )
        assertEquals(
            ConsultantOnboarding.Screen.STATUS,
            ConsultantOnboarding.resolveScreen("status", "rejected", false),
        )
        assertEquals(
            ConsultantOnboarding.Screen.PROFILE,
            ConsultantOnboarding.resolveScreen("", "pending", false),
        )
        assertEquals(
            ConsultantOnboarding.Screen.DASHBOARD,
            ConsultantOnboarding.resolveScreen("dashboard", "pending", false),
        )
        assertEquals(
            ConsultantOnboarding.Screen.TIPS,
            ConsultantOnboarding.resolveScreen("dashboard", "approved", false),
        )
        assertEquals(
            ConsultantOnboarding.Screen.DASHBOARD,
            ConsultantOnboarding.resolveScreen("dashboard", "approved", true),
        )
        assertTrue(ConsultantOnboarding.slidesSeen(apiFlag = true, localFlag = false))
        assertFalse(ConsultantOnboarding.slidesSeen(apiFlag = false, localFlag = false))
        assertEquals(3, ConsultantOnboarding.tips.size)
        assertTrue(ConsultantOnboarding.canSetAvailability("approved"))
        assertFalse(ConsultantOnboarding.canSetAvailability("pending"))
        assertFalse(ConsultantOnboarding.canSetAvailability("rejected"))
    }

    @Test
    fun errorsPreferPersianMessageAndRejectReason() {
        val fields = JSONObject()
            .put("ok", false)
            .put("error", "invalid")
            .put("fields", JSONObject().put("specialties", "تخصص نامعتبر است"))
        assertEquals("تخصص نامعتبر است", ConsultantOnboarding.userMessage(fields))
        val message = JSONObject().put("ok", false).put("message", "ابتدا تأیید شوید.").put("http_status", 409)
        assertEquals("ابتدا تأیید شوید.", ConsultantOnboarding.userMessage(message))
        assertEquals("برای ادامه وارد شوید.", ConsultantOnboarding.userMessage(JSONObject().put("http_status", 401)))
        assertTrue(ConsultantOnboarding.isUnauthorized(JSONObject().put("error", "unauthorized")))
        val rejected = JSONObject().put("status", "rejected").put("reject_reason", "تصویر کارت ناخوانا است")
        assertEquals("تصویر کارت ناخوانا است", ConsultantOnboarding.rejectReason(rejected))
        assertEquals("", ConsultantOnboarding.rejectReason(JSONObject().put("status", "pending").put("reject_reason", "نباید دیده شود")))
        assertFalse(VinorCachePolicy.isQueueablePost("https://vinor.ir/api/consultant/documents"))
        assertFalse(VinorCachePolicy.isSafeGetApi("https://vinor.ir/api/consultant/status"))
    }
}
