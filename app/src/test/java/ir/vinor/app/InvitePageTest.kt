package ir.vinor.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvitePageTest {

    @Test
    fun linkPrefersPayloadAndIgnoresPlaceholder() {
        assertEquals("https://vinor.ir/i/1", InvitePage.linkOf("https://vinor.ir/i/1", ""))
        assertEquals("https://vinor.ir/i/2", InvitePage.linkOf("", "https://vinor.ir/i/2"))
        assertEquals("https://vinor.ir/i/1", InvitePage.linkOf(" https://vinor.ir/i/1 ", InvitePage.EMPTY_LINK))
        assertEquals("", InvitePage.linkOf("", InvitePage.EMPTY_LINK))
        assertEquals("", InvitePage.linkOf("null", ""))
        assertEquals("", InvitePage.linkOf("  ", " null "))
    }

    @Test
    fun shareUsesTextAndLinkWithoutDuplicating() {
        assertNull(InvitePage.shareMessage("", ""))
        assertNull(InvitePage.shareMessage("null", "null"))
        assertEquals("https://vinor.ir/i/1", InvitePage.shareMessage("", "https://vinor.ir/i/1"))
        assertEquals("بیا وینور", InvitePage.shareMessage("بیا وینور", ""))
        assertEquals(
            "بیا وینور\nhttps://vinor.ir/i/1",
            InvitePage.shareMessage("بیا وینور", "https://vinor.ir/i/1"),
        )
        assertEquals(
            "بیا https://vinor.ir/i/1",
            InvitePage.shareMessage("بیا https://vinor.ir/i/1", "https://vinor.ir/i/1"),
        )
    }

    @Test
    fun leadAndEarnedStayOnRealFields() {
        assertEquals(InvitePage.TITLE, "دعوت")
        assertEquals(
            "لینک زیر را بفرست تا دوستانت راحت‌تر به وینور بپیوندند.",
            InvitePage.lead(approved = false, bonus = 50000),
        )
        assertEquals(
            "لینک را برای همکار بفرست. پس از تأیید، پاداش دعوت به موجودی شما اضافه می‌شود.",
            InvitePage.lead(approved = true, bonus = 0),
        )
        assertEquals(
            "هر همکار تأییدشده، ${PartnerFormat.toman(150000)} تومان به موجودی شما اضافه می‌شود.",
            InvitePage.lead(approved = true, bonus = 150000),
        )
        assertNull(InvitePage.earnedText(false, JSONObject().put("total_earned", 10)))
        assertNull(InvitePage.earnedText(true, null))
        assertNull(InvitePage.earnedText(true, JSONObject()))
        assertEquals(
            "${PartnerFormat.toman(2500000)} تومان",
            InvitePage.earnedText(true, JSONObject().put("total_earned", 2500000)),
        )
        assertEquals("۰ تومان", InvitePage.earnedText(true, JSONObject().put("total_earned", 0)))
    }
}