package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/** کارت‌های پیگیری. فیلدها همان پاسخ درخواست‌ها هستند. */
object FollowRequests {
    const val DATA_PATH = "/express/partner/api/requests/data"
    const val PAGE_PATH = "/express/partner/requests"
    const val FILTER_ALL = ""
    const val ARCHIVE_CHIP = "archive"
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
        val form: Boolean = false,
        val counter: Boolean = false,
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
        val kind: String = "",
        val amountText: String = "",
        val terms: String = "",
        val showLand: Boolean = false,
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

    fun refreshesWallet(path: String): Boolean {
        val bare = path.substringBefore('?')
        return bare.endsWith("/deal-close") ||
            bare.endsWith("/approve_deal") ||
            bare.endsWith("/close_deal")
    }

    fun walletRefreshPaths(): List<String> = listOf(
        "/express/partner/commissions/data",
        WalletWithdraw.HOME_DATA,
    )

    /** لیست واحد. `cards` اگر باشد؛ وگرنه ادغام items و inbox_items و offers. */
    fun feed(
        payload: JSONObject,
        filter: String = FILTER_ALL,
        viewerPhone: String = "",
    ): List<JSONObject> {
        val all = source(payload, viewerPhone)
        val want = normalizeStage(filter)
        if (want.isBlank() || want == "all") return all
        return all.filter { pipelineBucket(it) == want }
    }

    fun items(payload: JSONObject, tab: String, viewerPhone: String = ""): List<JSONObject> {
        val grouped = groupedOf(payload)?.optJSONArray(tab)
        if (grouped != null) return arrayOf(grouped)
        if (tab.isNotBlank()) {
            val named = payload.optJSONArray(tab)
            if (named != null) return arrayOf(named)
        }
        return feed(payload, tab, viewerPhone)
    }

    /** شمارنده همان ردیف‌های روی صفحه است؛ پیشنهاد داخل cards یک‌بار در معامله می‌آید. */
    fun counters(payload: JSONObject, viewerPhone: String = ""): Counts {
        val all = source(payload, viewerPhone)
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

    /** یک ردیف پیگیری: مرحله‌ها به‌اضافهٔ بایگانی. «فعال» در این ردیف نیست. */
    fun requestChips(counts: Counts, archivedCount: Int): List<Filter> =
        filters(counts) + Filter(ARCHIVE_CHIP, "بایگانی", archivedCount.coerceAtLeast(0))

    /**
     * بایگانی نما را عوض می‌کند. «همه» و مرحله‌ها همیشه به آیتم‌های فعال برمی‌گردند.
     * فیلتر مرحله هنگام ورود به بایگانی حفظ می‌شود تا برگشت همان تب بماند.
     */
    fun afterChip(filter: String, archive: String, chipId: String): Pair<String, String> {
        val status = ListingArchive.normalize(archive)
        if (chipId == ARCHIVE_CHIP) {
            val next = if (status == ListingArchive.ARCHIVED) ListingArchive.ACTIVE else ListingArchive.ARCHIVED
            return filter to next
        }
        return chipId to ListingArchive.ACTIVE
    }

    fun chipSelected(filter: String, archive: String, chipId: String): Boolean {
        val archived = ListingArchive.normalize(archive) == ListingArchive.ARCHIVED
        return if (archived) chipId == ARCHIVE_CHIP else chipId == filter
    }

    /** در نمای بایگانی همهٔ ردیف‌های بایگانی دیده می‌شوند، نه فقط مرحلهٔ قبلی. */
    fun listFilter(archive: String, filter: String): String =
        if (ListingArchive.normalize(archive) == ListingArchive.ARCHIVED) FILTER_ALL else filter

    fun card(item: JSONObject, tab: String = ""): Card {
        val id = idOf(item)
        val offer = isOffer(item)
        val title = firstText(item, "title", "land_title", "property_title").ifBlank {
            if (offer) "پیشنهاد قیمت" else "پیگیری"
        }
        val stage = cardStage(item)
        return Card(
            id = id,
            title = title,
            status = displayStatus(item, tab),
            customer = if (offer) "" else firstText(item, "customer_name", "customer", "client_name", "buyer_name"),
            whenText = if (offer) "" else firstText(item, "visit_when", "when_label", "visit_label", "scheduled_label"),
            landCode = firstText(item, "land_code", "property_code", "file_code"),
            phone = if (offer) offerPhone(item) else firstText(item, "customer_phone", "phone", "mobile", "tel"),
            commission = if (offer) "" else commissionLine(item),
            actions = actions(item, id, tab),
            stage = stage,
            stageLabel = stageLabel(item, stage),
            direction = if (offer) "" else directionOf(item),
            directionLabel = if (offer) "" else directionLabel(item),
            revisitCount = if (offer) 0 else intOf(item, "revisit_count"),
            updatedAt = if (offer) "" else ListingWeek.formatWhen(firstText(item, "updated_at")),
            history = if (offer) emptyList() else pastVisits(item),
            ownerPhone = if (offer) "" else firstText(item, "owner_phone"),
            archived = if (offer) false else ListingArchive.isArchived(item),
            kind = if (offer) "offer" else "",
            amountText = if (offer) offerAmountText(item) else "",
            terms = if (offer) text(item, "terms") else "",
            showLand = showsLand(item),
        )
    }

    fun dataPath(status: String = ListingArchive.ACTIVE): String =
        "$DATA_PATH?status=${ListingArchive.normalize(status)}&offers_in_cards=1"

    fun cachePaths(): List<String> = listOf(
        DATA_PATH,
        "$DATA_PATH?status=${ListingArchive.ACTIVE}",
        "$DATA_PATH?status=${ListingArchive.ARCHIVED}",
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
            bare.endsWith("/deal-close") || bare.endsWith("/close_deal") ->
                "معامله انجام شد. پورسانت به کیف پول واریز می‌شود."
            bare.endsWith("/deal-confirm") || bare.endsWith("/confirm_deal") -> "تأیید نهایی ثبت شد."
            bare.endsWith("/deal-reject") || bare.endsWith("/decline_deal") || bare.endsWith("/reject_deal") ->
                "معامله رد شد."
            bare.endsWith("/remove") -> "از پیگیری حذف شد."
            bare.endsWith("/no-show") || bare.endsWith("/no_show") -> "عدم حضور ثبت شد."
            bare.endsWith("/revisit") -> "بازدید مجدد ثبت شد."
            bare.endsWith("/complete") -> "بازدید انجام شد."
            bare.endsWith("/approve_deal") -> "فروش تأیید شد."
            bare.endsWith("/start_deal") -> "معامله ثبت شد."
            bare.endsWith("/reject_done") -> "انجام بازدید رد شد."
            bare.endsWith("/approve") -> "بازدید تأیید شد."
            bare.endsWith("/reject") -> "بازدید رد شد."
            (bare.endsWith("/visit-result") || bare.endsWith("/result")) && outcome == "interested" ->
                "علاقه‌مندی ثبت شد."
            (bare.endsWith("/visit-result") || bare.endsWith("/result")) && outcome == "follow_up" ->
                "پیگیری ثبت شد."
            (bare.endsWith("/visit-result") || bare.endsWith("/result")) && outcome == "not_interested" ->
                "بی‌علاقگی ثبت شد."
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

    fun formFields(action: Action): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val keys = action.body.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "csrf_token") continue
            out[key] = action.body.optString(key)
        }
        return out
    }

    /**
     * نتیجهٔ POST فرم صندوق. ریدایرکت به صفحهٔ پیگیری موفقیت است،
     * مگر فلش هشدار داخل HTML باشد.
     */
    fun inboxResponse(
        status: Int,
        body: String,
        finalPath: String,
        redirected: Boolean,
    ): JSONObject {
        val text = body.trim()
        if (text.startsWith("{")) {
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (parsed != null) {
                if (!parsed.has("http_status")) parsed.put("http_status", status)
                return parsed
            }
        }
        val path = finalPath.substringBefore('?')
        if (path.startsWith("/express/partner/login")) {
            return JSONObject()
                .put("ok", false)
                .put("success", false)
                .put("error", "unauthorized")
                .put("http_status", 401)
        }
        val flashes = htmlFlashes(text)
        val warnings = flashes.filter { it.first }
        if (warnings.isNotEmpty()) {
            val message = warnings.joinToString("\n") { it.second }
            return JSONObject()
                .put("ok", false)
                .put("http_status", 400)
                .put("error", message)
                .put("message", message)
        }
        val notes = flashes.filter { !it.first }
        if (notes.isNotEmpty()) {
            return JSONObject()
                .put("ok", true)
                .put("http_status", 200)
                .put("message", notes.joinToString("\n") { it.second })
        }
        val follow = path.contains("/requests") ||
            text.contains("id=\"partnerRequestsPage\"") ||
            text.contains("id=\"rqFeed\"")
        if (redirected && status in 200..299 && follow) {
            return JSONObject().put("ok", true).put("http_status", 200).put("message", "")
        }
        return JSONObject()
            .put("ok", false)
            .put("http_status", if (status in 400..599) status else 400)
            .put("error", "پاسخ نامعتبر")
    }

    fun actions(item: JSONObject, id: String, tab: String = ""): List<Action> {
        if (id.isBlank()) return emptyList()
        if (isOffer(item)) return offerActions(item, id)
        val built = if (postsToInbox(item)) ownerInboxActions(item, id) else requesterActions(item, id, tab)
        val ranked = rankActions(built)
        return if (showsArchive(item)) ranked + archiveAction(item, id) else ranked
    }

    /** مبلغ و شرایط اختیاری پیشنهاد متقابل. مبلغ خالی یا صفر پست نمی‌شود. */
    fun withCounter(action: Action, amountRaw: String, termsRaw: String): Action? {
        val amount = PartnerApi.asciiDigits(amountRaw).toLongOrNull() ?: return null
        if (amount <= 0L) return null
        val body = JSONObject()
        val keys = action.body.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "csrf_token" || key == "amount" || key == "terms") continue
            body.put(key, action.body.optString(key))
        }
        if (body.optString("return_to").isBlank()) body.put("return_to", "visits")
        body.put("amount", amount.toString())
        val terms = termsRaw.trim()
        if (terms.isNotEmpty()) body.put("terms", terms)
        return action.copy(body = body)
    }

    private fun offerActions(item: JSONObject, id: String): List<Action> {
        val given = item.optJSONObject("actions")
        val ret = text(item, "return_to").ifBlank { "visits" }
        fun path(name: String): String {
            val raw = given?.optString(name)?.trim().orEmpty()
            if (raw.isNotBlank() && !raw.equals("null", true)) return raw
            return "/express/partner/inbox-offers/${id.trim()}/$name"
        }
        fun body(): JSONObject = JSONObject().put("return_to", ret)
        val all = ArrayList<Action>()
        if (flag(item, "can_accept")) {
            all.add(
                Action(
                    label = "پذیرش",
                    path = path("accept"),
                    body = body(),
                    tone = Tone.PRIMARY,
                    confirm = "این پیشنهاد پذیرفته شود؟",
                    form = true,
                ),
            )
        }
        if (flag(item, "can_reject")) {
            all.add(
                Action(
                    label = "رد",
                    path = path("reject"),
                    body = body(),
                    tone = Tone.DANGER,
                    confirm = "این پیشنهاد رد شود؟",
                    form = true,
                ),
            )
        }
        if (flag(item, "can_counter")) {
            all.add(
                Action(
                    label = "پیشنهاد متقابل",
                    path = path("counter"),
                    body = body(),
                    tone = Tone.SECONDARY,
                    form = true,
                    counter = true,
                ),
            )
        }
        return rankActions(all)
    }

    private fun isOffer(item: JSONObject): Boolean = text(item, "kind") == "offer"

    private fun offerAmountText(item: JSONObject): String {
        val amount = WalletWithdraw.money(item, "amount")
        if (amount <= 0L) return ""
        return "${PartnerFormat.toman(amount)} تومان"
    }

    private fun offerPhone(item: JSONObject): String {
        val partner = text(item, "partner_phone")
        val raw = partner.ifBlank { text(item, "buyer_phone") }
        val shown = isolatedDigits(raw)
        if (shown.isBlank()) return ""
        val label = if (partner.isNotBlank()) "همکار" else "خریدار"
        return "$label: $shown"
    }

    private fun isolatedDigits(raw: String): String {
        val shown = ListingWeek.persianDigits(raw.trim())
        if (shown.isBlank()) return ""
        return "\u2066$shown\u2069"
    }

    private fun requesterActions(item: JSONObject, id: String, tab: String): ArrayList<Action> {
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
        return all
    }

    private fun ownerInboxActions(item: JSONObject, id: String): ArrayList<Action> {
        val all = ArrayList<Action>()
        fun add(
            label: String,
            action: String,
            tone: Tone,
            confirm: String? = null,
            body: JSONObject = JSONObject(),
        ) {
            all.add(
                Action(
                    label = label,
                    path = inboxPath(id, action),
                    body = body,
                    tone = tone,
                    confirm = confirm,
                    form = true,
                ),
            )
        }
        if (flag(item, "can_register_result")) {
            add("علاقه‌مند", "result", Tone.PRIMARY, body = resultBody("interested"))
        }
        if (flag(item, "can_approve")) add("تأیید بازدید", "approve", Tone.PRIMARY)
        if (flag(item, "can_complete")) add("انجام شد", "complete", Tone.PRIMARY)
        val startDeal = flag(item, "can_start_deal")
        val approveDeal = flag(item, "can_approve_deal")
        if (startDeal) {
            add(
                "ثبت معامله",
                "start_deal",
                Tone.PRIMARY,
                "فروش نهایی برای این همکار ثبت شود؟ فایل در حال معامله می‌شود.",
            )
        } else if (flag(item, "can_confirm_deal")) {
            add("تأیید نهایی", "confirm_deal", Tone.PRIMARY, "تأیید نهایی این پرونده ثبت شود؟")
        }
        if (approveDeal) {
            add(
                "تأیید فروش",
                "approve_deal",
                Tone.PRIMARY,
                "فروش تأیید و فایل بسته شود؟ پورسانت به کیف پول همکار اضافه می‌شود.",
            )
        } else if (flag(item, "can_close_deal")) {
            add(
                "انجام معامله",
                "close_deal",
                Tone.SECONDARY,
                "انجام معامله ثبت شود؟ پورسانت به کیف پول واریز می‌شود.",
            )
        }
        if (flag(item, "can_no_show")) add("عدم حضور", "no_show", Tone.SECONDARY, "عدم حضور مشتری ثبت شود؟")
        if (flag(item, "can_register_result")) {
            add("پیگیری", "result", Tone.SECONDARY, body = resultBody("follow_up"))
            add("بی‌علاقه", "result", Tone.SECONDARY, body = resultBody("not_interested"))
        }
        if (flag(item, "can_reject")) add("رد بازدید", "reject", Tone.DANGER, "این بازدید رد شود؟")
        if (flag(item, "can_reject_done")) add("رد انجام", "reject_done", Tone.DANGER, "انجام بازدید رد شود؟")
        if (flag(item, "can_reject_deal")) {
            if (approveDeal) add("رد معامله", "reject_deal", Tone.DANGER, "این معامله رد شود؟")
            else add("رد معامله", "decline_deal", Tone.DANGER, "این معامله رد شود؟")
        }
        return all
    }

    private fun resultBody(outcome: String): JSONObject =
        JSONObject().put("outcome", outcome).put("notes", "")

    private fun inboxPath(id: String, action: String): String =
        "/express/partner/inbox-visits/${id.trim()}/$action"

    private fun postsToInbox(item: JSONObject): Boolean =
        directionOf(item) == "incoming" && ownRequest(item) != true

    private fun rankActions(all: List<Action>): List<Action> {
        val ranked = ArrayList(all)
        if (ranked.none { it.tone == Tone.PRIMARY }) {
            val index = ranked.indexOfFirst { it.tone == Tone.SECONDARY }
            if (index >= 0) ranked[index] = ranked[index].copy(tone = Tone.PRIMARY)
        }
        var primarySeen = false
        return ranked.map { action ->
            if (action.tone != Tone.PRIMARY) action
            else if (!primarySeen) {
                primarySeen = true
                action
            } else {
                action.copy(tone = Tone.SECONDARY)
            }
        }
    }

    /** پرچم غایب یعنی رفتار قبلی: دکمهٔ بایگانی هست. false آن را برمی‌دارد. */
    private fun showsArchive(item: JSONObject): Boolean {
        if (isOffer(item) || idBlank(item)) return false
        if (!item.has("can_archive") || item.isNull("can_archive")) return true
        return flag(item, "can_archive")
    }

    /** پرچم غایب یعنی رفتار قبلی: با داشتن کد ملک، «نمایش ملک» هست. */
    private fun showsLand(item: JSONObject): Boolean {
        if (isOffer(item)) return false
        if (firstText(item, "land_code", "property_code", "file_code").isBlank()) return false
        if (!item.has("can_view_land") || item.isNull("can_view_land")) return true
        return flag(item, "can_view_land")
    }

    private fun idBlank(item: JSONObject): Boolean = idOf(item).isBlank()

    private fun archiveAction(item: JSONObject, id: String): Action {
        val archived = ListingArchive.isArchived(item)
        return Action(
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

    /** دریافتی فقط برای مالک همان آگهی. ارسالی مال کسی است که بازدید را ثبت کرده. */
    fun visibleToViewer(item: JSONObject, viewerPhone: String): Boolean {
        if (isOffer(item) || text(item, "audience") == "offer") return true
        if (directionOf(item) != "incoming") return true
        return VisitAudience.samePhone(firstText(item, "owner_phone"), viewerPhone)
    }

    private fun source(payload: JSONObject, viewerPhone: String): List<JSONObject> {
        if (payload.has("cards") && !payload.isNull("cards")) {
            val cards = payload.optJSONArray("cards")
            if (cards != null) {
                return order(dedupe(arrayOf(cards))).filter { visibleToViewer(it, viewerPhone) }
            }
        }
        val merged = ArrayList<JSONObject>()
        merged.addAll(stamped(payload.optJSONArray("inbox_items"), "incoming", ""))
        merged.addAll(stamped(payload.optJSONArray("items"), "outgoing", ""))
        merged.addAll(stamped(payload.optJSONArray("offers"), "incoming", "deals").onEach { it.put("audience", "offer") })
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
        return order(dedupe(merged)).filter { visibleToViewer(it, viewerPhone) }
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
            val key = if (id.isBlank()) "" else if (isOffer(row)) "offer:$id" else id
            if (key.isNotBlank() && !seen.add(key)) continue
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
        if (isOffer(item)) return "deal"
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
        if (raw == "incoming" || raw == "outgoing") return raw
        when (text(item, "direction_label")) {
            "دریافتی" -> return "incoming"
            "ارسالی" -> return "outgoing"
        }
        return when (ownRequest(item)) {
            true -> "outgoing"
            false -> "incoming"
            null -> ""
        }
    }

    private fun ownRequest(item: JSONObject): Boolean? {
        if (!item.has("is_own_request") || item.isNull("is_own_request")) return null
        return flag(item, "is_own_request")
    }

    private fun directionLabel(item: JSONObject): String {
        val server = text(item, "direction_label")
        if (server.isNotBlank()) return server
        return when (directionOf(item)) {
            "incoming" -> "دریافتی"
            "outgoing" -> "ارسالی"
            else -> ""
        }
    }

    private fun htmlFlashes(html: String): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        val blocks = Regex(
            """<div\b([^>]*\bclass\s*=\s*["'][^"']*(?:border-amber|border-emerald)[^"']*["'][^>]*)>(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val classAttr = Regex("""class\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        for (match in blocks.findAll(html)) {
            val cls = classAttr.find(match.groupValues[1])?.groupValues?.get(1).orEmpty()
            if (!cls.contains("rounded-xl")) continue
            val warning = cls.contains("border-amber")
            val success = cls.contains("border-emerald")
            if (!warning && !success) continue
            val inner = unescapeHtml(match.groupValues[2].replace(Regex("<[^>]+>"), " "))
            if (inner.isEmpty()) continue
            out.add(warning to inner)
        }
        return out
    }

    private fun unescapeHtml(raw: String): String {
        return raw
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
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
