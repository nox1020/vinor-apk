package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * چت زنده مثل وب: تاریخچه برای اولین رنگ، بعد همگام‌سازی سبک هر ۱۵ ثانیه.
 * کشِ تازه نباید پیام جدید را قایم کند و poll یکسان نباید لیست را از نو بسازد.
 */
object ChatLive {
    const val POLL_MS = 15_000L
    const val EMPTY_POLL_PAUSE = 60
    const val HISTORY = "/express/partner/ai/history"
    const val SYNC = "/express/partner/ai/sync"

    fun shouldPoll(
        open: Boolean,
        threadId: String?,
        sending: Boolean,
        inFlight: Boolean,
        paused: Boolean,
    ): Boolean {
        if (!open) return false
        if (threadId.isNullOrBlank()) return false
        if (sending || inFlight || paused) return false
        return true
    }

    /** بدون ترد همان مسیر گرم‌شده؛ با ترد فقط همان گفتگو. */
    fun historyPath(threadId: String?): String {
        val tid = threadId?.trim().orEmpty()
        if (tid.isEmpty()) return HISTORY
        return "$HISTORY?thread_id=${enc(tid)}"
    }

    fun syncPath(threadId: String, count: Int, after: String, escalationIds: List<String>): String {
        val q = StringBuilder()
        q.append(SYNC)
        q.append("?thread_id=").append(enc(threadId.trim()))
        q.append("&count=").append(count.coerceAtLeast(0))
        val stamp = after.trim()
        if (stamp.isNotEmpty()) q.append("&after=").append(enc(stamp))
        if (escalationIds.isNotEmpty()) {
            q.append("&exclude_esc=").append(enc(escalationIds.joinToString(",")))
        }
        return q.toString()
    }

    fun messagesOf(obj: JSONObject?): JSONArray {
        if (obj == null) return JSONArray()
        obj.optJSONArray("messages")?.let { return it }
        obj.optJSONObject("data")?.optJSONArray("messages")?.let { return it }
        return JSONArray()
    }

    fun isHidden(msg: JSONObject?): Boolean {
        if (msg == null) return true
        return msg.optBoolean("hide_ui") || msg.optBoolean("hide_user")
    }

    /** اثر انگشت متنِ دیده‌شونده؛ تعدادِ تنها کافی نیست. */
    fun fingerprint(messages: JSONArray?): String {
        if (messages == null || messages.length() == 0) return "0"
        val sb = StringBuilder()
        var n = 0
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (isHidden(msg)) continue
            n += 1
            sb.append('\n').append(identity(msg))
        }
        return "$n$sb"
    }

    fun lastSyncAt(messages: JSONArray?): String {
        var max = ""
        if (messages == null) return max
        for (i in 0 until messages.length()) {
            val t = messages.optJSONObject(i)?.optString("created_at").orEmpty()
            if (t.isNotBlank() && t > max) max = t
        }
        return max
    }

    fun laterSyncAt(current: String, candidate: String): String {
        val next = candidate.trim()
        if (next.isEmpty()) return current
        return if (next > current) next else current
    }

    fun escalationIds(messages: JSONArray?): List<String> {
        if (messages == null) return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until messages.length()) {
            val id = messages.optJSONObject(i)?.optString("escalation_id").orEmpty()
            if (id.isNotBlank() && id !in ids) ids.add(id)
        }
        return ids
    }

    /**
     * پیام تازه را اضافه می‌کند و اگر همان id با متن تازه آمده باشد جایگزین می‌کند.
     * null یعنی چیزی برای کشیدن دوباره نیست.
     */
    fun reconcile(existing: JSONArray?, incoming: JSONArray?): JSONArray? {
        val next = JSONArray((existing ?: JSONArray()).toString())
        if (incoming == null || incoming.length() == 0) return null
        var changed = false
        for (i in 0 until incoming.length()) {
            val msg = incoming.optJSONObject(i) ?: continue
            if (isHidden(msg)) continue
            val id = msg.optString("id")
            if (id.isNotBlank()) {
                var found = false
                for (j in 0 until next.length()) {
                    val old = next.optJSONObject(j) ?: continue
                    if (old.optString("id") != id) continue
                    found = true
                    if (identity(old) != identity(msg)) {
                        next.put(j, msg)
                        changed = true
                    }
                    break
                }
                if (found) continue
            }
            if (!seen(next, msg)) {
                next.put(msg)
                changed = true
            }
        }
        return if (changed) next else null
    }

    fun unseen(existing: JSONArray?, incoming: JSONArray?): JSONArray {
        val have = existing ?: JSONArray()
        val out = JSONArray()
        if (incoming == null) return out
        for (i in 0 until incoming.length()) {
            val msg = incoming.optJSONObject(i) ?: continue
            if (isHidden(msg)) continue
            if (!seen(have, msg)) out.put(msg)
        }
        return out
    }

    enum class SyncOutcome { AUTH, EMPTY, MESSAGES }

    fun syncOutcome(obj: JSONObject): SyncOutcome {
        val status = obj.optInt("http_status")
        if (status == 401 || status == 403) return SyncOutcome.AUTH
        val messages = messagesOf(obj)
        if (!obj.optBoolean("ok") || messages.length() == 0) return SyncOutcome.EMPTY
        return SyncOutcome.MESSAGES
    }

    fun nextEmptyStreak(streak: Int, hadMessages: Boolean): Int =
        if (hadMessages) 0 else streak + 1

    fun shouldPause(streak: Int): Boolean = streak >= EMPTY_POLL_PAUSE

    /**
     * پاسخ ارسال را داخل رونوشت حافظه می‌گذارد تا باز شدن بعدی همان لحظهٔ قبل از ارسال نباشد.
     */
    fun applyExchange(
        history: JSONObject?,
        userText: String,
        attachment: JSONObject?,
        response: JSONObject,
        threadId: String?,
    ): JSONObject {
        val base = if (history != null) JSONObject(history.toString()) else JSONObject()
        base.remove("from_cache")
        val tid = response.optString("thread_id").ifBlank { threadId.orEmpty() }
        if (tid.isNotBlank()) base.put("thread_id", tid)
        val messages = messagesOf(base)
        val server = messagesOf(response)
        if (server.length() > 0 && server !== messages) {
            dropPendingUser(messages, userText)
            for (i in 0 until server.length()) {
                val msg = server.optJSONObject(i) ?: continue
                if (isHidden(msg)) continue
                if (!seen(messages, msg)) messages.put(msg)
            }
        } else if (response.optBoolean("ok") || response.optString("reply").isNotBlank()) {
            if (userText.isNotBlank() || attachment != null) {
                val user = JSONObject().put("role", "user").put("content", userText)
                if (attachment != null) user.put("attachment", attachment)
                if (!seen(messages, user)) messages.put(user)
            }
            val reply = response.optString("reply")
            val codes = response.optJSONArray("land_codes")
            if (reply.isNotBlank() || (codes != null && codes.length() > 0)) {
                val asst = JSONObject().put("role", "assistant").put("content", reply)
                if (codes != null) asst.put("land_codes", codes)
                val created = response.optString("created_at")
                if (created.isNotBlank()) asst.put("created_at", created)
                if (!seen(messages, asst)) messages.put(asst)
            }
        }
        base.put("messages", messages)
        return base
    }

    private fun identity(msg: JSONObject): String {
        val id = msg.optString("id")
        val role = msg.optString("role")
        val ts = msg.optString("created_at")
        val esc = msg.optString("escalation_id")
        val att = msg.optJSONObject("attachment")?.optString("url")
            ?: msg.optJSONObject("meta")?.optJSONObject("attachment")?.optString("url").orEmpty()
        val content = msg.optString("content").take(96)
        val codes = msg.optJSONArray("land_codes")?.toString().orEmpty()
        return "$id|$role|$ts|$esc|$att|$content|$codes"
    }

    private fun seen(existing: JSONArray, msg: JSONObject): Boolean {
        val id = msg.optString("id")
        val esc = msg.optString("escalation_id")
        val role = msg.optString("role")
        val ts = msg.optString("created_at")
        val content = msg.optString("content").take(96)
        for (i in 0 until existing.length()) {
            val have = existing.optJSONObject(i) ?: continue
            if (id.isNotBlank() && have.optString("id") == id) return true
            if (esc.isNotBlank() && have.optString("escalation_id") == esc) return true
            if (ts.isNotBlank() && have.optString("created_at") == ts && have.optString("role") == role) return true
            if (have.optString("id").isBlank() &&
                have.optString("created_at").isBlank() &&
                role == "user" &&
                have.optString("role") == "user" &&
                content.isNotBlank() &&
                have.optString("content").take(96) == content
            ) {
                return true
            }
        }
        return false
    }

    private fun dropPendingUser(messages: JSONArray, userText: String) {
        for (i in messages.length() - 1 downTo 0) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role") != "user") continue
            if (msg.optString("id").isNotBlank() || msg.optString("created_at").isNotBlank()) continue
            if (msg.optString("content") != userText) continue
            messages.remove(i)
            return
        }
    }

    private fun enc(raw: String): String =
        URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
}
