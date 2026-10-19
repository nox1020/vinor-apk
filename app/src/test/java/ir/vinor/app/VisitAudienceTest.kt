package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitAudienceTest {
    private val owner = "09120000000"
    private val other = "09121111111"
    private val booker = "09123334444"

    @Test
    fun receivedVisitIsOnlyListedForTheListingOwner() {
        val incoming = JSONObject()
            .put("id", "15")
            .put("direction", "incoming")
            .put("owner_phone", "۰۹۱۲۰۰۰۰۰۰۰")
            .put("pipeline_tab", "visits")
            .put("title", "ملک مالک")
        val outgoing = JSONObject()
            .put("id", "16")
            .put("direction", "outgoing")
            .put("owner_phone", owner)
            .put("pipeline_tab", "requests")
            .put("title", "درخواست خودم")
        val offer = JSONObject()
            .put("kind", "offer")
            .put("id", "off-1")
            .put("direction", "incoming")
            .put("pipeline_tab", "deals")
        val payload = JSONObject().put("cards", JSONArray().put(incoming).put(outgoing).put(offer))

        assertEquals(setOf("off-1", "16", "15"), ids(FollowRequests.feed(payload, FollowRequests.FILTER_ALL, owner)))
        assertEquals(setOf("off-1", "16"), ids(FollowRequests.feed(payload, FollowRequests.FILTER_ALL, other)))
        assertEquals(setOf("off-1", "16"), ids(FollowRequests.feed(payload, FollowRequests.FILTER_ALL, "")))
        assertEquals(1, FollowRequests.counters(payload, owner).visits)
        assertEquals(0, FollowRequests.counters(payload, other).visits)
        assertTrue(FollowRequests.visibleToViewer(outgoing, booker))
        assertFalse(
            FollowRequests.visibleToViewer(
                JSONObject().put("direction", "incoming").put("id", "x"),
                owner,
            ),
        )
    }

    @Test
    fun visitNotificationRequiresTheRecipientAndSkipsTheNonOwnerPlusCopy() {
        val mine = JSONObject()
            .put("id", "n1")
            .put("type", "visit")
            .put("source", "visit_inbox")
            .put("user_id", owner)
            .put("title", "بازدید جدید")
        val theirs = JSONObject(mine.toString()).put("id", "n2").put("user_id", other)
        val plus = JSONObject(mine.toString()).put("id", "n3").put("source", VisitAudience.PLUS_VISIT_SOURCE)
        val wallet = JSONObject().put("id", "n4").put("type", "info").put("user_id", owner).put("title", "کیف پول")
        val anonymousVisit = JSONObject().put("id", "n5").put("type", "visit").put("title", "بازدید جدید")

        assertTrue(VisitAudience.showNotification(mine, "0912 000 0000"))
        assertFalse(VisitAudience.showNotification(theirs, owner))
        assertFalse(VisitAudience.showNotification(plus, owner))
        assertTrue(VisitAudience.showNotification(wallet, owner))
        assertFalse(VisitAudience.showNotification(wallet, other))
        assertFalse(VisitAudience.showNotification(anonymousVisit, owner))
    }

    @Test
    fun pushIsShownOnlyWhenThePayloadOrRegisteredTokenMatchesThisAccount() {
        val visit = mapOf("title" to "بازدید جدید", "body" to "درخواست ثبت شد", "url" to "/express/partner/home")
        val named = visit + mapOf("owner_phone" to owner)
        assertTrue(VisitAudience.showPush(named, owner, other))
        assertFalse(VisitAudience.showPush(named, other, other))
        assertFalse(VisitAudience.showPush(visit, other, owner))
        assertTrue(VisitAudience.showPush(visit, owner, owner))
        assertFalse(VisitAudience.showPush(visit, owner, ""))
        assertFalse(VisitAudience.accountMismatch(owner, owner))
        assertTrue(VisitAudience.accountMismatch(owner, other))
        val postchi = mapOf("title" to "پیام جدید", "body" to "پستچی")
        assertTrue(VisitAudience.showPush(postchi, owner, owner))
        assertFalse(VisitAudience.showPush(postchi, owner, other))
    }

    private fun ids(rows: List<JSONObject>): Set<String> = rows.map { FollowRequests.card(it).id }.toSet()
}
