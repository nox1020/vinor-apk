package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * درخواست برداشت به سبک دیوار (هم‌ارز وب /express/partner/wallet/withdraw):
 * مبلغ (چیپ‌ها و «همهٔ موجودی») → مقصد (شبای ذخیره‌شده یا شبای جدید با چک‌سام) → تأیید → ثبت.
 *
 * GET  /express/partner/api/wallet/withdraw  گزینه‌ها + csrf_token
 * POST /express/partner/api/wallet/withdraw  JSON + X-CSRFToken؛ client_request_id ضد دوبار ثبت است.
 *
 * منطق بدون اندروید تا تست واحد داشته باشد. قابل برداشت را سرور حساب می‌کند (بدون بلوکهٔ پورسانت و
 * درخواست‌های باز)؛ «همهٔ موجودی» با all=true فرستاده می‌شود تا سرور عدد لحظهٔ ثبت را بردارد.
 */
object WalletWithdrawWizard {
    const val PATH = "/express/partner/api/wallet/withdraw"
    const val DEFAULT_MIN = 10_000_000L
    const val HOLDER_MAX = 80

    val DEFAULT_TEXTS: Map<String, String> = mapOf(
        "title" to "برداشت از کیف پول",
        "amount_question" to "چقدر می‌خواهید برداشت کنید؟",
        "amount_available" to "قابل برداشت",
        "amount_placeholder" to "مبلغ دلخواه",
        "amount_suffix" to "تومان",
        "amount_all" to "همهٔ موجودی",
        "amount_min" to "کمترین مبلغ برداشت ۱۰٬۰۰۰٬۰۰۰ تومان است.",
        "below_min_title" to "فعلاً موجودی کافی برای برداشت ندارید",
        "below_min_body" to "کمترین مبلغ برداشت ۱۰٬۰۰۰٬۰۰۰ تومان است.",
        "dest_question" to "به کدام حساب واریز شود؟",
        "dest_new" to "شبای جدید",
        "dest_new_hint" to "شمارهٔ شبا ۲۴ رقم است و با IR شروع می‌شود.",
        "sheba_placeholder" to "۲۴ رقم شبا",
        "holder_label" to "نام صاحب حساب",
        "holder_placeholder" to "مثلاً علی رضایی",
        "holder_hint" to "باید با نام صاحب حساب در بانک یکی باشد.",
        "summary_question" to "اطلاعات برداشت را بررسی کنید",
        "summary_amount" to "مبلغ برداشت",
        "summary_dest" to "واریز به",
        "summary_holder" to "صاحب حساب",
        "summary_after" to "موجودی بعد از برداشت",
        "summary_note" to "مبلغ از همین حالا کنار گذاشته می‌شود و بعد از تأیید پشتیبانی وینور به حسابتان واریز می‌شود. تا قبل از بررسی می‌توانید لغوش کنید.",
        "submit" to "ثبت درخواست برداشت",
        "submitting" to "در حال ثبت…",
        "next" to "ادامه",
        "success_title" to "درخواست برداشت ثبت شد",
        "success_body" to "در انتظار بررسی پشتیبانی وینور است. نتیجه را پیامک می‌کنیم.",
        "success_tracking" to "کد پیگیری",
        "success_status" to "وضعیت",
        "back_to_wallet" to "بازگشت به کیف پول",
    )

    val DEFAULT_ERRORS: Map<String, String> = mapOf(
        "invalid_amount" to "مبلغ را درست وارد کنید.",
        "min_amount" to "کمترین مبلغ برداشت ۱۰٬۰۰۰٬۰۰۰ تومان است.",
        "insufficient_balance" to "این مبلغ از موجودی قابل برداشت شما بیشتر است.",
        "missing_bank" to "حساب مقصد را انتخاب کنید.",
        "invalid_bank" to "این حساب پیدا نشد. دوباره انتخاب کنید.",
        "sheba_required" to "برای این حساب شبا ثبت نشده. یک شبای جدید وارد کنید.",
        "sheba_format" to "شبا باید IR و ۲۴ رقم باشد.",
        "sheba_checksum" to "این شمارهٔ شبا معتبر نیست. دوباره بررسی کنید.",
        "invalid_holder" to "نام صاحب حساب را کامل بنویسید.",
        "save_failed" to "ثبت درخواست انجام نشد. دوباره تلاش کنید.",
    )

    data class Chip(val key: String, val amount: Long, val label: String, val sub: String, val all: Boolean)
    data class Destination(val id: String, val bankName: String, val holder: String, val shebaMasked: String, val isDefault: Boolean)
    data class Note(val key: String, val text: String)
    data class Request(
        val id: String,
        val tracking: String,
        val amount: Long,
        val amountLabel: String,
        val status: String,
        val statusLabel: String,
        val tone: String,
        val destination: String,
        val dateLabel: String,
        val cancellable: Boolean,
        val adminNote: String = "",
    )

    data class Options(
        val minAmount: Long,
        val available: Long,
        val blocked: Long,
        val pendingWithdrawal: Long,
        val canWithdraw: Boolean,
        val chips: List<Chip>,
        val notes: List<Note>,
        val destinations: List<Destination>,
        val requests: List<Request>,
        val texts: Map<String, String>,
        val errors: Map<String, String>,
        val csrf: String,
        val loaded: Boolean,
    ) {
        fun text(key: String): String = texts[key]?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXTS[key].orEmpty()
        fun error(key: String): String = errors[key]?.takeIf { it.isNotBlank() } ?: DEFAULT_ERRORS[key].orEmpty()
    }

    sealed class Outcome {
        data class Submitted(val request: Request, val availableAfter: Long, val duplicate: Boolean) : Outcome()
        data class Failed(val code: String, val message: String, val offline: Boolean = false) : Outcome()
    }

    fun toman(amount: Long): String = WalletCharge.toman(amount)

    private fun map(obj: JSONObject?, defaults: Map<String, String>): Map<String, String> {
        val out = defaults.toMutableMap()
        obj?.keys()?.forEach { k -> obj.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
        return out
    }

    fun parseRequest(o: JSONObject): Request = Request(
        id = o.optString("id"),
        tracking = o.optString("tracking_code"),
        amount = o.optLong("amount", 0L),
        amountLabel = o.optString("amount_label").ifBlank { toman(o.optLong("amount", 0L)) },
        status = o.optString("status").ifBlank { "pending" },
        statusLabel = o.optString("status_label").ifBlank { "در انتظار بررسی" },
        tone = o.optString("tone").ifBlank { "amber" },
        destination = o.optString("destination"),
        dateLabel = o.optString("date_label"),
        cancellable = o.optBoolean("cancellable", false),
        adminNote = o.optString("admin_note"),
    )

    fun parseRequests(arr: JSONArray?): List<Request> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseRequest(it) } }
    }

    /** پاسخ GET. سرور قدیمی یا خطا → loaded=false (دیالوگ منتظر می‌ماند و برداشت را باز نمی‌کند). */
    fun parseOptions(obj: JSONObject?): Options {
        val ok = obj != null && obj.optBoolean("ok") && obj.has("flow_version")
        val min = if (ok) obj!!.optLong("min_amount", DEFAULT_MIN).coerceAtLeast(1L) else DEFAULT_MIN
        val available = if (ok) obj!!.optLong("available", 0L).coerceAtLeast(0L) else 0L
        val chips = mutableListOf<Chip>()
        obj?.optJSONArray("chips")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                chips.add(Chip(c.optString("key"), c.optLong("amount", 0L), c.optString("label"), c.optString("sub"), c.optBoolean("all")))
            }
        }
        val notes = mutableListOf<Note>()
        obj?.optJSONArray("notes")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { notes.add(Note(it.optString("key"), it.optString("text"))) }
        }
        val dest = mutableListOf<Destination>()
        obj?.optJSONArray("destinations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                if (d.optString("id").isBlank()) continue
                dest.add(Destination(d.optString("id"), d.optString("bank_name"), d.optString("holder_name"),
                    d.optString("sheba_masked"), d.optBoolean("is_default")))
            }
        }
        return Options(
            minAmount = min,
            available = available,
            blocked = obj?.optLong("blocked", 0L) ?: 0L,
            pendingWithdrawal = obj?.optLong("pending_withdrawal", 0L) ?: 0L,
            canWithdraw = ok && obj!!.optBoolean("can_withdraw", available >= min),
            chips = chips,
            notes = notes,
            destinations = dest,
            requests = parseRequests(obj?.optJSONArray("requests")),
            texts = map(obj?.optJSONObject("texts"), DEFAULT_TEXTS),
            errors = map(obj?.optJSONObject("errors"), DEFAULT_ERRORS),
            csrf = obj?.optString("csrf_token").orEmpty(),
            loaded = ok,
        )
    }

    fun parseOutcome(obj: JSONObject?, options: Options): Outcome {
        val o = obj ?: JSONObject()
        val req = o.optJSONObject("request")
        if ((o.optBoolean("ok") || o.optBoolean("success")) && req != null) {
            return Outcome.Submitted(parseRequest(req), o.optLong("available", 0L), o.optBoolean("duplicate"))
        }
        val code = o.optString("error").ifBlank { if (o.optBoolean("offline")) "offline" else "save_failed" }
        val msg = o.optString("message").ifBlank { options.error(code) }.ifBlank { "درخواست انجام نشد. دوباره تلاش کنید." }
        return Outcome.Failed(code, msg, o.optBoolean("offline"))
    }

    /** فقط رقم (فارسی و عربی هم)؛ «IR» و فاصله و خط تیره دور ریخته می‌شوند. */
    fun shebaDigits(raw: String?): String {
        val out = StringBuilder()
        for (c in raw.orEmpty()) {
            when (c) {
                in '0'..'9' -> out.append(c)
                in '۰'..'۹' -> out.append('0' + (c - '۰'))
                in '٠'..'٩' -> out.append('0' + (c - '٠'))
            }
        }
        return out.toString().take(24)
    }

    /** "" یعنی معتبر؛ وگرنه کد خطا (sheba_format یا sheba_checksum). ISO 13616: IR = 18 27. */
    fun shebaError(digits: String): String {
        if (digits.length != 24 || digits.any { it !in '0'..'9' }) return "sheba_format"
        val rearranged = digits.substring(2) + "1827" + digits.substring(0, 2)
        var rem = 0
        for (ch in rearranged) rem = (rem * 10 + (ch - '0')) % 97
        return if (rem == 1) "" else "sheba_checksum"
    }

    fun maskSheba(digits: String): String =
        if (digits.length < 6) "" else "IR${digits.take(2)} •••• •••• ${digits.takeLast(4)}"

    fun holderError(raw: String?): String {
        val name = raw.orEmpty().trim().replace(Regex("\\s+"), " ")
        if (name.length < 2 || name.length > HOLDER_MAX) return "invalid_holder"
        if (name.any { it.isDigit() || it in "<>{}" }) return "invalid_holder"
        return ""
    }
}

/** وضعیت مرحله‌های برداشت. client_request_id برای هر بار باز شدن ویزارد ثابت است. */
class WalletWithdrawFlow(var options: WalletWithdrawWizard.Options, val requestId: String = UUID.randomUUID().toString()) {
    enum class Step { AMOUNT, DEST, SUMMARY, DONE }

    var step: Step = Step.AMOUNT
        private set
    var digits: String = ""
        private set
    var takeAll: Boolean = false
        private set
    var destId: String = ""
        private set
    var shebaInput: String = ""
        private set
    var holderInput: String = ""
        private set
    var submitted: WalletWithdrawWizard.Request? = null
        private set

    val belowMin: Boolean get() = options.loaded && !options.canWithdraw

    fun applyOptions(next: WalletWithdrawWizard.Options) {
        options = next
        if (destId.isBlank()) next.destinations.firstOrNull { it.isDefault }?.let { destId = it.id }
    }

    fun amount(): Long = if (takeAll) options.available else (digits.toLongOrNull() ?: 0L)

    fun setInput(raw: String?) { digits = WalletCharge.digitsOf(raw); takeAll = false }

    fun pick(chip: WalletWithdrawWizard.Chip) {
        takeAll = chip.all
        digits = if (chip.all) "" else chip.amount.toString()
    }

    fun selectedChip(): String? = when {
        takeAll -> options.chips.firstOrNull { it.all }?.key
        else -> options.chips.firstOrNull { !it.all && it.amount == amount() }?.key
    }

    fun amountError(): String {
        if (takeAll) return ""
        val a = amount()
        if (a <= 0L) return ""
        if (a < options.minAmount) return options.error("min_amount")
        if (a > options.available) return options.error("insufficient_balance")
        return ""
    }

    fun chooseDest(id: String) { destId = id }

    fun setSheba(raw: String?) { shebaInput = WalletWithdrawWizard.shebaDigits(raw) }

    fun setHolder(raw: String?) { holderInput = raw.orEmpty().take(WalletWithdrawWizard.HOLDER_MAX) }

    /** خطای نمایشی شبا: تا ۲۴ رقم کامل نشده، خطای «قالب» نشان داده نمی‌شود. */
    fun shebaMessage(): String {
        if (destId != NEW || shebaInput.isEmpty()) return ""
        val err = WalletWithdrawWizard.shebaError(shebaInput)
        if (err == "sheba_format" && shebaInput.length < 24) return ""
        return if (err.isEmpty()) "" else options.error(err)
    }

    fun holderMessage(): String {
        if (destId != NEW || holderInput.isBlank()) return ""
        val err = WalletWithdrawWizard.holderError(holderInput)
        return if (err.isEmpty()) "" else options.error(err)
    }

    fun canContinue(): Boolean = when (step) {
        Step.AMOUNT -> options.canWithdraw && amount() > 0L && amountError().isEmpty()
        Step.DEST -> when {
            destId.isBlank() -> false
            destId == NEW -> WalletWithdrawWizard.shebaError(shebaInput).isEmpty() &&
                WalletWithdrawWizard.holderError(holderInput).isEmpty()
            else -> options.destinations.any { it.id == destId }
        }
        Step.SUMMARY -> true
        Step.DONE -> true
    }

    fun next(): Boolean {
        if (!canContinue()) return false
        step = when (step) {
            Step.AMOUNT -> Step.DEST
            Step.DEST -> Step.SUMMARY
            else -> return false
        }
        return true
    }

    /** true یعنی در ویزارد ماندیم؛ false یعنی بسته شود. */
    fun back(): Boolean = when (step) {
        Step.DEST -> { step = Step.AMOUNT; true }
        Step.SUMMARY -> { step = Step.DEST; true }
        else -> false
    }

    fun progressLabel(): String = when (step) {
        Step.AMOUNT -> "مرحلهٔ ۱ از ۳"
        Step.DEST -> "مرحلهٔ ۲ از ۳"
        Step.SUMMARY -> "مرحلهٔ ۳ از ۳"
        Step.DONE -> ""
    }

    fun progressIndex(): Int = when (step) { Step.AMOUNT -> 1; Step.DEST -> 2; else -> 3 }

    fun destLabel(): String = if (destId == NEW) WalletWithdrawWizard.maskSheba(shebaInput)
    else options.destinations.firstOrNull { it.id == destId }?.let { listOf(it.bankName, it.shebaMasked).filter(String::isNotBlank).joinToString(" · ") }.orEmpty()

    fun holderLabel(): String = if (destId == NEW) holderInput.trim().replace(Regex("\\s+"), " ")
    else options.destinations.firstOrNull { it.id == destId }?.holder.orEmpty()

    fun summaryRows(): List<Pair<String, String>> = listOf(
        options.text("summary_amount") to WalletWithdrawWizard.toman(amount()),
        options.text("summary_dest") to destLabel(),
        options.text("summary_holder") to holderLabel(),
        options.text("summary_after") to WalletWithdrawWizard.toman((options.available - amount()).coerceAtLeast(0L)),
    )

    fun body(): JSONObject = JSONObject().apply {
        put("client_request_id", requestId)
        if (takeAll) put("all", true) else put("amount", amount())
        if (destId == NEW) {
            put("sheba", "IR$shebaInput")
            put("holder_name", holderLabel())
        } else {
            put("account_id", destId)
        }
    }

    fun done(request: WalletWithdrawWizard.Request) { submitted = request; step = Step.DONE }

    companion object { const val NEW = "new" }
}
