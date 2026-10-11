package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerApiResponseTest {

    @Test
    fun partnerRedirectToLoginBecomesUnauthorizedJson() {
        val payload = PartnerApi.responsePayload(
            text = "<html><title>تأیید شماره موبایل</title></html>",
            status = 200,
            requestPath = "/express/partner/requests/15/deal-confirm",
            finalPath = "/express/partner/login",
        )
        assertEquals(401, payload.getInt("http_status"))
        assertEquals("unauthorized", payload.getString("error"))
        assertFalse(payload.getBoolean("ok"))
    }

    @Test
    fun realLoginPageAndJsonResponsesKeepTheirMeaning() {
        val login = PartnerApi.responsePayload(
            text = "<html>login</html>",
            status = 200,
            requestPath = "/express/partner/login",
            finalPath = "/express/partner/login",
        )
        assertEquals(200, login.getInt("http_status"))
        assertEquals("پاسخ نامعتبر", login.getString("error"))

        val success = PartnerApi.responsePayload(
            text = """{"ok":true,"message":"ثبت شد"}""",
            status = 200,
            requestPath = "/express/partner/requests/15/complete",
            finalPath = "/express/partner/requests/15/complete",
        )
        assertTrue(success.getBoolean("ok"))
        assertEquals("ثبت شد", success.getString("message"))
    }
}
