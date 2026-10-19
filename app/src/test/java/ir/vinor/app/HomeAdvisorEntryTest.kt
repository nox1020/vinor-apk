package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAdvisorEntryTest {
    @Test
    fun homeDropsTheAdvisorBannerAndMenuKeepsTheSameEntry() {
        val prompts = JSONArray()
            .put(
                JSONObject()
                    .put("label", VinorManMenu.ADVISOR_LABEL)
                    .put("subtitle", VinorManMenu.ADVISOR_SUBTITLE)
                    .put("variant", "hero")
                    .put("href", "/express/partner/apply"),
            )
            .put(
                JSONObject()
                    .put("label", VinorManMenu.ADVISOR_STATUS_LABEL)
                    .put("variant", "hero")
                    .put("href", "/express/partner/thanks"),
            )
            .put(
                JSONObject()
                    .put("label", "سطوح همکاری")
                    .put("variant", "tile")
                    .put("href", "/express/partner/levels"),
            )
        val kept = HomePrompts.withoutAdvisorBanner(prompts)
        assertEquals(1, kept.length())
        assertEquals("سطوح همکاری", kept.getJSONObject(0).optString("label"))
        assertFalse(HomePrompts.isAdvisorBanner(JSONObject().put("label", "سطوح همکاری").put("variant", "hero")))

        val guest = VinorManMenu.rows(VinorManMenu.Profile(loggedIn = false))
        val signup = guest.single { it.id == "consultant" }
        assertEquals(VinorManMenu.ADVISOR_LABEL, signup.label)
        assertEquals(VinorManMenu.ADVISOR_SUBTITLE, signup.subtitle)
        assertEquals(VinorManMenu.Action.CONSULTANT, signup.action)
        assertTrue(guest.none { it.id == "consultant_status" })

        val pending = VinorManMenu.rows(VinorManMenu.Profile(loggedIn = true, pending = true))
        val status = pending.single { it.id == "consultant_status" }
        assertEquals(VinorManMenu.ADVISOR_STATUS_LABEL, status.label)
        assertEquals(VinorManMenu.ADVISOR_STATUS_SUBTITLE, status.subtitle)
        assertEquals(VinorManMenu.Action.CONSULTANT, status.action)
        assertTrue(pending.none { it.id == "consultant" })

        val approved = VinorManMenu.rows(VinorManMenu.Profile(loggedIn = true, approved = true))
        assertTrue(approved.none { it.id == "consultant" || it.id == "consultant_status" })
        assertTrue(approved.any { it.id == "requests" })
    }
}
