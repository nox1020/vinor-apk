package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLiveTest {

    @Test
    fun pollRunsOnlyForAnOpenThreadThatIsIdle() {
        assertTrue(ChatLive.shouldPoll(true, "t1", false, false, false))
        assertFalse(ChatLive.shouldPoll(false, "t1", false, false, false))
        assertFalse(ChatLive.shouldPoll(true, null, false, false, false))
        assertFalse(ChatLive.shouldPoll(true, "  ", false, false, false))
        assertFalse(ChatLive.shouldPoll(true, "t1", true, false, false))
        assertFalse(ChatLive.shouldPoll(true, "t1", false, true, false))
        assertFalse(ChatLive.shouldPoll(true, "t1", false, false, true))
    }

    @Test
    fun historyPathStaysCanonicalUntilAThreadExists() {
        assertEquals("/express/partner/ai/history", ChatLive.historyPath(null))
        assertEquals("/express/partner/ai/history", ChatLive.historyPath("  "))
        assertEquals(
            "/express/partner/ai/history?thread_id=abc%201",
            ChatLive.historyPath("abc 1"),
        )
    }

    @Test
    fun syncPathCarriesCursorCountAndEscalations() {
        assertEquals(
            "/express/partner/ai/sync?thread_id=t1&count=3&after=2026-01-01T00%3A00%3A00&exclude_esc=e1%2Ce2",
            ChatLive.syncPath("t1", 3, "2026-01-01T00:00:00", listOf("e1", "e2")),
        )
        assertEquals(
            "/express/partner/ai/sync?thread_id=t1&count=0",
            ChatLive.syncPath("t1", 0, "  ", emptyList()),
        )
    }

    @Test
    fun fingerprintChangesWhenTheLastBubbleChangesAtTheSameCount() {
        val first = JSONArray()
            .put(JSONObject().put("id", "1").put("role", "user").put("content", "سلام"))
            .put(JSONObject().put("id", "2").put("role", "assistant").put("content", "درود"))
        val edited = JSONArray()
            .put(JSONObject().put("id", "1").put("role", "user").put("content", "سلام"))
            .put(JSONObject().put("id", "2").put("role", "assistant").put("content", "درود دوباره"))
        assertEquals(2, first.length())
        assertEquals(edited.length(), first.length())
        assertTrue(ChatLive.fingerprint(first) != ChatLive.fingerprint(edited))
        assertEquals(ChatLive.fingerprint(first), ChatLive.fingerprint(JSONArray(first.toString())))
    }

    @Test
    fun unseenSkipsKnownIdsAndHiddenRows() {
        val have = JSONArray()
            .put(JSONObject().put("id", "1").put("role", "user").put("content", "سلام"))
            .put(JSONObject().put("id", "2").put("role", "user").put("content", "پیش‌نویس"))
        val incoming = JSONArray()
            .put(JSONObject().put("id", "1").put("role", "user").put("content", "سلام"))
            .put(JSONObject().put("id", "9").put("role", "user").put("content", "پیش‌نویس").put("created_at", "t"))
            .put(JSONObject().put("id", "3").put("role", "assistant").put("content", "جدید"))
            .put(JSONObject().put("role", "user").put("content", "مخفی").put("hide_ui", true))
        val fresh = ChatLive.unseen(have, incoming)
        assertEquals(2, fresh.length())
        assertEquals("9", fresh.getJSONObject(0).getString("id"))
        assertEquals("3", fresh.getJSONObject(1).getString("id"))
    }

    @Test
    fun emptySyncPausesAndAMessageResetsTheStreak() {
        assertEquals(ChatLive.SyncOutcome.AUTH, ChatLive.syncOutcome(JSONObject().put("http_status", 401)))
        assertEquals(
            ChatLive.SyncOutcome.EMPTY,
            ChatLive.syncOutcome(JSONObject().put("ok", true).put("messages", JSONArray())),
        )
        assertEquals(
            ChatLive.SyncOutcome.MESSAGES,
            ChatLive.syncOutcome(
                JSONObject().put("ok", true).put("messages", JSONArray().put(JSONObject().put("id", "1"))),
            ),
        )
        assertEquals(2, ChatLive.nextEmptyStreak(1, false))
        assertEquals(0, ChatLive.nextEmptyStreak(40, true))
        assertFalse(ChatLive.shouldPause(59))
        assertTrue(ChatLive.shouldPause(60))
    }

    @Test
    fun exchangeKeepsReplyWithoutErasingThePreviousTurn() {
        val history = JSONObject().put(
            "messages",
            JSONArray().put(JSONObject().put("id", "old").put("role", "user").put("content", "قیمت؟")),
        )
        val response = JSONObject()
            .put("ok", true)
            .put("thread_id", "thr")
            .put("reply", "۱۲ میلیارد")
            .put("land_codes", JSONArray().put("L1"))
        val next = ChatLive.applyExchange(history, "قیمت؟", null, response, null)
        val messages = next.getJSONArray("messages")
        assertEquals("thr", next.getString("thread_id"))
        assertEquals(3, messages.length())
        assertEquals("old", messages.getJSONObject(0).getString("id"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("L1", messages.getJSONObject(2).getJSONArray("land_codes").getString(0))
    }

    @Test
    fun exchangePrefersServerMessagesOverTheLocalReply() {
        val history = JSONObject().put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "user").put("content", "سلام").put("pending", true))
                .put(JSONObject().put("role", "assistant").put("content", "علیک").put("pending", true)),
        )
        val server = JSONArray()
            .put(JSONObject().put("id", "u").put("role", "user").put("content", "سلام").put("created_at", "a"))
            .put(JSONObject().put("id", "a").put("role", "assistant").put("content", "علیک").put("created_at", "b"))
        val response = JSONObject().put("ok", true).put("reply", "نباید تکرار شود").put("messages", server)
        val next = ChatLive.applyExchange(history, "سلام", null, response, "old")
        val messages = next.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("u", messages.getJSONObject(0).getString("id"))
        assertEquals("a", messages.getJSONObject(1).getString("id"))
        assertEquals("b", ChatLive.lastSyncAt(messages))
        assertTrue(ChatLive.sameVisible(history.getJSONArray("messages"), messages))
        assertEquals(0, ChatLive.syncCount(history.getJSONArray("messages")))
        assertEquals(2, ChatLive.syncCount(messages))
    }

    @Test
    fun emptyHistoryDoesNotWipeAnOpenTranscript() {
        val have = JSONArray().put(JSONObject().put("id", "1").put("role", "user").put("content", "سلام"))
        assertFalse(ChatLive.shouldReplaceTranscript(have, JSONArray()))
        assertFalse(ChatLive.shouldReplaceTranscript(have, JSONArray(have.toString())))
        assertTrue(
            ChatLive.shouldReplaceTranscript(
                JSONArray(),
                JSONArray().put(JSONObject().put("id", "1").put("role", "assistant").put("content", "درود")),
            ),
        )
    }

    @Test
    fun reconcileReplacesEditedIdAndAppendsNew() {
        val have = JSONArray().put(JSONObject().put("id", "2").put("role", "assistant").put("content", "درود"))
        val incoming = JSONArray()
            .put(JSONObject().put("id", "2").put("role", "assistant").put("content", "درود دوباره"))
            .put(JSONObject().put("id", "3").put("role", "user").put("content", "باشه"))
        val next = ChatLive.reconcile(have, incoming)
        assertEquals(2, next!!.length())
        assertEquals("درود دوباره", next.getJSONObject(0).getString("content"))
        assertEquals("3", next.getJSONObject(1).getString("id"))
        assertNull(ChatLive.reconcile(next, incoming))
    }

    @Test
    fun messagesCanSitUnderData() {
        val obj = JSONObject().put(
            "data",
            JSONObject().put("messages", JSONArray().put(JSONObject().put("id", "x"))),
        )
        assertEquals(1, ChatLive.messagesOf(obj).length())
        assertEquals("c", ChatLive.laterSyncAt("a", "c"))
        assertEquals("c", ChatLive.laterSyncAt("c", ""))
    }
}
