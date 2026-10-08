package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/** تب‌ها، کارت و نتیجهٔ ثبت صفحهٔ پیگیری. فیلدها همان پاسخ درخواست‌ها هستند. */
object FollowRequests {
    const val EMPTY = "موردی در این تب نیست."
    const val FAIL = "ثبت ناموفق بود."

    enum class Tone { PRIMARY, SECONDARY, DANGER }

    data class Tab(val id: String, val title: String)

    data class Action(
        val label: String,
        val path: String,
        val body: JSONObject,
        val tone: Tone,
        val confirm: String? = null,
    )

    data class Card(
        val id: String,
        val title: String,
        val status: String,
        val customer: String,
        val whenText: String,
        val landCode: String,
        val actions: List<Action>,
    )

    fun tabs(array: JSONArray?): List<Tab> {
        if (array == null) return emptyList()
        val out = ArrayList<Tab>()
        for (i in 0 until array.length()) {
            val tab = array.optJSONObject(i) ?: continue
            val id = text(tab, "id")
            if (id.isBlank()) continue
            out.add(Tab(id, text(tab, "title").ifBlank { id }))
        }
        return out
    }

    fun activeId(current: String, suggested: String, tabs: List<Tab>): String {
        val known = tabs.map { it.id }.toSet()
        fun keep(id: String): Boolean = id.isNotBlank() && (known.isEmpty() || id in known)
        if (keep(current)) return current
        if (keep(suggested)) return suggested
        return tabs.firstOrNull()?.id ?: "requests"
    }

    fun items(payload: JSONObject, tab: String): List<JSONObject> {
        val array = payload.optJSONArray("items") ?: payload.optJSONArray("inbox_items") ?: JSONArray()
        val out = ArrayList<JSONObject>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val itemTab = text(item, "pipeline_tab").ifBlank { "requests" }
            if (tab.isNotBlank() && itemTab != tab) continue
            out.add(item)
        }
        return out
    }

    fun card(item: JSONObject): Card {
        val id = idOf(item)
        val title = text(item, "title").ifBlank { text(item, "land_title") }.ifBlank { "پیگیری" }
        return Card(
            id = id,
            title = title,
            status = text(item, "status_label"),
            customer = text(item, "customer_name"),
            whenText = text(item, "visit_when"),
            landCode = text(item, "land_code"),
            actions = actions(item, id),
        )
    }

    fun actions(item: JSONObject, id: String): List<Action> {
        if (id.isBlank()) return emptyList()
        val base = "/express/partner/requests/$id"
        val all = ArrayList<Action>()
        fun add(
            flag: String,
            label: String,
            suffix: String,
            tone: Tone,
            confirm: String? = null,
            body: JSONObject = JSONObject(),
        ) {
            if (!flag(item, flag)) return
            all.add(Action(label, base + suffix, body, tone, confirm))
        }
        add("can_complete", "انجام شد", "/complete", Tone.PRIMARY)
        add(
            "can_register_result",
            "علاقه‌مند",
            "/visit-result",
            Tone.PRIMARY,
            body = JSONObject().put("outcome", "interested"),
        )
        add("can_confirm_deal", "تأیید معامله", "/deal-confirm", Tone.PRIMARY)
        add("can_close_deal", "بستن", "/deal-close", Tone.SECONDARY)
        add("can_revisit", "بازدید مجدد", "/revisit", Tone.SECONDARY)
        add(
            "can_register_result",
            "پیگیری",
            "/visit-result",
            Tone.SECONDARY,
            body = JSONObject().put("outcome", "follow_up"),
        )
        add("can_no_show", "عدم حضور", "/no-show", Tone.SECONDARY)
        add(
            "can_register_result",
            "بی‌علاقه",
            "/visit-result",
            Tone.SECONDARY,
            body = JSONObject().put("outcome", "not_interested"),
        )
        add("can_reject_deal", "رد معامله", "/deal-reject", Tone.DANGER, "این معامله رد شود؟")
        add("can_remove", "حذف", "/remove", Tone.DANGER, "این مورد از پیگیری حذف شود؟")
        if (all.none { it.tone == Tone.PRIMARY }) {
            val index = all.indexOfFirst { it.tone == Tone.SECONDARY }
            if (index >= 0) all[index] = all[index].copy(tone = Tone.PRIMARY)
        }
        var primarySeen = false
        return all.map { action ->
            if (action.tone != Tone.PRIMARY) action
            else if (!primarySeen) {
                primarySeen = true
                action
            } else {
                action.copy(tone = Tone.SECONDARY)
            }
        }
    }

    fun isOk(payload: JSONObject): Boolean {
        val code = payload.optInt("http_status", 200)
        if (code !in 200..299) return false
        if (payload.optInt("http_status") == 401 || payload.optString("error") == "unauthorized") return false
        if (payload.optString("error") == "csrf_expired") return false
        if (payload.has("success") && !payload.optBoolean("success")) return false
        if (payload.has("ok") && !payload.optBoolean("ok")) return false
        if (payload.optString("error").isNotBlank()) return false
        return true
    }

    fun userMessage(payload: JSONObject, fallback: String = FAIL): String {
        if (payload.optBoolean("offline")) {
            return payload.optString("message").trim().ifBlank { "برای انجام این عملیات اینترنت لازم است." }
        }
        val message = payload.optString("message").trim()
        if (message.isNotEmpty() && message != "Not Found" && payload.optString("error") != "csrf_expired") {
            return message
        }
        val fields = payload.optJSONObject("fields") ?: payload.optJSONObject("errors")
        if (fields != null) {
            val keys = fields.keys()
            while (keys.hasNext()) {
                val value = fields.optString(keys.next()).trim()
                if (value.isNotEmpty()) return value
            }
        }
        val error = payload.optString("error").trim()
        if (error.any { it.code > 127 }) return error
        if (payload.optInt("http_status") == 401 || error == "unauthorized") return "برای ادامه وارد شوید."
        return fallback
    }

    private fun flag(obj: JSONObject, key: String): Boolean {
        if (!obj.has(key) || obj.isNull(key)) return false
        return when (val value = obj.opt(key)) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.equals("true", true) || value == "1"
            else -> false
        }
    }

    private fun idOf(obj: JSONObject): String {
        if (!obj.has("id") || obj.isNull("id")) return ""
        val raw = obj.opt("id") ?: return ""
        val text = when (raw) {
            is Number -> {
                val whole = raw.toLong()
                if (raw.toDouble() == whole.toDouble()) whole.toString() else raw.toString()
            }
            else -> raw.toString()
        }.trim()
        return if (text.isBlank() || text.equals("null", true)) "" else text
    }

    private fun text(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val value = obj.optString(key).trim()
        return if (value.isBlank() || value.equals("null", true)) "" else value
    }
}
