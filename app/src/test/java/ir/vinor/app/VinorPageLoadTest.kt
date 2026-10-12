package ir.vinor.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VinorPageLoadTest {
    @Test
    fun freshCacheDoesNotCountAsFailure() {
        val cached = JSONObject().put("from_cache", true).put("http_status", 200)
        assertFalse(VinorPageLoad.networkFailed(wasFresh = true, cached))
        assertTrue(VinorPageLoad.networkFailed(wasFresh = false, cached))
    }

    @Test
    fun offlineAndHttpErrorsFailOnlyWhenDiskWasStale() {
        val offline = JSONObject().put("offline", true)
        val missing = JSONObject().put("http_status", 500)
        val ok = JSONObject().put("http_status", 200)
        assertTrue(VinorPageLoad.networkFailed(false, offline))
        assertTrue(VinorPageLoad.networkFailed(false, missing))
        assertFalse(VinorPageLoad.networkFailed(false, ok))
    }

    @Test
    fun memoryKeepsLivePayloadOnly() {
        val live = JSONObject().put("http_status", 200).put("title", "ملک")
        assertTrue(VinorPageLoad.canRemember(live))
        assertFalse(live.optBoolean("from_cache"))
        val copy = VinorPageLoad.rememberedCopy(live.put("from_cache", true))
        assertFalse(copy.optBoolean("from_cache"))
        assertFalse(VinorPageLoad.canRemember(JSONObject().put("from_cache", true).put("http_status", 200)))
        assertFalse(VinorPageLoad.canRemember(JSONObject().put("http_status", 401).put("session_expired", true)))
        assertTrue(VinorPageLoad.needsLogin(JSONObject().put("http_status", 401)))
    }
}
