package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/** تب‌ها، کارت و نتیجهٔ ثبت صفحهٔ پیگیری. فیلدها همان پاسخ درخواست‌ها هستند. */
object FollowRequests {
    const val DATA_PATH = "/express/partner/api/requests/data"
    const val PAGE_PATH = "/express/partner/requests"
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
        val opensWallet: Boolean = false,
    )

    data class Card(
        val id: String,
        val title: String,
        val status: String,
        val customer: String,
        val whenText: String,
        val landCode: String,
        val commission: String,
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
        if (keepTab(current, known)) return current
        if (keepTab(suggested, known)) return suggested
        return tabs.firstOrNull()?.id ?: "requests"
    }

    /** بعد از ثبت مرحله: تبِ خود کارت، بعد تب سرور، بعد حدس مرحله. */
    fun focusAfterAction(serverTab: String, located: String, hint: String, tabs: List<Tab>): String {
        val known = tabs.map { it.id }.toSet()
        if (keepTab(located, known)) return located
        if (keepTab(serverTab, known)) return serverTab
        if (keepTab(hint, known)) return hint
        return tabs.firstOrNull()?.id ?: "requests"
    }

    fun requestIdFromPath(path: String): String {
        val parts = path.substringBefore('?').trim().trim('/').split('/')
        val index = parts.indexOfLast { it == "requests" }
        if (index < 0 || index + 1 >= parts.size) return ""
        val id = parts[index + 1].trim()
        return if (id.isBlank() || id.equals("data", true)) "" else id
    }

    /** حذف، رد، عدم حضور و بی‌علاقگی کارت را به تب بعدی نمی‌برند. */
    fun movesCard(path: String, body: JSONObject): Boolean {
        val bare = path.substringBefore('?')
        if (!bare.contains("/requests/")) return false
        if (bare.endsWith("/remove") || bare.endsWith("/deal-reject") || bare.endsWith("/no-show")) return false
        if (bare.endsWith("/visit-result")) {
            val outcome = body.optString("outcome").trim()
            return outcome == "interested" || outcome == "follow_up"
        }
        return bare.endsWith("/complete") ||
            bare.endsWith("/revisit") ||
            bare.endsWith("/deal-confirm") ||
            bare.endsWith("/deal-close")
    }

    fun stageHint(path: String, body: JSONObject, tabs: List<Tab>): String {
        val known = tabs.map { it.id }
        fun pick(vararg ids: String): String = ids.firstOrNull { known.isEmpty() || it in known }.orEmpty()
        val bare = path.substringBefore('?')
        val outcome = body.optString("outcome").trim()
        return when {
            bare.endsWith("/deal-close") ->
                pick("done", "success", "closed", "sold", "completed", "deals", "deal")
            bare.endsWith("/deal-confirm") -> pick("deals", "deal")
            bare.endsWith("/visit-result") && outcome == "interested" -> pick("deals", "deal")
            bare.endsWith("/visit-result") && outcome == "follow_up" -> pick("visits", "visit")
            bare.endsWith("/complete") || bare.endsWith("/revisit") -> pick("visits", "visit")
            else -> ""
        }
    }

    fun locateTab(payload: JSONObject, id: String, tabs: List<Tab>): String {
        if (id.isBlank()) return ""
        val names = LinkedHashSet<String>()
        if (tabs.isNotEmpty()) {
            tabs.forEach { if (it.id.isNotBlank()) names.add(it.id) }
        } else {
            groupedOf(payload)?.keys()?.forEach { names.add(it) }
            names.addAll(listOf("requests", "visits", "deals", "deal"))
        }
        for (tab in names) {
            if (items(payload, tab).any { idOf(it) == id }) return tab
        }
        return ""
    }

    fun refreshesWallet(path: String): Boolean = path.substringBefore('?').endsWith("/deal-close")

    fun walletRefreshPaths(): List<String> = listOf(
        "/express/partner/commissions/data",
        WalletWithdraw.HOME_DATA,
    )

    fun items(payload: JSONObject, tab: String): List<JSONObject> {
        val grouped = groupedOf(payload)
        val scoped = grouped?.optJSONArray(tab)
            ?: payload.optJSONArray(tab)
        val array = scoped
            ?: payload.optJSONArray("items")
            ?: payload.optJSONArray("inbox_items")
            ?: JSONArray()
        val out = ArrayList<JSONObject>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val itemTab = text(item, "pipeline_tab")
            if (scoped == null && tab.isNotBlank()) {
                val effectiveTab = itemTab.ifBlank { "requests" }
                if (effectiveTab != tab) continue
            }
            out.add(item)
        }
        return out
    }

    fun card(item: JSONObject, tab: String = ""): Card {
        val id = idOf(item)
        val title = text(item, "title").ifBlank { text(item, "land_title") }.ifBlank { "پیگیری" }
        return Card(
            id = id,
            title = title,
            status = displayStatus(item, tab),
            customer = text(item, "customer_name"),
            whenText = text(item, "visit_when"),
            landCode = text(item, "land_code"),
            commission = commissionLine(item),
            actions = actions(item, id, tab),
        )
    }

    fun doneMessage(path: String, payload: JSONObject): String {
        val note = payload.optString("message").trim()
        if (note.isNotEmpty() && !note.equals("null", true)) return note
        return when {
            path.endsWith("/deal-confirm") -> "تأیید نهایی ثبت شد."
            path.endsWith("/deal-close") -> "معامله انجام شد. پورسانت به کیف پول واریز می‌شود."
            else -> "ثبت شد."
        }
    }

    fun actions(item: JSONObject, id: String, tab: String = ""): List<Action> {
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
        add(
            "can_register_result",
            "علاقه‌مند",
            "/visit-result",
            Tone.PRIMARY,
            body = JSONObject().put("outcome", "interested"),
        )
        add("can_complete", "انجام شد", "/complete", Tone.PRIMARY)
        add(
            "can_confirm_deal",
            "تأیید نهایی",
            "/deal-confirm",
            Tone.PRIMARY,
            "تأیید نهایی این پرونده ثبت شود؟",
        )
        add(
            "can_close_deal",
            "انجام معامله",
            "/deal-close",
            Tone.SECONDARY,
            "انجام معامله ثبت شود؟ پورسانت به کیف پول واریز می‌شود.",
        )
        add("can_revisit", "بازدید مجدد", "/revisit", Tone.SECONDARY)
        add(
            "can_register_result",
            "پیگیری",
            "/visit-result",
            Tone.SECONDARY,
            body = JSONObject().put("outcome", "follow_up"),
        )
        add("can_no_show", "عدم حضور", "/no-show", Tone.SECONDARY, "عدم حضور مشتری ثبت شود؟")
        add(
            "can_register_result",
            "بی‌علاقه",
            "/visit-result",
            Tone.SECONDARY,
            body = JSONObject().put("outcome", "not_interested"),
        )
        add("can_reject_deal", "رد معامله", "/deal-reject", Tone.DANGER, "این معامله رد شود؟")
        add("can_remove", "حذف", "/remove", Tone.DANGER, "این مورد از پیگیری حذف شود؟")
        if (depositReady(item, tab)) {
            all.add(
                Action(
                    label = "واریز پورسانت",
                    path = "/express/partner/commissions",
                    body = JSONObject(),
                    tone = Tone.SECONDARY,
                    opensWallet = true,
                ),
            )
        }
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

    fun isUnauthorized(payload: JSONObject): Boolean {
        val error = payload.optString("error").trim().lowercase()
        return payload.optInt("http_status") == 401 ||
            error in setOf("unauthorized", "login_required", "session_expired")
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

    private fun commissionLine(item: JSONObject): String {
        val amount = WalletWithdraw.money(item, "commission_amount", "commission", "payout_amount")
        val status = commissionStatus(item)
        return when {
            amount > 0L && status.isNotBlank() -> "پورسانت ${PartnerFormat.toman(amount)} تومان · $status"
            amount > 0L -> "پورسانت ${PartnerFormat.toman(amount)} تومان"
            status.isNotBlank() -> "پورسانت · $status"
            else -> ""
        }
    }

    private fun commissionKey(item: JSONObject): String =
        text(item, "commission_status")
            .ifBlank { text(item, "payout_status") }
            .ifBlank { text(item, "deal_status") }
            .ifBlank { text(item, "result") }

    private fun commissionStatus(item: JSONObject): String =
        WalletWithdraw.commissionStatusText(commissionKey(item))

    private fun displayStatus(item: JSONObject, tab: String): String {
        val server = text(item, "status_label")
        if (server.isNotBlank()) return server
        return if (dealSucceeded(item, tab)) "معامله موفق" else ""
    }

    private fun dealSucceeded(item: JSONObject, tab: String): Boolean {
        val kind = WalletWithdraw.commissionKind(commissionKey(item))
        if (kind == "paid" || kind == "success") return true
        if (!depositReady(item, tab)) return false
        val stage = text(item, "pipeline_tab").ifBlank { tab }
        return stage in DEAL_STAGES
    }

    private fun depositReady(item: JSONObject, tab: String): Boolean {
        val kind = WalletWithdraw.commissionKind(commissionKey(item))
        if (kind == "rejected") return false
        if (kind == "paid" || kind == "success") return true
        if (flag(item, "can_open_commission") || flag(item, "commission_credited")) return true
        if (flag(item, "can_confirm_deal") || flag(item, "can_close_deal")) return false
        val stage = text(item, "pipeline_tab").ifBlank { tab }
        if (stage !in DEAL_STAGES) return false
        val amount = WalletWithdraw.money(item, "commission_amount", "commission", "payout_amount")
        val awaiting = amount > 0L || kind == "pending" || kind == "approved"
        if (flag(item, "can_register_result") || flag(item, "can_reject_deal")) return awaiting
        return true
    }

    private val DEAL_STAGES = setOf("deals", "deal", "done", "success", "closed", "sold", "completed")

    private fun groupedOf(payload: JSONObject): JSONObject? =
        payload.optJSONObject("items_by_tab")
            ?: payload.optJSONObject("pipeline")
            ?: payload.optJSONObject("tabs_items")

    private fun keepTab(id: String, known: Set<String>): Boolean {
        val clean = id.trim()
        if (clean.isBlank() || clean.equals("null", true)) return false
        return known.isEmpty() || clean in known
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
