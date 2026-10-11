package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionGateTest {

    @Test
    fun guestHasNoChatAccount() {
        assertNull(ChatSessionGate.accountKey(false, "abc123"))
        assertNull(ChatSessionGate.accountKey(false, VinorCacheSession.ANON))
    }

    @Test
    fun signedInAccountUsesFingerprint() {
        assertEquals("abc123", ChatSessionGate.accountKey(true, " abc123 "))
        assertEquals(VinorCacheSession.ANON, ChatSessionGate.accountKey(true, " "))
    }

    @Test
    fun memoryDropsOnLogoutAndAccountSwitch() {
        assertTrue(ChatSessionGate.shouldDropMemory("user-a", null))
        assertTrue(ChatSessionGate.shouldDropMemory("user-a", "user-b"))
        assertTrue(ChatSessionGate.shouldDropMemory(null, "user-b"))
        assertFalse(ChatSessionGate.shouldDropMemory("user-a", "user-a"))
    }

    @Test
    fun historyAuthFailureIsNotHiddenByCache() {
        assertFalse(ChatSessionGate.cacheMayHideAuthFailure("/express/partner/ai/history", 401))
        assertFalse(
            ChatSessionGate.cacheMayHideAuthFailure(
                "https://vinor.ir/express/partner/ai/history?x=1",
                403,
            ),
        )
        assertTrue(ChatSessionGate.cacheMayHideAuthFailure("/express/partner/home/data", 401))
        assertTrue(ChatSessionGate.cacheMayHideAuthFailure("/express/partner/ai/history", 500))
        assertTrue(ChatSessionGate.cacheMayHideAuthFailure("/express/partner/ai/history", 200))
    }

    @Test
    fun historyMemoryKeyIsScopedToAccount() {
        val url = "https://vinor.ir/express/partner/ai/history"
        val a = VinorApiCacheStore.sessionMemoryKey("user-a", url)
        val b = VinorApiCacheStore.sessionMemoryKey("user-b", url)
        assertFalse(a == b)
        assertEquals(a, VinorApiCacheStore.sessionMemoryKey(" user-a ", url))
    }
}
