package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingArchiveTest {

    @Test
    fun defaultViewIsActiveAndCountsComeFromTheApi() {
        val payload = JSONObject()
            .put("ok", true)
            .put("has_property", true)
            .put("status", "active")
            .put("counts", JSONObject().put("active", 2).put("archived", 1))
            .put(
                "items",
                JSONArray()
                    .put(JSONObject().put("code", "a").put("archived", false).put("is_archived", false))
                    .put(JSONObject().put("code", "b").put("title", "فعال")),
            )
        val page = ListingArchive.page(payload, ListingArchive.ACTIVE)
        assertEquals(ListingArchive.ACTIVE, page.status)
        assertEquals(2, page.activeCount)
        assertEquals(1, page.archivedCount)
        assertEquals(listOf("a", "b"), page.items.map { it.getString("code") })
        assertFalse(page.showRegister)
        assertEquals("فعال", ListingArchive.tabs(page.activeCount, page.archivedCount)[0].title)
        assertEquals("بایگانی‌شده", ListingArchive.tabs(page.activeCount, page.archivedCount)[1].title)
        assertEquals(
            "/express/partner/api/my-property/data?status=active",
            ListingArchive.portfolioPath(ListingArchive.ACTIVE),
        )
        assertEquals(
            "/express/partner/api/listings/data?status=archived",
            ListingArchive.listingsPath("ARCHIVED"),
        )
    }

    @Test
    fun archivedViewDropsActiveRowsAndUsesTheArchivedEmptyState() {
        val payload = JSONObject()
            .put("has_property", true)
            .put("counts", JSONObject().put("active", 1).put("archived", 0))
            .put("items", JSONArray().put(JSONObject().put("code", "live").put("archived", false)))
        val page = ListingArchive.page(payload, ListingArchive.ARCHIVED)
        assertTrue(page.items.isEmpty())
        assertEquals(ListingArchive.EMPTY_ARCHIVED, page.emptyMessage)
        assertFalse(page.showRegister)
        val none = ListingArchive.page(JSONObject().put("items", JSONArray()).put("has_property", false))
        assertEquals(ListingArchive.EMPTY_NONE, none.emptyMessage)
        assertTrue(none.showRegister)
        val activeEmpty = ListingArchive.page(
            JSONObject()
                .put("has_property", true)
                .put("counts", JSONObject().put("active", 0).put("archived", 2))
                .put("items", JSONArray()),
            ListingArchive.ACTIVE,
        )
        assertEquals(ListingArchive.EMPTY_ACTIVE, activeEmpty.emptyMessage)
    }

    @Test
    fun archiveAndRestoreAreIdempotentAndNeverCallDelete() {
        val body = ListingArchive.body(" 12 ")
        assertEquals("12", body.getString("code"))
        assertEquals(ListingArchive.ARCHIVE_PATH, ListingArchive.path(restore = false))
        assertEquals(ListingArchive.RESTORE_PATH, ListingArchive.path(restore = true))
        assertFalse(ListingArchive.cachePaths().any { it.endsWith("/delete") })
        val again = JSONObject()
            .put("ok", true)
            .put("http_status", 200)
            .put("code", "12")
            .put("archived", true)
            .put("is_archived", true)
            .put("archived_at", "2026-10-17T12:00:00Z")
            .put("archived_reason", "manual")
            .put("already", true)
            .put("message", "آگهی از قبل بایگانی شده است.")
        assertEquals(ListingArchive.Outcome.OK, ListingArchive.outcome(again))
        assertEquals("آگهی از قبل بایگانی شده است.", ListingArchive.message(again, "بایگانی آگهی انجام نشد."))
        val card = JSONObject().put("code", "12").put("title", "زمین")
        val next = ListingArchive.applyResult(card, again)
        assertTrue(ListingArchive.isArchived(next))
        assertEquals(ListingArchive.BADGE, "بسته")
        assertEquals(ListingArchive.RESTORE_LABEL, ListingArchive.actionLabel(true))
        assertEquals(ListingArchive.ARCHIVE_LABEL, ListingArchive.actionLabel(false))
    }

    @Test
    fun errorsStayDistinctForLoginForbiddenAndMissing() {
        val login = JSONObject().put("http_status", 401).put("error", "unauthorized")
        assertEquals(ListingArchive.Outcome.UNAUTHORIZED, ListingArchive.outcome(login))
        assertEquals("برای این کار وارد شوید.", ListingArchive.message(login, "خطا"))
        val forbidden = JSONObject()
            .put("ok", false)
            .put("http_status", 403)
            .put("error_code", "forbidden")
            .put("error", "این آگهی برای شما نیست.")
        assertEquals(ListingArchive.Outcome.FORBIDDEN, ListingArchive.outcome(forbidden))
        assertEquals("این آگهی برای شما نیست.", ListingArchive.message(forbidden, "خطا"))
        val missing = JSONObject()
            .put("ok", false)
            .put("http_status", 404)
            .put("error_code", "not_found")
            .put("error", "این آگهی پیدا نشد.")
        assertEquals(ListingArchive.Outcome.NOT_FOUND, ListingArchive.outcome(missing))
        assertEquals("این آگهی پیدا نشد.", ListingArchive.message(missing, "خطا"))
        val offline = JSONObject().put("offline", true).put("http_status", 0)
        assertEquals(ListingArchive.Outcome.OFFLINE, ListingArchive.outcome(offline))
    }
}
