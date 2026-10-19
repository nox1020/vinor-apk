package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandDetailRoleTest {

    @Test
    fun missingListingUsesServerError() {
        assertEquals(
            "این آگهی دیگر در دسترس نیست.",
            LandDetailFragment.missingMessage(JSONObject().put("ok", false).put("http_status", 404).put("error", "این آگهی دیگر در دسترس نیست.")),
        )
        assertEquals(
            "آگهی پیدا نشد.",
            LandDetailFragment.missingMessage(JSONObject().put("ok", false).put("http_status", 404).put("error", "آگهی پیدا نشد.")),
        )
        assertEquals(
            "آگهی پیدا نشد.",
            LandDetailFragment.missingMessage(JSONObject().put("ok", false).put("http_status", 404)),
        )
    }

    @Test
    fun guest_seesPublicListingAndLoginChat() {
        assertEquals(LandDetailRole.Audience.GUEST, LandDetailRole.audience(false, false))
        assertEquals(LandDetailRole.Audience.GUEST, LandDetailRole.audience(false, true))
        val sticky = LandDetailRole.sticky(LandDetailRole.Audience.GUEST, false, false, false)
        assertEquals(LandDetailRole.StickyKind.LOGIN, sticky.kind)
        assertEquals("ورود برای گفتگو", sticky.label)
        assertFalse(LandDetailRole.showCommission(false, 2_000_000))
        assertFalse(LandDetailRole.showPublish(false, true))
        assertFalse(LandDetailRole.showMeta(false, "تهران، تهرانپارس", "تهران · 2"))
        assertTrue(LandDetailRole.showMeta(false, "", "تهران · تهرانپارس"))
        assertEquals("", LandDetailRole.badge(false, false, false, "", true))
    }

    @Test
    fun partner_chatsAndSeesCommission_divarOpensDivar() {
        assertEquals(LandDetailRole.Audience.PARTNER, LandDetailRole.audience(true, false))
        val chat = LandDetailRole.sticky(LandDetailRole.Audience.PARTNER, false, false, false)
        assertEquals(LandDetailRole.StickyKind.CHAT, chat.kind)
        assertEquals("گفتگو", chat.label)
        val divar = LandDetailRole.sticky(LandDetailRole.Audience.PARTNER, true, false, false)
        assertEquals(LandDetailRole.StickyKind.DIVAR, divar.kind)
        assertEquals("دیدن در دیوار", divar.label)
        assertTrue(LandDetailRole.showCommission(true, 1))
        assertFalse(LandDetailRole.showCommission(true, 0))
        assertFalse(LandDetailRole.showPublish(true, false))
        assertEquals("دیوار", LandDetailRole.badge(true, false, true, "", false))
    }

    @Test
    fun owner_managesAndKeepsPublish() {
        assertEquals(LandDetailRole.Audience.OWNER, LandDetailRole.audience(true, true))
        val sticky = LandDetailRole.sticky(LandDetailRole.Audience.OWNER, true, true, true)
        assertEquals(LandDetailRole.StickyKind.MANAGE, sticky.kind)
        assertEquals("مدیریت ملک", sticky.label)
        assertEquals("منتشرشده", LandDetailRole.stickyHint(LandDetailRole.Audience.OWNER, true))
        assertEquals("خصوصی", LandDetailRole.stickyHint(LandDetailRole.Audience.OWNER, false))
        assertEquals("", LandDetailRole.stickyHint(LandDetailRole.Audience.PARTNER, true))
        assertEquals("منتشرشده", LandDetailRole.badge(true, true, false, "", true))
        assertEquals("ویژه", LandDetailRole.badge(true, true, false, "ویژه", true))
        assertTrue(LandDetailRole.showPublish(true, true))
        assertFalse(LandDetailRole.showPublish(true, false))
        assertEquals("تهران، تهرانپارس", LandDetailRole.place("تهران", "تهرانپارس"))
        assertEquals("تهران", LandDetailRole.place("تهران", "تهران"))
    }

    @Test
    fun highlights_useRealRowsAndSkipDuplicates() {
        val rows = JSONArray()
            .put(JSONObject().put("label", "نوع").put("value", "زمین"))
            .put(JSONObject().put("label", "متراژ").put("value", "250"))
            .put(JSONObject().put("label", "متراژ زمین").put("value", "۲۵۰ متر"))
            .put(JSONObject().put("label", "کاربری").put("value", "مسکونی"))
            .put(JSONObject().put("label", "یادداشت").put("value", "نباید بیاید"))
        assertEquals(listOf("250 متر", "زمین", "مسکونی"), LandDetailRole.highlights(rows))
    }
}
