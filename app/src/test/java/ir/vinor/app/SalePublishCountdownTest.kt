package ir.vinor.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalePublishCountdownTest {
    @Test
    fun underThirtyDaysUsesPersianDaysHoursAndMinutes() {
        val now = 1_700_000_000_000L
        val left = 29L * 86_400_000L + 5L * 3_600_000L + 12L * 60_000L
        val payload = JSONObject().put(
            SalePublishCountdown.EXPIRES_AT,
            iso(now + left),
        )
        assertEquals(
            "۲۹ روز ۵ ساعت ۱۲ دقیقه تا توقف خودکار",
            SalePublishCountdown.label(published = true, payload, fetchedAtMillis = now, nowMillis = now),
        )
    }

    @Test
    fun underOneHourShowsOnlyMinutes() {
        val now = 1_700_000_000_000L
        val payload = JSONObject().put(SalePublishCountdown.REMAINING_SECONDS, 12 * 60)
        assertEquals(
            "۱۲ دقیقه تا توقف خودکار",
            SalePublishCountdown.label(published = true, payload, fetchedAtMillis = now, nowMillis = now),
        )
        val later = now + 2L * 60_000L
        val hour = JSONObject().put(SalePublishCountdown.REMAINING_SECONDS, 3600)
        assertEquals(
            "۵۸ دقیقه تا توقف خودکار",
            SalePublishCountdown.label(published = true, hour, fetchedAtMillis = now, nowMillis = later),
        )
    }

    @Test
    fun expiredOrStoppedUsesStoppedLabel() {
        val now = 1_700_000_000_000L
        val expired = JSONObject().put(SalePublishCountdown.REMAINING_SECONDS, 0)
        assertEquals(
            SalePublishCountdown.STOPPED,
            SalePublishCountdown.label(published = true, expired, fetchedAtMillis = now, nowMillis = now),
        )
        val past = JSONObject().put(SalePublishCountdown.EXPIRES_AT, iso(now - 1_000L))
        assertEquals(
            SalePublishCountdown.STOPPED,
            SalePublishCountdown.label(published = true, past, fetchedAtMillis = now, nowMillis = now),
        )
        val future = JSONObject().put(SalePublishCountdown.EXPIRES_AT, iso(now + 86_400_000L))
        assertEquals(
            SalePublishCountdown.STOPPED,
            SalePublishCountdown.label(published = false, future, fetchedAtMillis = now, nowMillis = now),
        )
        assertFalse(SalePublishCountdown.isLiveCountdown(SalePublishCountdown.STOPPED))
    }

    @Test
    fun absentFieldsHideTheCountdown() {
        val now = 1_700_000_000_000L
        assertNull(SalePublishCountdown.label(published = true, JSONObject(), now, now))
        assertNull(SalePublishCountdown.label(published = false, null, now, now))
        val blanks = JSONObject()
            .put(SalePublishCountdown.EXPIRES_AT, JSONObject.NULL)
            .put(SalePublishCountdown.REMAINING_SECONDS, JSONObject.NULL)
        assertNull(SalePublishCountdown.label(published = true, blanks, now, now))
        assertFalse(SalePublishCountdown.hasSignal(blanks))
    }

    @Test
    fun expiresAtBeatsRemainingSecondsAndAcceptsUtcOffsets() {
        val now = SalePublishCountdown.parseUtcMillis("2026-10-19T06:00:00Z")!!
        val same = SalePublishCountdown.parseUtcMillis("2026-10-19T06:00:00.000+00:00")
        assertEquals(now, same)
        val payload = JSONObject()
            .put(SalePublishCountdown.EXPIRES_AT, "2026-10-19T07:12:00Z")
            .put(SalePublishCountdown.REMAINING_SECONDS, 30)
        assertEquals(
            "۱ ساعت ۱۲ دقیقه تا توقف خودکار",
            SalePublishCountdown.label(published = true, payload, fetchedAtMillis = now - 999_999L, nowMillis = now),
        )
    }

    private fun iso(millis: Long): String {
        val totalSeconds = Math.floorDiv(millis, 1000L)
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = totalSeconds * 1000L
        return String.format(
            java.util.Locale.US,
            "%04d-%02d-%02dT%02d:%02d:%02dZ",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }
}
