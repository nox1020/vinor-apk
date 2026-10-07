package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * قرارداد واقعی کیف پول روی vinor.ir:
 * GET  /express/partner/bank-accounts/data
 * POST /express/partner/bank-accounts/add|delete|update|default
 * POST /express/partner/withdrawals/request
 * POSTها CSRF با هدر X-CSRFToken و Referer صفحهٔ کمیسیون می‌خواهند.
 * نام فیلدها از HTML لاگین‌شدهٔ همان صفحه خوانده می‌شود؛ اگر صفحه فیلد ندهد، نام‌های پایدار همین مسیرها استفاده می‌شود.
 */
object WalletWithdraw {
    const val BANK_ACCOUNTS = "/express/partner/bank-accounts/data"
    const val BANK_ADD = "/express/partner/bank-accounts/add"
    const val BANK_DELETE = "/express/partner/bank-accounts/delete"
    const val BANK_UPDATE = "/express/partner/bank-accounts/update"
    const val BANK_DEFAULT = "/express/partner/bank-accounts/default"
    const val REQUEST = "/express/partner/withdrawals/request"
    const val PROFILE_DATA = "/express/partner/profile/data"
    const val HOME_DATA = "/express/partner/home/data"
    const val COMMISSIONS_HTML = "/express/partner/commissions"
    const val ORIGIN = "https://vinor.ir"
    const val REFERER_PATH = "/express/partner/commissions"

    data class BankAccount(
        val id: String,
        val bankName: String,
        val ownerName: String,
        val sheba: String,
        val isDefault: Boolean,
    )

    data class BodyFields(
        val withdrawAmount: String = "amount",
        val withdrawAccount: String = "bank_account_id",
        val addSheba: String = "sheba",
        val addOwner: String = "owner_name",
        val addBank: String = "bank_name",
        val accountId: String = "id",
    )

    data class Pending(val amount: Long, val label: String)

    private val modalIds = listOf("vnWithdrawModal", "vnBankPickerModal", "vnBankAddModal")
    private val pendingStatuses = setOf("pending", "requested", "waiting", "review", "processing")

    fun fieldsFromHtml(html: String): BodyFields {
        val withdrawKeys = modalNames(html, "vnWithdrawModal") + keysNear(html, "withdrawals/request")
        val addKeys = modalNames(html, "vnBankAddModal") + keysNear(html, "bank-accounts/add")
        val idKeys = keysNear(html, "bank-accounts/delete") +
            keysNear(html, "bank-accounts/default") +
            keysNear(html, "bank-accounts/update")
        return BodyFields(
            withdrawAmount = pick(withdrawKeys, listOf("amount", "withdraw_amount", "value"), "amount"),
            withdrawAccount = pick(
                withdrawKeys,
                listOf("bank_account_id", "account_id", "bank_id", "card_id"),
                "bank_account_id",
            ),
            addSheba = pick(addKeys, listOf("sheba", "iban", "sheba_number", "account_sheba"), "sheba"),
            addOwner = pick(
                addKeys,
                listOf("owner_name", "holder_name", "account_holder", "owner", "full_name"),
                "owner_name",
            ),
            addBank = pick(addKeys, listOf("bank_name", "bank", "bank_title"), "bank_name"),
            accountId = pick(idKeys, listOf("bank_account_id", "account_id", "id"), "id"),
        )
    }

    fun normalizeSheba(raw: String): String {
        val digits = PartnerApi.asciiDigits(raw)
        val letters = raw.filter { it.isLetter() }.uppercase()
        val compact = (letters + digits).replace(" ", "")
        return when {
            compact.startsWith("IR") -> "IR" + PartnerApi.asciiDigits(compact.drop(2)).take(24)
            digits.length == 24 -> "IR$digits"
            else -> compact
        }
    }

    fun shebaError(raw: String): String? {
        val sheba = normalizeSheba(raw)
        if (sheba.isBlank() || sheba == "IR") return "شماره شبا را وارد کنید."
        if (!sheba.matches(Regex("IR[0-9]{24}"))) return "شماره شبا باید با IR و ۲۴ رقم باشد."
        if (!ibanChecksumOk(sheba)) return "شماره شبا معتبر نیست."
        return null
    }

    fun formatSheba(raw: String): String {
        val sheba = normalizeSheba(raw)
        if (sheba.length < 4) return sheba
        return sheba.chunked(4).joinToString(" ")
    }

    fun parseAmount(raw: String): Long? = PartnerApi.asciiDigits(raw).toLongOrNull()

    fun amountError(raw: String, available: Long?): String? {
        val amount = parseAmount(raw)
        if (amount == null) return "مبلغ را وارد کنید."
        if (amount <= 0L) return "مبلغ باید بیشتر از صفر باشد."
        if (available != null && available <= 0L) return "موجودی قابل برداشت ندارید."
        if (available != null && amount > available) return "مبلغ از موجودی قابل برداشت بیشتر است."
        return null
    }

    fun ownerError(raw: String): String? =
        if (raw.trim().length < 2) "نام صاحب حساب را وارد کنید." else null

    fun bankError(raw: String): String? =
        if (raw.trim().isEmpty()) "نام بانک را وارد کنید." else null

    fun accountTitle(account: BankAccount): String {
        val bank = account.bankName.trim()
        val owner = account.ownerName.trim()
        return when {
            bank.isNotEmpty() && owner.isNotEmpty() -> "$bank · $owner"
            bank.isNotEmpty() -> bank
            owner.isNotEmpty() -> owner
            else -> "حساب شبا"
        }
    }

    fun readBalance(root: JSONObject?): Long? {
        if (root == null) return null
        val direct = readBalanceKeys(root)
        if (direct != null) return direct
        val data = root.optJSONObject("data") ?: return null
        return readBalanceKeys(data)
    }

    fun parseAccounts(root: JSONObject?): List<BankAccount> {
        if (root == null) return emptyList()
        val arrays = ArrayList<JSONArray>()
        collectAccountArrays(root, arrays)
        root.optJSONObject("data")?.let { collectAccountArrays(it, arrays) }
        val out = ArrayList<BankAccount>()
        val seen = HashSet<String>()
        for (arr in arrays) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.has("land_code") || obj.has("commission_amount")) continue
                val sheba = firstString(obj, "sheba", "iban", "sheba_number", "account_sheba")
                val id = firstString(obj, "id", "bank_account_id", "account_id")
                if (sheba.isBlank() && id.isBlank()) continue
                val account = BankAccount(
                    id = id.ifBlank { normalizeSheba(sheba) },
                    bankName = firstString(obj, "bank_name", "bank", "title"),
                    ownerName = firstString(obj, "owner_name", "holder_name", "account_holder", "owner", "full_name"),
                    sheba = normalizeSheba(sheba),
                    isDefault = truthy(obj, "is_default") || truthy(obj, "default") || truthy(obj, "is_primary"),
                )
                if (seen.add(account.id + "|" + account.sheba)) out.add(account)
            }
        }
        return out
    }

    fun parsePending(vararg roots: JSONObject?): Pending? {
        for (root in roots) {
            if (root == null) continue
            pendingIn(root)?.let { return it }
            root.optJSONObject("data")?.let { pendingIn(it) }?.let { return it }
        }
        return null
    }

    fun withdrawBody(amount: Long, accountId: String, fields: BodyFields): JSONObject {
        return JSONObject()
            .put(fields.withdrawAmount, amount)
            .put(fields.withdrawAccount, jsonId(accountId))
    }

    fun addBody(sheba: String, owner: String, bank: String, fields: BodyFields): JSONObject {
        return JSONObject()
            .put(fields.addSheba, normalizeSheba(sheba))
            .put(fields.addOwner, owner.trim())
            .put(fields.addBank, bank.trim())
    }

    fun idBody(accountId: String, fields: BodyFields): JSONObject {
        return JSONObject().put(fields.accountId, jsonId(accountId))
    }

    fun isCsrfExpired(payload: JSONObject): Boolean = payload.optString("error") == "csrf_expired"

    fun isUnauthorized(payload: JSONObject): Boolean {
        if (payload.optInt("http_status") == 401) return true
        return payload.optString("error") == "unauthorized"
    }

    fun csrfOf(payload: JSONObject?): String = payload?.optString("csrf_token").orEmpty().trim()

    fun isOk(payload: JSONObject): Boolean {
        val code = payload.optInt("http_status", 200)
        if (code !in 200..299) return false
        if (isCsrfExpired(payload) || isUnauthorized(payload)) return false
        if (payload.has("success") && !payload.optBoolean("success")) return false
        if (payload.has("ok") && !payload.optBoolean("ok")) return false
        if (payload.optString("error").isNotBlank()) return false
        return true
    }

    fun userMessage(payload: JSONObject): String {
        val message = payload.optString("message").trim()
        if (message.isNotEmpty()) return message
        val fields = payload.optJSONObject("fields") ?: payload.optJSONObject("errors")
        if (fields != null) {
            val keys = fields.keys()
            while (keys.hasNext()) {
                val value = fields.optString(keys.next()).trim()
                if (value.isNotEmpty()) return value
            }
        }
        val err = payload.optString("error").trim()
        if (err.any { it.code > 127 }) return err
        if (isCsrfExpired(payload)) return "جلسه منقضی شده؛ صفحه را تازه کنید."
        if (isUnauthorized(payload)) return "برای برداشت باید وارد حساب شوید."
        if (payload.optBoolean("offline")) return "برای انجام این عملیات اینترنت لازم است."
        return "مشکلی پیش آمد؛ دوباره تلاش کنید."
    }

    fun statusLabel(status: String): String = when (status) {
        "paid" -> "پرداخت‌شده"
        "approved" -> "تایید شده"
        "rejected" -> "رد شده"
        "processing", "review" -> "در حال بررسی"
        else -> "در انتظار"
    }

    private fun pendingIn(src: JSONObject): Pending? {
        for (key in listOf("withdrawals", "pending_withdrawals", "payouts", "requests")) {
            val arr = src.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.has("land_code")) continue
                val status = obj.optString("status").ifBlank { "pending" }
                if (status !in pendingStatuses) continue
                val amount = readLong(obj, "amount") ?: readLong(obj, "withdraw_amount") ?: 0L
                return Pending(amount, statusLabel(status))
            }
        }
        return null
    }

    private fun readBalanceKeys(obj: JSONObject): Long? {
        for (key in listOf("withdrawable_balance", "available_balance", "withdrawable", "available_amount")) {
            readLong(obj, key)?.let { return it.coerceAtLeast(0L) }
        }
        return null
    }

    private fun readLong(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val value = obj.opt(key)) {
            is Number -> value.toLong()
            is String -> PartnerApi.asciiDigits(value).toLongOrNull()
            else -> null
        }
    }

    private fun collectAccountArrays(obj: JSONObject, into: MutableList<JSONArray>) {
        for (key in listOf("accounts", "bank_accounts", "banks", "items", "cards", "results")) {
            obj.optJSONArray(key)?.let { into.add(it) }
        }
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    private fun truthy(obj: JSONObject, key: String): Boolean {
        if (!obj.has(key) || obj.isNull(key)) return false
        return when (val value = obj.opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value == "1" || value.equals("true", true) || value == "yes"
            else -> false
        }
    }

    private fun jsonId(id: String): Any {
        val trimmed = id.trim()
        val number = trimmed.toLongOrNull()
        return if (number != null && trimmed.all { it.isDigit() }) number else trimmed
    }

    private fun pick(keys: List<String>, options: List<String>, fallback: String): String {
        for (option in options) {
            if (option in keys) return option
        }
        return fallback
    }

    private fun modalNames(html: String, id: String): List<String> {
        val slice = modalSlice(html, id)
        if (slice.isEmpty()) return emptyList()
        val names = Regex("""name\s*=\s*["']([A-Za-z_][A-Za-z0-9_]*)["']""")
            .findAll(slice)
            .map { it.groupValues[1] }
            .filter { it != "csrf_token" }
            .toList()
        return names + appendedKeys(slice)
    }

    private fun keysNear(html: String, endpoint: String): List<String> {
        val at = html.indexOf(endpoint)
        if (at < 0) return emptyList()
        val from = (at - 1800).coerceAtLeast(0)
        val to = (at + 1800).coerceAtMost(html.length)
        val window = html.substring(from, to)
        val objectKeys = Regex("""["']?([A-Za-z_][A-Za-z0-9_]*)["']?\s*:""")
            .findAll(window)
            .map { it.groupValues[1] }
            .filter { it !in jsNoise }
            .toList()
        return objectKeys + appendedKeys(window)
    }

    private fun appendedKeys(window: String): List<String> {
        return Regex("""append\(\s*["']([A-Za-z_][A-Za-z0-9_]*)["']""")
            .findAll(window)
            .map { it.groupValues[1] }
            .filter { it != "csrf_token" }
            .toList()
    }

    private fun modalSlice(html: String, id: String): String {
        val start = listOf("id=\"$id\"", "id='$id'")
            .map { html.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return ""
        var end = html.length
        for (other in modalIds) {
            if (other == id) continue
            for (marker in listOf("id=\"$other\"", "id='$other'")) {
                val at = html.indexOf(marker, start + id.length)
                if (at in (start + 1) until end) end = at
            }
        }
        return html.substring(start, end.coerceAtMost(start + 20000))
    }

    private fun ibanChecksumOk(sheba: String): Boolean {
        val rearranged = sheba.drop(4) + sheba.take(4)
        var remainder = 0
        for (ch in rearranged) {
            val digits = if (ch.isDigit()) ch.toString() else (ch.code - 55).toString()
            for (digit in digits) {
                remainder = (remainder * 10 + (digit - '0')) % 97
            }
        }
        return remainder == 1
    }

    private val jsNoise = setOf(
        "method", "headers", "body", "credentials", "signal", "mode", "cache",
        "redirect", "referrer", "integrity", "keepalive", "window", "document",
        "if", "function", "return", "var", "let", "const", "catch", "then",
        "Accept", "Content-Type",
    )
}
