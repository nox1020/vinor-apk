package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * شارژ کیف پول به سبک دیوار (هم‌ارز وب): مرحلهٔ مبلغ، مرحلهٔ تأیید، انتظار درگاه و نتیجه.
 * منطق بدون اندروید تا تست واحد داشته باشد. مبلغ نهایی را سرور ثبت و با زرین‌پال تأیید می‌کند.
 */
object WalletCharge {
    const val OPTIONS_PATH = "/express/partner/api/wallet"
    const val CHARGE_PATH = "/express/partner/wallet/charge"
    const val DEFAULT_MIN = 1_000L
    const val INPUT_DIGITS = 15
    val DEFAULT_PRESETS = listOf(100_000L, 500_000L, 1_000_000L, 5_000_000L)

    val DEFAULT_TEXTS: Map<String, String> = mapOf(
        "wallet_title" to "کیف پول",
        "available_label" to "موجودی قابل استفاده",
        "available_hint" to "برای انتشار آگهی، پرداخت پورسانت و برداشت در دسترس است.",
        "blocked_label" to "بلوکه برای پورسانت",
        "blocked_hint" to "پورسانت آگهی‌های منتشرشده کنار گذاشته شده و با فروش یا لغو آگهی تکلیفش روشن می‌شود.",
        "pending_withdrawal_label" to "در انتظار برداشت",
        "pending_withdrawal_hint" to "درخواست‌های برداشتی که هنوز واریز نشده‌اند.",
        "topup_button" to "افزایش اعتبار",
        "withdraw_button" to "برداشت",
        "requests_title" to "درخواست‌های برداشت",
        "amount_title" to "افزایش اعتبار",
        "amount_question" to "چقدر می‌خواهید کیف پولتان را شارژ کنید؟",
        "amount_placeholder" to "مبلغ دلخواه",
        "amount_suffix" to "تومان",
        "amount_min" to "کمترین مبلغ شارژ ۱٬۰۰۰ تومان است.",
        "amount_too_low" to "مبلغ باید دست‌کم ۱٬۰۰۰ تومان باشد.",
        "amount_too_high" to "این مبلغ بیش از حد مجاز است.",
        "next" to "ادامه",
        "summary_title" to "تأیید و پرداخت",
        "summary_question" to "اطلاعات پرداخت را بررسی کنید",
        "summary_amount" to "مبلغ شارژ",
        "summary_gateway" to "درگاه پرداخت",
        "summary_after" to "موجودی بعد از شارژ",
        "summary_note" to "مبلغ فقط بعد از تأیید پرداخت از طرف بانک به کیف پولتان اضافه می‌شود.",
        "pay" to "پرداخت",
        "paying" to "در حال انتقال به درگاه…",
        "success_title" to "کیف پولتان شارژ شد",
        "success_balance" to "موجودی قابل استفاده",
        "success_ref" to "کد پیگیری",
        "retry" to "تلاش دوباره",
        "recheck" to "بررسی دوباره",
        "transactions_title" to "تراکنش‌ها",
        "transactions_empty" to "هنوز تراکنشی ندارید.",
        "waiting_title" to "در انتظار نتیجهٔ پرداخت",
        "waiting_body" to "پرداخت را در مرورگر تمام کنید و به وینور برگردید. تا بانک تأیید نکند مبلغی به کیف پول اضافه نمی‌شود.",
        "check_result" to "بررسی نتیجه",
        "pending_title" to "نتیجهٔ پرداخت هنوز مشخص نیست",
        "pending_body" to "هنوز تأیید بانک به ما نرسیده و تا وقتی تأیید نشود مبلغی به کیف پول اضافه نمی‌شود.",
        "pending_note" to "اگر پولی از حسابتان کم شده، یا چند دقیقهٔ دیگر تأیید می‌شود یا بانک حداکثر تا ۷۲ ساعت برمی‌گرداند.",
        "failed_title" to "پرداخت انجام نشد",
        "failed_body" to "بانک پرداخت را تأیید نکرد و مبلغی به کیف پول اضافه نشد.",
        "refund_note" to "اگر پولی از حسابتان کم شده، بانک حداکثر تا ۷۲ ساعت آن را برمی‌گرداند.",
        "cancelled_title" to "پرداخت لغو شد",
        "cancelled_body" to "پرداخت را در درگاه لغو کردید و پولی از حسابتان کم نشد.",
        "back_to_wallet" to "بازگشت به کیف پول",
    )

    data class Preset(val amount: Long, val label: String)
    data class Line(val key: String, val label: String, val value: String, val hint: String)
    /** هر مفهوم یک بار: قابل استفاده (بزرگ) + خط‌های بلوکه و در انتظار برداشت. «کل موجودی» نمایش داده نمی‌شود. */
    data class Header(val available: Long, val blocked: Long, val total: Long, val lines: List<Line>, val pendingWithdrawal: Long = 0L)
    /** ورودی «برداشت» در صفحهٔ کیف پول (از بلوک withdraw پاسخ /api/wallet). */
    data class Withdraw(
        val known: Boolean = false,
        val enabled: Boolean = false,
        val canWithdraw: Boolean = false,
        val minAmount: Long = WalletWithdrawWizard.DEFAULT_MIN,
        val requests: List<WalletWithdrawWizard.Request> = emptyList(),
    )
    data class Return(val key: String, val code: String, val label: String)

    data class Options(
        val minAmount: Long,
        val maxAmount: Long,
        val presets: List<Preset>,
        val prefill: Long,
        val reasonLine: String,
        val ctx: String,
        val gatewayLabel: String,
        val header: Header,
        val returnTarget: Return,
        val texts: Map<String, String>,
        val groups: List<Group>,
        val csrf: String,
        val withdraw: Withdraw = Withdraw(),
    ) {
        fun text(key: String): String = texts[key]?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXTS[key].orEmpty()
    }

    data class Item(
        val kind: String,
        val title: String,
        val subtitle: String,
        val amountLabel: String,
        val tone: String,
        val chipLabel: String,
        val chipTone: String,
        val time: String,
    )

    data class Group(val key: String, val label: String, val items: List<Item>)

    data class Result(
        val state: String,
        val title: String,
        val message: String,
        val note: String,
        val amountLabel: String,
        val balanceLabel: String,
        val refId: String,
        val canRecheck: Boolean,
        val returnTarget: Return,
    ) {
        val isFinal: Boolean get() = state == "success" || state == "failed" || state == "cancelled"
        val canRetry: Boolean get() = state == "failed" || state == "cancelled" || state == "not_found"
    }

    fun toman(amount: Long): String = PartnerFormat.toman(amount).replace('،', '٬') + " تومان"

    private val SCALES = listOf("", "هزار", "میلیون", "میلیارد", "هزار میلیارد")

    /** ۲٬۵۰۰٬۰۰۰ → «۲ میلیون و ۵۰۰ هزار تومان» (مثل وب). */
    fun words(amount: Long): String {
        if (amount <= 0L) return ""
        var n = amount
        val parts = mutableListOf<String>()
        var scale = 0
        while (n > 0 && scale < SCALES.size) {
            val chunk = n % 1000
            n /= 1000
            if (chunk > 0) {
                val s = ListingWeek.persianDigits(chunk.toString())
                parts.add(if (SCALES[scale].isNotEmpty()) "$s ${SCALES[scale]}" else s)
            }
            scale++
        }
        if (n > 0) return toman(amount)
        return parts.reversed().joinToString(" و ") + " تومان"
    }

    /** فقط رقم؛ فارسی و عربی به لاتین. صفرهای ابتدای عدد حذف می‌شوند. */
    fun digitsOf(raw: String?): String {
        val out = StringBuilder()
        for (c in raw.orEmpty()) {
            when (c) {
                in '0'..'9' -> out.append(c)
                in '۰'..'۹' -> out.append('0' + (c - '۰'))
                in '٠'..'٩' -> out.append('0' + (c - '٠'))
            }
        }
        return out.toString().trimStart('0').take(INPUT_DIGITS)
    }

    fun grouped(digits: String): String =
        if (digits.isEmpty()) "" else PartnerFormat.toman(digits.toLongOrNull() ?: 0L).replace('،', '٬')

    private fun texts(obj: JSONObject?): Map<String, String> {
        val out = DEFAULT_TEXTS.toMutableMap()
        obj?.keys()?.forEach { k -> obj.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
        return out
    }

    private fun returnOf(obj: JSONObject?): Return = Return(
        key = obj?.optString("key").orEmpty().ifBlank { "wallet" },
        code = obj?.optString("code").orEmpty(),
        label = obj?.optString("label").orEmpty().ifBlank { "بازگشت به کیف پول" },
    )

    fun parseGroups(arr: JSONArray?): List<Group> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Group>()
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val rows = g.optJSONArray("entries") ?: JSONArray()
            val items = mutableListOf<Item>()
            for (j in 0 until rows.length()) {
                val it = rows.optJSONObject(j) ?: continue
                val chip = it.optJSONObject("chip")
                items.add(Item(
                    kind = it.optString("kind"),
                    title = it.optString("title"),
                    subtitle = it.optString("subtitle"),
                    amountLabel = it.optString("amount_label"),
                    tone = it.optString("tone"),
                    chipLabel = chip?.optString("label").orEmpty(),
                    chipTone = chip?.optString("tone").orEmpty(),
                    time = it.optString("time_label"),
                ))
            }
            out.add(Group(g.optString("key"), g.optString("label"), items))
        }
        return out
    }

    /**
     * پاسخ /api/wallet. سرور قدیمی (بدون این مسیر) → پیش‌فرض‌ها و [fallbackAvailable]/[legacyShortfall]،
     * تا شارژ مثل قبل کار کند.
     */
    fun parseOptions(obj: JSONObject?, legacyShortfall: Long = 0L, fallbackAvailable: Long = 0L, fallbackBlocked: Long = 0L): Options {
        val ok = obj != null && obj.optBoolean("ok") && obj.has("min_amount")
        val t = texts(obj?.optJSONObject("texts"))
        val minAmount = if (ok) obj!!.optLong("min_amount", DEFAULT_MIN).coerceAtLeast(1L) else DEFAULT_MIN
        val maxAmount = if (ok) obj!!.optLong("max_amount", 0L).coerceAtLeast(0L) else 0L
        val presets = mutableListOf<Preset>()
        obj?.optJSONArray("presets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val a = p.optLong("amount", 0L)
                if (a > 0L) presets.add(Preset(a, p.optString("label").ifBlank { toman(a) }))
            }
        }
        var prefill = if (ok) obj!!.optLong("prefill", 0L).coerceAtLeast(0L) else 0L
        var reason = if (ok) obj!!.optString("reason_line") else ""
        if (!ok && legacyShortfall > 0L) {
            prefill = legacyShortfall.coerceAtLeast(minAmount)
            reason = "برای ادامه ${toman(legacyShortfall)} کم دارید."
        }
        if (presets.isEmpty()) {
            DEFAULT_PRESETS.forEach { presets.add(Preset(it, toman(it))) }
            if (prefill > 0L && DEFAULT_PRESETS.none { it == prefill }) presets.add(0, Preset(prefill, toman(prefill)))
        }
        val w = obj?.optJSONObject("wallet")
        val available = w?.optLong("available", fallbackAvailable) ?: fallbackAvailable
        val blocked = w?.optLong("blocked", fallbackBlocked) ?: fallbackBlocked
        val total = (w?.optLong("total", available + blocked) ?: (available + blocked)).let { if (it <= 0L) available + blocked else it }
        val pendingWd = w?.optLong("pending_withdrawal", 0L) ?: 0L
        val lines = mutableListOf<Line>()
        w?.optJSONArray("lines")?.let { arr ->
            for (i in 0 until arr.length()) {
                val l = arr.optJSONObject(i) ?: continue
                // سرور قدیمی خط «کل موجودی» هم می‌فرستاد؛ تکرار قابل استفاده + بلوکه است و نشان داده نمی‌شود.
                if (l.optString("key") == "total") continue
                lines.add(Line(l.optString("key"), l.optString("label"), l.optString("value"), l.optString("hint")))
            }
        }
        if (lines.isEmpty()) {
            lines.add(Line("blocked", t.getValue("blocked_label"), toman(blocked), t.getValue("blocked_hint")))
            lines.add(Line("pending_withdrawal", t.getValue("pending_withdrawal_label"), toman(pendingWd), t.getValue("pending_withdrawal_hint")))
        }
        val wd = obj?.optJSONObject("withdraw")
        val withdraw = if (wd == null) Withdraw() else Withdraw(
            known = true,
            enabled = wd.optBoolean("enabled", false),
            canWithdraw = wd.optBoolean("can_withdraw", false),
            minAmount = wd.optLong("min_amount", WalletWithdrawWizard.DEFAULT_MIN),
            requests = WalletWithdrawWizard.parseRequests(wd.optJSONArray("requests")),
        )
        return Options(
            minAmount = minAmount,
            maxAmount = maxAmount,
            presets = presets,
            prefill = prefill,
            reasonLine = reason,
            ctx = obj?.optString("ctx").orEmpty(),
            gatewayLabel = obj?.optJSONObject("gateway")?.optString("label").orEmpty().ifBlank { "زرین‌پال" },
            header = Header(available, blocked, total, lines, pendingWd),
            returnTarget = returnOf(obj?.optJSONObject("return")),
            texts = t,
            groups = parseGroups(obj?.optJSONArray("groups")),
            csrf = obj?.optString("csrf_token").orEmpty(),
            withdraw = withdraw,
        )
    }

    fun parseResult(obj: JSONObject?, texts: Map<String, String> = DEFAULT_TEXTS): Result {
        val o = obj ?: JSONObject()
        val state = o.optString("state").ifBlank { if (o.optInt("http_status", 200) == 404) "not_found" else "pending" }
        fun tx(k: String) = texts[k] ?: DEFAULT_TEXTS[k].orEmpty()
        val title = o.optString("title").ifBlank {
            when (state) {
                "success" -> tx("success_title")
                "failed", "not_found" -> tx("failed_title")
                "cancelled" -> tx("cancelled_title")
                else -> tx("pending_title")
            }
        }
        val message = o.optString("message").ifBlank {
            when (state) {
                "failed", "not_found" -> tx("failed_body")
                "cancelled" -> tx("cancelled_body")
                "pending" -> tx("pending_body")
                else -> ""
            }
        }
        val note = if (o.has("note")) o.optString("note") else when (state) {
            "failed", "not_found" -> tx("refund_note")
            "pending" -> tx("pending_note")
            else -> ""
        }
        return Result(
            state = state,
            title = title,
            message = message,
            note = note,
            amountLabel = o.optString("amount_label"),
            balanceLabel = o.optString("balance_label"),
            refId = o.optString("ref_id"),
            canRecheck = o.optBoolean("can_recheck", false),
            returnTarget = returnOf(o.optJSONObject("return")),
        )
    }

    /** بازگشت به صفحهٔ قبلی برنامه (آگهی یا معامله) یا ماندن در کیف پول. */
    fun returnsToPreviousScreen(target: Return): Boolean = target.key == "listing" || target.key == "deal"
}

/** وضعیت مرحله‌ها. «پرداخت» فقط مبلغ و زمینهٔ امضاشده را می‌فرستد؛ مبلغ را سرور ثبت می‌کند. */
class WalletChargeFlow(var options: WalletCharge.Options, startAtAmount: Boolean = true) {
    enum class Step { HOME, AMOUNT, SUMMARY, WAITING, RESULT }

    var step: Step = if (startAtAmount) Step.AMOUNT else Step.HOME
        private set
    private val entry: Step = step
    var digits: String = if (options.prefill > 0L) options.prefill.toString() else ""
        private set
    var txnId: Long = 0L
    var result: WalletCharge.Result? = null

    fun amount(): Long = digits.toLongOrNull() ?: 0L

    /** پاسخ سرور رسید: اگر کاربر چیزی ننوشته، پیش‌پر سرور (کمبود دقیق) را بگذار. */
    fun applyOptions(next: WalletCharge.Options) {
        options = next
        if (digits.isEmpty() && next.prefill > 0L) digits = next.prefill.toString()
    }

    fun setInput(raw: String?) { digits = WalletCharge.digitsOf(raw) }

    fun pick(amount: Long) { digits = if (amount > 0L) amount.toString() else "" }

    fun selectedPreset(): Long? = options.presets.firstOrNull { it.amount == amount() }?.amount

    fun error(): String {
        val a = amount()
        if (a <= 0L) return ""
        if (a < options.minAmount) return options.text("amount_too_low")
        if (options.maxAmount > 0L && a > options.maxAmount) return options.text("amount_too_high")
        return ""
    }

    fun canContinue(): Boolean = amount() > 0L && error().isEmpty()

    fun words(): String = WalletCharge.words(amount())

    fun inputLabel(): String = WalletCharge.grouped(digits)

    fun progressLabel(): String = when (step) {
        Step.AMOUNT -> "مرحلهٔ ۱ از ۲"
        Step.SUMMARY -> "مرحلهٔ ۲ از ۲"
        else -> ""
    }

    fun openAmount() { step = Step.AMOUNT }

    fun next(): Boolean {
        if (step == Step.AMOUNT && canContinue()) { step = Step.SUMMARY; return true }
        return false
    }

    fun startedPayment(id: Long) { txnId = id; step = Step.WAITING }

    fun showResult(r: WalletCharge.Result) { result = r; step = Step.RESULT }

    fun retry() { result = null; txnId = 0L; step = Step.AMOUNT }

    /** true یعنی در دیالوگ ماندیم؛ false یعنی دیالوگ بسته شود. */
    fun back(): Boolean = when (step) {
        Step.SUMMARY -> { step = Step.AMOUNT; true }
        Step.AMOUNT -> if (entry == Step.HOME) { step = Step.HOME; true } else false
        else -> false
    }

    fun summaryRows(): List<Pair<String, String>> = listOf(
        options.text("summary_amount") to WalletCharge.toman(amount()),
        options.text("summary_gateway") to options.gatewayLabel,
        options.text("summary_after") to WalletCharge.toman(options.header.available + amount()),
    )

    fun body(): JSONObject = JSONObject().apply {
        put("amount", amount())
        if (options.ctx.isNotBlank()) put("ctx", options.ctx)
        else if (options.returnTarget.key == "commissions" || options.returnTarget.key == "wallet") put("return", options.returnTarget.key)
    }
}
