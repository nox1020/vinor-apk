package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/** کارت‌های پیگیری. فیلدها همان پاسخ درخواست‌ها هستند. */
object FollowRequests {
    const val DATA_PATH = "/express/partner/api/requests/data"
    const val PAGE_PATH = "/express/partner/requests"
    const val FILTER_ALL = ""
    const val EMPTY = "موردی برای پیگیری نیست."
    const val EMPTY_FILTER = "موردی در این بخش نیست."
    const val EMPTY_ARCHIVED = "پیگیری بایگانی‌شده‌ای نیست."
    const val LISTING_STILL_ARCHIVED = "خود آگهی هنوز بایگانی یا غیرفعال است."
    const val ARCHIVE_LABEL = "بایگانی"
    const val RESTORE_LABEL = "بازگردوندن به پیگیری"
    const val FAIL = "ثبت ناموفق بود."

    enum class Tone { PRIMARY, SECONDARY, DANGER }

    data class Tab(val id: String, val title: String, val count: Int = 0)

    enum class Mark { NEUTRAL, GOOD, BAD }

    data class Action(
        val label: String,
        val path: String,
        val body: JSONObject,
        val tone: Tone,
        val confirm: String? = null,
        val opensWallet: Boolean = false,
        val json: Boolean = false,
    )

    data class Counts(val requests: Int, val visits: Int, val deals: Int) {
        val all: Int get() = requests + visits + deals
    }

    data class Filter(val id: String, val title: String, val count: Int)

    data class PastVisit(
        val id: String,
        val whenText: String,
        val status: String,
        val updatedAt: String,
        val outcome: String,
        val notes: String,
    )

    data class Card(
        val id: String,
        val title: String,
        val status: String,
        val customer: String,
        val whenText: String,
        val landCode: String,
        val phone: String = "",
        val commission: String,
        val actions: List<Action>,
        val stage: String = "",
        val stageLabel: String = "",
        val direction: String = "",
        val directionLabel: String = "",
        val revisitCount: Int = 0,
        val updatedAt: String = "",
        val history: List<PastVisit> = emptyList(),
        val ownerPhone: String = "",
        val archived: Boolean = false,
    )

    fun tabs(array: JSONArray?): List<Tab> = tabs(array, null)

    fun tabs(array: JSONArray?, payload: JSONObject?): List<Tab> {
        val parsed = readTabs(array)
        val grouped = payload?.let { groupedOf(it) }
        if (parsed.isNotEmpty()) {
            if (grouped == null) return parsed
            return parsed.map { tab ->
                if (tab.count > 0) tab
                else tab.copy(count = grouped.optJSONArray(tab.id)?.length() ?: 0)
            }
        }
        if (grouped == null) return emptyList()
        val out = ArrayList<Tab>()
        val keys = grouped.keys()
        while (keys.hasNext()) {
            val id = keys.next().trim()
            if (id.isBlank()) continue
            out.add(Tab(id, stageTitle(id), grouped.optJSONArray(id)?.length() ?: 0))
        }
        out.sortBy { stageOrder(it.id) }
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
        var best = ""
        var bestRank = -1
        for (tab in names) {
            if (items(payload, tab).none { idOf(it) == id }) continue
            val rank = stageRank(tab)
            if (rank >= bestRank) {
                best = tab
                bestRank = rank
            }
        }
        return best
    }

    fun refreshesWallet(path: String): Boolean = path.substringBefore('?').endsWith("/deal-close")

    fun walletRefreshPaths(): List<String> = listOf(
        "/express/partner/commissions/data",
        WalletWithdraw.HOME_DATA,
    )

    /** لیست واحد. `cards` اگر باشد؛ وگرنه ادغام items و inbox_items و offers. */
    fun feed(payload: JSONObject, filter: String = FILTER_ALL): List<JSONObject> {
        val all = source(payload)
        val want = normalizeStage(filter)
        if (want.isBlank() || want == "all") return all
        return all.filter { pipelineBucket(it) == want }
    }

    fun items(payload: JSONObject, tab: String): List<JSONObject> {
        val grouped = groupedOf(payload)?.optJSONArray(tab)
        if (grouped != null) return arrayOf(grouped)
        if (tab.isNotBlank()) {
            val named = payload.optJSONArray(tab)
            if (named != null) return arrayOf(named)
        }
        return feed(payload, tab)
    }

    fun counters(payload: JSONObject): Counts {
        val given = payload.optJSONObject("tab_counts")
        if (given != null) {
            return Counts(
                requests = countKey(given, "requests"),
                visits = countKey(given, "visits"),
                deals = countKey(given, "deals"),
            )
        }
        val all = source(payload)
        return Counts(
            requests = all.count { pipelineBucket(it) == "requests" },
            visits = all.count { pipelineBucket(it) == "visits" },
            deals = all.count { pipelineBucket(it) == "deals" },
        )
    }

    fun filters(counts: Counts): List<Filter> = listOf(
        Filter(FILTER_ALL, "همه", counts.all),
        Filter("requests", "درخواست بازدید", counts.requests),
        Filter("visits", "بازدید", counts.visits),
        Filter("deals", "معامله", counts.deals),
    )

    fun card(item: JSONObject, tab: String = ""): Card {
        val id = idOf(item)
        val title = firstText(item, "title", "land_title", "property_title").ifBlank { "پیگیری" }
        val stage = cardStage(item)
        return Card(
            id = id,
            title = title,
            status = displayStatus(item, tab),
            customer = firstText(item, "customer_name", "customer", "client_name", "buyer_name"),
            whenText = firstText(item, "visit_when", "when_label", "visit_label", "scheduled_label"),
            landCode = firstText(item, "land_code", "property_code", "file_code"),
            phone = firstText(item, "customer_phone", "phone", "mobile", "tel"),
            commission = commissionLine(item),
            actions = actions(item, id, tab),
            stage = stage,
            stageLabel = stageLabel(item, stage),
            direction = directionOf(item),
            directionLabel = directionLabel(item),
            revisitCount = intOf(item, "revisit_count"),
            updatedAt = ListingWeek.formatWhen(firstText(item, "updated_at")),
            history = pastVisits(item),
            ownerPhone = firstText(item, "owner_phone"),
            archived = ListingArchive.isArchived(item),
        )
    }

    fun dataPath(status: String = ListingArchive.ACTIVE): String =
        "$DATA_PATH?status=${ListingArchive.normalize(status)}"

    fun cachePaths(): List<String> = listOf(
        DATA_PATH,
        dataPath(ListingArchive.ACTIVE),
        dataPath(ListingArchive.ARCHIVED),
    )

    fun cardPath(id: String, restore: Boolean): String {
        val action = if (restore) "restore" else "archive"
        return "/express/partner/api/requests/${id.trim()}/$action"
    }

    fun archiveCounts(payload: JSONObject): ListingArchive.Counts {
        val obj = payload.optJSONObject("archive_counts") ?: JSONObject()
        return ListingArchive.Counts(obj.optInt("active"), obj.optInt("archived"))
    }

    fun emptyText(status: String, filter: String): String {
        if (filter.isNotBlank() && filter != FILTER_ALL) return EMPTY_FILTER
        return if (ListingArchive.normalize(status) == ListingArchive.ARCHIVED) EMPTY_ARCHIVED else EMPTY
    }

    fun listingStillArchived(payload: JSONObject): Boolean = payload.optBoolean("listing_archived")

    fun doneMessage(path: String, payload: JSONObject, body: JSONObject = JSONObject()): String {
        val note = payload.optString("message").trim()
        val base = if (note.isNotEmpty() && !note.equals("null", true)) note else fallbackDone(path, body)
        if (!listingStillArchived(payload)) return base
        return if (base.isBlank()) LISTING_STILL_ARCHIVED else "$base\n$LISTING_STILL_ARCHIVED"
    }

    private fun fallbackDone(path: String, body: JSONObject): String {
        val bare = path.substringBefore('?')
        val outcome = body.optString("outcome").trim()
        return when {
            bare.endsWith("/deal-close") -> "معامله انجام شد. پورسانت به کیف پول واریز می‌شود."
            bare.endsWith("/deal-confirm") -> "تأیید نهایی ثبت شد."
            bare.endsWith("/deal-reject") -> "معامله رد شد."
            bare.endsWith("/remove") -> "از پیگیری حذف شد."
            bare.endsWith("/no-show") -> "عدم حضور ثبت شد."
            bare.endsWith("/revisit") -> "بازدید مجدد ثبت شد."
            bare.endsWith("/complete") -> "بازدید انجام شد."
            bare.endsWith("/visit-result") && outcome == "interested" -> "علاقه‌مندی ثبت شد."
            bare.endsWith("/visit-result") && outcome == "follow_up" -> "پیگیری ثبت شد."
            bare.endsWith("/visit-result") && outcome == "not_interested" -> "بی‌علاقگی ثبت شد."
            bare.endsWith("/archive") -> "پیگیری بایگانی شد."
            bare.endsWith("/restore") -> "پیگیری به فهرست فعال برگشت."
            else -> "ثبت شد."
        }
    }

    fun statusMark(status: String): Mark {
        val kind = WalletWithdraw.commissionKind(status)
        if (kind == "rejected") return Mark.BAD
        if (kind == "paid" || kind == "success") return Mark.GOOD
        if (
            status.contains("رد") ||
            status.contains("بی‌علاق") ||
            status.contains("عدم حضور") ||
            status.contains("ناموفق")
        ) {
            return Mark.BAD
        }
        if (status.contains("موفق") || status.contains("واریز") || status.contains("علاقه")) return Mark.GOOD
        return Mark.NEUTRAL
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
        if (depositAwaiting(item, tab)) {
            all.add(
                Action(
                    label = "واریز پورسانت",
                    path = "$base/deal-close",
                    body = JSONObject(),
                    tone = Tone.PRIMARY,
                    confirm = "پورسانت این پرونده به کیف پول واریز شود؟",
                ),
            )
        } else if (depositReady(item, tab)) {
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
        val ranked = all.map { action ->
            if (action.tone != Tone.PRIMARY) action
            else if (!primarySeen) {
                primarySeen = true
                action
            } else {
                action.copy(tone = Tone.SECONDARY)
            }
        }
        val archived = ListingArchive.isArchived(item)
        return ranked + Action(
            label = if (archived) RESTORE_LABEL else ARCHIVE_LABEL,
            path = cardPath(id, archived),
            body = JSONObject(),
            tone = Tone.SECONDARY,
            json = true,
        )
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
        val amount = commissionAmount(item)
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
        if (kind == "rejected" || kind == "pending" || kind == "approved") return false
        if (commissionAmount(item) > 0L) return false
        if (!depositReady(item, tab)) return false
        val stage = normalizeStage(stageOf(item).ifBlank { tab })
        return stage == "done" || stage == "deals"
    }

    /** پورسانت هنوز به کیف پول نرسیده و دکمه باید همان ثبت معامله را بزند. */
    private fun depositAwaiting(item: JSONObject, tab: String): Boolean {
        if (!depositReady(item, tab) || commissionCredited(item)) return false
        val kind = WalletWithdraw.commissionKind(commissionKey(item))
        return commissionAmount(item) > 0L || kind == "pending" || kind == "approved"
    }

    private fun commissionCredited(item: JSONObject): Boolean {
        val kind = WalletWithdraw.commissionKind(commissionKey(item))
        return kind == "paid" || kind == "success" ||
            flag(item, "can_open_commission") ||
            flag(item, "commission_credited")
    }

    private fun depositReady(item: JSONObject, tab: String): Boolean {
        val kind = WalletWithdraw.commissionKind(commissionKey(item))
        if (kind == "rejected") return false
        if (commissionCredited(item)) return true
        if (flag(item, "can_confirm_deal") || flag(item, "can_close_deal")) return false
        val stage = normalizeStage(stageOf(item).ifBlank { tab })
        if (stage != "deals" && stage != "done") return false
        val awaiting = commissionAmount(item) > 0L || kind == "pending" || kind == "approved"
        if (flag(item, "can_register_result") || flag(item, "can_reject_deal")) return awaiting
        return true
    }

    private fun commissionAmount(item: JSONObject): Long =
        WalletWithdraw.money(item, "commission_amount", "commission", "payout_amount")

    private fun readTabs(array: JSONArray?): List<Tab> {
        if (array == null) return emptyList()
        val out = ArrayList<Tab>()
        for (i in 0 until array.length()) {
            when (val raw = array.opt(i)) {
                is String -> {
                    val id = raw.trim()
                    if (id.isNotBlank() && !id.equals("null", true)) out.add(Tab(id, stageTitle(id)))
                }
                is JSONObject -> {
                    val id = firstText(raw, "id", "key", "slug", "code")
                    if (id.isBlank()) continue
                    val title = firstText(raw, "title", "label", "name").ifBlank { stageTitle(id) }
                    out.add(Tab(id, title, countOf(raw)))
                }
            }
        }
        return out
    }

    private fun source(payload: JSONObject): List<JSONObject> {
        if (payload.has("cards") && !payload.isNull("cards")) {
            val cards = payload.optJSONArray("cards")
            if (cards != null) return order(dedupe(arrayOf(cards)))
        }
        val merged = ArrayList<JSONObject>()
        merged.addAll(stamped(payload.optJSONArray("inbox_items"), "incoming", ""))
        merged.addAll(stamped(payload.optJSONArray("items"), "outgoing", ""))
        merged.addAll(stamped(payload.optJSONArray("offers"), "incoming", "deals"))
        if (merged.isEmpty()) {
            val grouped = groupedOf(payload)
            if (grouped != null) {
                val keys = grouped.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    merged.addAll(stamped(grouped.optJSONArray(key), "", key))
                }
            }
        }
        return order(dedupe(merged))
    }

    private fun stamped(array: JSONArray?, direction: String, pipeline: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        if (array == null) return out
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val copy = JSONObject(item.toString())
            if (direction.isNotBlank() && text(copy, "direction").isBlank()) copy.put("direction", direction)
            val hasStage = text(copy, "stage").isNotBlank() || text(copy, "pipeline_tab").isNotBlank()
            if (pipeline.isNotBlank() && !hasStage) copy.put("pipeline_tab", pipeline)
            out.add(copy)
        }
        return out
    }

    private fun dedupe(rows: List<JSONObject>): List<JSONObject> {
        val seen = HashSet<String>()
        val out = ArrayList<JSONObject>()
        for (row in rows) {
            val id = idOf(row)
            if (id.isNotBlank() && !seen.add(id)) continue
            out.add(row)
        }
        return out
    }

    private fun order(rows: List<JSONObject>): List<JSONObject> =
        rows.sortedWith(compareByDescending { firstText(it, "updated_at", "created_at") })

    private fun arrayOf(array: JSONArray): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(item)
        }
        return out
    }

    private fun pipelineBucket(item: JSONObject): String = when (cardStage(item)) {
        "visit" -> "visits"
        "deal" -> "deals"
        else -> "requests"
    }

    private fun cardStage(item: JSONObject): String {
        val explicit = knownStage(text(item, "stage"))
        if (explicit.isNotBlank()) return explicit
        val pipe = normalizeStage(firstText(item, "pipeline_tab", "bucket"))
        when (pipe) {
            "requests", "visit_request" -> return "visit_request"
            "visits" -> return "visit"
            "deals", "done" -> return "deal"
        }
        val fromStatus = stageFromStatus(item)
        if (fromStatus.isNotBlank()) return fromStatus
        return "visit_request"
    }

    private fun stageFromStatus(item: JSONObject): String {
        val status = text(item, "status").lowercase()
        if (status == "pending") return "visit_request"
        if (
            flag(item, "can_confirm_deal") ||
            flag(item, "can_close_deal") ||
            flag(item, "can_start_deal") ||
            flag(item, "can_approve_deal") ||
            text(item, "deal_decision").lowercase() in setOf("approved", "closed") ||
            text(item, "tx_status").lowercase() in setOf("in_transaction", "sold") ||
            text(item, "assignment_status").lowercase() in setOf("in_transaction", "sold")
        ) {
            return "deal"
        }
        if (status.isNotBlank()) return "visit"
        return ""
    }

    private fun knownStage(raw: String): String = when (raw.trim()) {
        "visit_request", "request", "requests", "inbox", "درخواست", "درخواست‌ها", "درخواست بازدید" ->
            "visit_request"
        "visit", "visits", "بازدید", "بازدیدها" -> "visit"
        "deal", "deals", "معامله", "معاملات", "done", "success", "closed", "sold", "completed", "موفق", "موفق‌ها" ->
            "deal"
        else -> ""
    }

    private fun stageLabel(item: JSONObject, stage: String): String {
        val server = text(item, "stage_label")
        if (server.isNotBlank()) return server
        return when (stage) {
            "visit" -> "بازدید"
            "deal" -> "معامله"
            else -> "درخواست بازدید"
        }
    }

    private fun directionOf(item: JSONObject): String {
        val raw = text(item, "direction").lowercase()
        return if (raw == "incoming") "incoming" else "outgoing"
    }

    private fun directionLabel(item: JSONObject): String {
        val server = text(item, "direction_label")
        if (server.isNotBlank()) return server
        return if (directionOf(item) == "incoming") "دریافتی" else "ارسالی"
    }

    private fun pastVisits(item: JSONObject): List<PastVisit> {
        val array = item.optJSONArray("history") ?: return emptyList()
        val out = ArrayList<PastVisit>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val date = ListingWeek.formatWhen(firstText(row, "visit_date"))
            val time = ListingWeek.persianDigits(text(row, "visit_time"))
            val whenText = listOf(date, time).filter { it.isNotBlank() }.joinToString(" · ")
            out.add(
                PastVisit(
                    id = idOf(row),
                    whenText = whenText,
                    status = historyStatus(text(row, "status")),
                    updatedAt = ListingWeek.formatWhen(firstText(row, "updated_at")),
                    outcome = historyOutcome(text(row, "visit_outcome")),
                    notes = text(row, "notes"),
                ),
            )
        }
        return out
    }

    private fun historyStatus(raw: String): String = when (raw.trim().lowercase()) {
        "pending" -> "منتظر تأیید"
        "approved" -> "منتظر بازدید"
        "done_pending" -> "منتظر تأیید انجام"
        "completed" -> "بازدید انجام شد"
        "cancelled" -> "رد شده"
        "no_show" -> "عدم حضور"
        else -> raw.trim()
    }

    private fun historyOutcome(raw: String): String = when (raw.trim().lowercase()) {
        "interested" -> "علاقه‌مند"
        "follow_up" -> "پیگیری دوباره"
        "not_interested" -> "بی‌علاقه"
        else -> raw.trim()
    }

    private fun stageOf(item: JSONObject): String =
        firstText(item, "pipeline_tab", "stage", "bucket")

    private fun normalizeStage(raw: String): String = when (raw.trim()) {
        "request", "requests", "inbox", "visit_request", "درخواست", "درخواست‌ها", "درخواست بازدید" -> "requests"
        "visit", "visits", "بازدید", "بازدیدها" -> "visits"
        "deal", "deals", "معامله", "معاملات" -> "deals"
        "done", "success", "closed", "sold", "completed", "موفق", "موفق‌ها" -> "done"
        else -> raw.trim()
    }

    private fun stageRank(tab: String): Int = when (normalizeStage(tab)) {
        "requests" -> 1
        "visits" -> 2
        "deals" -> 3
        "done" -> 4
        else -> 0
    }

    private fun stageOrder(tab: String): Int = stageRank(tab).let { if (it == 0) 50 else it }

    private fun stageTitle(id: String): String = when (normalizeStage(id)) {
        "requests" -> "درخواست"
        "visits" -> "بازدید"
        "deals" -> "معامله"
        "done" -> "موفق"
        else -> id
    }

    private fun countOf(obj: JSONObject): Int =
        WalletWithdraw.money(obj, "count", "total", "badge", "items_count")
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()

    private fun countKey(obj: JSONObject, key: String): Int {
        if (!obj.has(key) || obj.isNull(key)) return 0
        val parsed = when (val raw = obj.opt(key)) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull() ?: return 0
            else -> return 0
        }
        return parsed.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun intOf(obj: JSONObject, key: String): Int = countKey(obj, key)

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
            is String -> {
                val text = value.trim().lowercase()
                text == "true" || text == "1" || text == "yes" || text == "y" || text == "on" || text == "بله"
            }
            else -> false
        }
    }

    private fun idOf(obj: JSONObject): String {
        for (key in listOf("id", "request_id", "follow_id")) {
            val id = rawId(obj, key)
            if (id.isNotBlank()) return id
        }
        return ""
    }

    private fun rawId(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val raw = obj.opt(key) ?: return ""
        val text = when (raw) {
            is Number -> {
                val whole = raw.toLong()
                if (raw.toDouble() == whole.toDouble()) whole.toString() else raw.toString()
            }
            else -> raw.toString()
        }.trim()
        return if (text.isBlank() || text.equals("null", true)) "" else text
    }

    private fun firstText(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = text(obj, key)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun text(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val value = obj.optString(key).trim()
        return if (value.isBlank() || value.equals("null", true)) "" else value
    }
}
