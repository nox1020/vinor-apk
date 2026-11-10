package ir.vinor.app

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.async
import org.json.JSONArray
import java.util.UUID
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentCommissionsBinding
import ir.vinor.app.databinding.ItemBankAccountBinding
import ir.vinor.app.databinding.ItemCommissionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * کیف پول نیتیو. لیست پورسانت از /express/partner/commissions/data.
 * درخواست برداشت همان POST وب است: حساب شبا و /express/partner/withdrawals/request.
 */
class CommissionsFragment : Fragment() {

    private var _binding: FragmentCommissionsBinding? = null
    private val binding get() = _binding!!

    private var fields = WalletWithdraw.BodyFields()
    private var accounts: List<WalletWithdraw.BankAccount> = emptyList()
    private var selectedId: String? = null
    private var csrf: String = ""
    private var withdrawable: Long = 0L
    private var withdrawableKnown: Boolean = false
    private var minWithdrawal: Long = WalletWithdraw.DEFAULT_MIN_WITHDRAWAL
    // شناسهٔ ثابت درخواست برای تلاش مجدد همان مبلغ/حساب (سرور تکراری را یک‌بار ثبت می‌کند)
    private var withdrawRequestKey: String? = null
    private var withdrawRequestId: String? = null
    private var bankCatalog = JSONObject()
    private var busy: Boolean = false
    private var step: Step = Step.REQUEST
    private var addFromPick: Boolean = false
    private var pendingDeleteId: String? = null
    private var loadGen = 0
    private var rendered = false
    private var skipNextResume = true

    private enum class Step { REQUEST, PICK, ADD }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        skipNextResume = true
        _binding = FragmentCommissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.commissionsBack.setOnClickListener {
            if (binding.withdrawSheet.isVisible) {
                onSheetBack()
            } else if (!findNavController().navigateUp()) {
                (activity as? MainActivity)?.openPartnerHome()
            }
        }
        binding.commissionsWithdrawBtn.setOnClickListener { openWithdraw() }
        binding.commissionsChargeBtn.setOnClickListener { openCharge() }
        val owed = pendingShortfall
        if (owed > 0L) {
            pendingShortfall = 0L
            openCharge(owed)
        }
        binding.withdrawSheetBack.setOnClickListener { onSheetBack() }
        binding.withdrawAccountRow.setOnClickListener { showStep(Step.PICK) }
        binding.withdrawAddOpen.setOnClickListener {
            addFromPick = true
            showStep(Step.ADD)
        }
        binding.withdrawSheetSubmit.setOnClickListener { onSheetSubmit() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (_binding?.withdrawSheet?.isVisible == true) {
                        onSheetBack()
                        return
                    }
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            },
        )
        remembered?.let {
            if (WalletWithdraw.hasCommissionPayload(it)) {
                bindCommissionPayload(it)
                rendered = true
            }
        }
        loadData()
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResume) {
            skipNextResume = false
            return
        }
        if (_binding != null && binding.withdrawSheet.isVisible) return
        if (_binding != null) loadData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun formatToman(value: Long): String = PartnerFormat.toman(value)

    private fun formatDate(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        val raw = iso.trim().replace("Z", "")
        val outFmt = SimpleDateFormat("yyyy/MM/dd", Locale("fa"))
        val parsers = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        )
        for (parser in parsers) {
            try {
                val d = parser.parse(raw.take(19).trim())
                if (d != null) return outFmt.format(d)
            } catch (_: Exception) { }
        }
        return raw.take(10).replace("-", "/")
    }

    private fun statusText(status: String): String =
        WalletWithdraw.commissionStatusText(status).ifBlank { "در انتظار" }

    private fun statusColor(status: String): Int {
        val color = when (WalletWithdraw.commissionKind(status)) {
            "paid", "success" -> R.color.vinor_accent_green
            "approved" -> R.color.vinor_text
            "rejected" -> R.color.vinor_error
            else -> R.color.vinor_text_muted
        }
        return ContextCompat.getColor(requireContext(), color)
    }

    private fun loadData() {
        val ctx = context ?: return
        if (!rendered) {
            binding.commissionsProgress.visibility = View.VISIBLE
            binding.commissionsList.visibility = View.GONE
            binding.commissionsEmpty.visibility = View.GONE
        }
        val gen = ++loadGen
        val stamp = sessionStamp
        viewLifecycleOwner.lifecycleScope.launch {
            val wasFresh = withContext(Dispatchers.IO) { PartnerApi.isFresh(COMMISSIONS_DATA, ctx) }
            try {
                var paintedByLoader = false
                val obj = PartnerApi.loadDisplay(COMMISSIONS_DATA, ctx) { data ->
                    if (_binding == null || gen != loadGen || stamp != sessionStamp) return@loadDisplay
                    if (!WalletWithdraw.hasCommissionPayload(data)) return@loadDisplay
                    if (VinorPageLoad.needsLogin(data)) return@loadDisplay
                    bindCommissionPayload(data)
                    paintedByLoader = true
                    rendered = true
                }
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                if (VinorPageLoad.needsLogin(obj)) {
                    clearRemembered()
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (!WalletWithdraw.hasCommissionPayload(obj)) {
                    if (rendered && VinorPageLoad.networkFailed(wasFresh, obj)) showOffline()
                    else if (!rendered) showOffline()
                    return@launch
                }
                if (!paintedByLoader) bindCommissionPayload(obj)
                rendered = true
                if (VinorPageLoad.canRemember(obj)) remembered = VinorPageLoad.rememberedCopy(obj)
                if (rendered && VinorPageLoad.networkFailed(wasFresh, obj)) showOffline()
                if (WalletWithdraw.readBalance(obj) == null) {
                    val home = withContext(Dispatchers.IO) {
                        PartnerApi.peekJson(WalletWithdraw.HOME_DATA, ctx)
                            ?: PartnerApi.get(WalletWithdraw.HOME_DATA, ctx)
                    }
                    if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                    WalletWithdraw.readBalance(home)?.let { setWithdrawable(it) }
                }
                WalletWithdraw.parsePending(obj)?.let { showPending(it) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "loadData error", e)
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                showOffline()
            } finally {
                if (_binding != null && gen == loadGen) binding.commissionsProgress.visibility = View.GONE
            }
        }
    }

    private fun bindCommissionPayload(obj: JSONObject) {
        val root = WalletWithdraw.commissionRoot(obj)
        val total = WalletWithdraw.money(root, "total_commission")
        val pending = WalletWithdraw.money(root, "pending_commission")
        val paid = WalletWithdraw.money(root, "paid_commission")
        val soldCount = WalletWithdraw.money(root, "sold_count")
        val itemsArr = obj.optJSONArray("transactions") ?: WalletWithdraw.commissionItems(obj)
        minWithdrawal = obj.optLong("min_withdrawal_amount", minWithdrawal).coerceAtLeast(0L)
        val inWithdrawal = obj.optLong("in_withdrawal_amount", 0L)
        binding.commissionsWithdrawStatus.isVisible = inWithdrawal > 0L
        if (inWithdrawal > 0L) binding.commissionsWithdrawStatus.text =
            "در حال برداشت: ${formatToman(inWithdrawal)} تومان"
        val items = mutableListOf<JSONObject>()
        for (i in 0 until itemsArr.length()) {
            itemsArr.optJSONObject(i)?.let { items.add(it) }
        }
        bind(total, pending, paid, soldCount, items)
        val total = obj.optLong("wallet_total", root.optLong("wallet_total", -1L))
        val blocked = obj.optLong("wallet_blocked", root.optLong("wallet_blocked", 0L))
        val available = obj.optLong(
            "wallet_available",
            root.optLong("wallet_available", obj.optLong("requestable_balance", withdrawable)),
        )
        if (available >= 0L) setWithdrawable(available)
        else WalletWithdraw.readBalance(obj)?.let { setWithdrawable(it) }
        binding.commissionsBlocked.text = "بلوکه‌شده برای پورسانت: ${formatToman(blocked)} تومان"
        val shownTotal = if (total >= 0L) total else withdrawable + blocked
        binding.commissionsAvailable.text = "کل موجودی: ${formatToman(shownTotal)} تومان"
        WalletWithdraw.csrfOf(obj).takeIf { it.isNotBlank() }?.let { csrf = it }
    }

    private fun setWithdrawable(amount: Long) {
        withdrawable = amount.coerceAtLeast(0L)
        withdrawableKnown = true
        val label = PartnerFormat.toman(withdrawable) + " تومان"
        // Keep account management accessible even below the minimum.
        // The actual request is checked against the server-provided limit.
        binding.commissionsWithdrawable.text = label
        if (binding.withdrawSheet.isVisible) binding.withdrawAvailable.text = label
    }

    private fun showPending(pending: WalletWithdraw.Pending) {
        val text = if (pending.amount > 0L) {
            "درخواست ${pending.label}: " + PartnerFormat.toman(pending.amount) + " تومان"
        } else {
            "درخواست ${pending.label}"
        }
        binding.commissionsWithdrawStatus.text = text
        binding.commissionsWithdrawStatus.visibility = View.VISIBLE
    }

    private fun showOffline() {
        if (_binding == null) return
        binding.commissionsProgress.visibility = View.GONE
        if (rendered) {
            VinorPageLoad.showOffline(binding.root) { loadData() }
            return
        }
        binding.commissionsEmptyText.text = VinorPageLoad.OFFLINE
        binding.commissionsEmpty.visibility = View.VISIBLE
        binding.commissionsList.visibility = View.GONE
    }

    private fun bind(
        total: Long,
        pending: Long,
        paid: Long,
        soldCount: Long,
        items: List<JSONObject>
    ) {
        if (_binding == null) return
        binding.commissionsProgress.visibility = View.GONE
        binding.commissionsTotal.text = formatToman(total) + " تومان"
        binding.commissionsPending.text = formatToman(pending) + " تومان"
        binding.commissionsPaid.text = formatToman(paid) + " تومان"
        binding.commissionsSoldCount.text = formatToman(soldCount)

        val list = binding.commissionsList
        list.removeAllViews()

        if (items.isEmpty()) {
            list.visibility = View.GONE
            binding.commissionsEmptyText.text = "هنوز پورسانتی ثبت نشده است."
            binding.commissionsEmpty.visibility = View.VISIBLE
            return
        }
        list.visibility = View.VISIBLE
        binding.commissionsEmpty.visibility = View.GONE

        for (i in items.indices) {
            val c = items[i]
            val itemBinding = ItemCommissionBinding.inflate(layoutInflater, list, false)
            val isTransaction = c.has("txn_type")
            itemBinding.itemCommissionLandCode.text = if (isTransaction)
                c.optString("title").ifBlank { "تراکنش" }
            else "کد فایل: ${c.optString("land_code", "—")}"
            itemBinding.itemCommissionDate.text = formatDate(c.optString("created_at"))
            val status = c.optString("status").trim().ifBlank { c.optString("commission_status").trim() }
                .ifBlank { "pending" }
            itemBinding.itemCommissionStatus.text =
                if (isTransaction) c.optString("status_label").ifBlank { statusText(status) }
                else statusText(status)
            itemBinding.itemCommissionStatus.setTextColor(statusColor(status))
            val pct = c.optDouble("commission_pct", 0.0)
            itemBinding.itemCommissionPct.isVisible = !isTransaction
            itemBinding.itemCommissionPct.text = if (pct == pct.toLong().toDouble()) "${pct.toLong()}%" else "$pct%"
            val amount = if (isTransaction) WalletWithdraw.money(c, "amount") else
                WalletWithdraw.money(c, "original_amount", "commission_amount", "payout_amount", "amount")
            val sign = if (isTransaction) c.optString("amount_sign").ifBlank { "+" } else "+"
            itemBinding.itemCommissionAmount.text = sign + formatToman(amount) + " تومان"
            itemBinding.itemCommissionAmount.setTextColor(ContextCompat.getColor(requireContext(),
                if (sign == "-") R.color.vinor_text else R.color.vinor_accent_green))
            val saleAmount = WalletWithdraw.money(c, "sale_amount")
            if (!isTransaction && saleAmount > 0) {
                itemBinding.itemCommissionSaleRow.visibility = View.VISIBLE
                itemBinding.itemCommissionSaleAmount.text = formatToman(saleAmount) + " تومان"
            } else {
                itemBinding.itemCommissionSaleRow.visibility = View.GONE
            }
            if (isTransaction && c.optBoolean("cancellable")) {
                val wid = c.optString("withdrawal_id")
                if (wid.isNotBlank()) {
                    itemBinding.root.isClickable = true
                    itemBinding.root.setOnClickListener {
                        AlertDialog.Builder(requireContext())
                            .setTitle("لغو درخواست برداشت")
                            .setMessage("این درخواست هنوز در انتظار بررسی است. لغو شود؟")
                            .setNegativeButton("بازگشت", null)
                            .setPositiveButton("لغو درخواست") { _, _ ->
                                postFinance("/express/partner/withdrawals/$wid/cancel",
                                    JSONObject(), requestError = true) {
                                    Toast.makeText(requireContext(), "درخواست لغو شد.", Toast.LENGTH_SHORT).show()
                                    loadData()
                                }
                            }.show()
                    }
                }
            }
            list.addView(itemBinding.root)
        }
    }

    private fun openWithdraw() {
        if (!PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openLogin()
            return
        }
        binding.withdrawAmount.setText("")
        showRequestError(null)
        showStep(Step.REQUEST)
        binding.withdrawSheet.visibility = View.VISIBLE
        refreshWithdrawContext()
    }

    private fun refreshWithdrawContext() {
        val ctx = context ?: return
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val bankRequest = async(Dispatchers.IO) { PartnerApi.get(WalletWithdraw.BANK_ACCOUNTS, ctx) }
            val profileRequest = async(Dispatchers.IO) { PartnerApi.get(WalletWithdraw.PROFILE_DATA, ctx) }
            val banks: JSONObject
            val profile: JSONObject
            try {
                banks = bankRequest.await()
                profile = profileRequest.await()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding != null) {
                    setBusy(false)
                    showRequestError("دریافت حساب‌های بانکی ناموفق بود. دوباره تلاش کنید.")
                }
                return@launch
            }
            if (_binding == null) return@launch
            setBusy(false)
            fields = WalletWithdraw.BodyFields()
            bankCatalog = banks
            minWithdrawal = banks.optLong("min_withdrawal_amount", minWithdrawal)
            val names = (banks.optJSONArray("banks") ?: JSONArray()).let { catalog ->
                (0 until catalog.length()).mapNotNull { catalog.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            }
            binding.withdrawBankName.setAdapter(ArrayAdapter(requireContext(),
                android.R.layout.simple_dropdown_item_1line, names))
            binding.withdrawBankName.setOnClickListener { binding.withdrawBankName.showDropDown() }
            csrf = WalletWithdraw.csrfOf(profile).ifBlank { WalletWithdraw.csrfOf(banks) }.ifBlank { csrf }
            if (WalletWithdraw.isUnauthorized(banks)) {
                closeSheet()
                (activity as? MainActivity)?.openLogin()
                return@launch
            }
            if (!WalletWithdraw.isOk(banks) && WalletWithdraw.parseAccounts(banks).isEmpty()) {
                val message = WalletWithdraw.userMessage(banks)
                if (banks.optInt("http_status") !in 200..299) showRequestError(message)
            }
            accounts = WalletWithdraw.parseAccounts(banks)
            WalletWithdraw.readBalance(banks)?.let { setWithdrawable(it) }
            WalletWithdraw.parsePending(banks)?.let { showPending(it) }
            if (accounts.none { it.id == selectedId }) {
                selectedId = accounts.firstOrNull { it.isDefault }?.id ?: accounts.firstOrNull()?.id
            }
            bindAccounts()
        }
    }

    private fun bindAccounts() {
        if (_binding == null) return
        val selected = accounts.firstOrNull { it.id == selectedId }
        if (selected == null) {
            binding.withdrawAccountTitle.text = "افزودن حساب"
            binding.withdrawAccountSub.text = "برای واریز، یک شبا ثبت کنید"
        } else {
            binding.withdrawAccountTitle.text = WalletWithdraw.accountTitle(selected)
            binding.withdrawAccountSub.text = if (selected.sheba.isNotBlank())
                WalletWithdraw.formatSheba(selected.sheba)
            else selected.maskedPan.ifBlank { "حساب بانکی" }
        }
        binding.withdrawAvailable.text = if (withdrawableKnown) {
            PartnerFormat.toman(withdrawable) + " تومان"
        } else {
            "—"
        }
        binding.withdrawSheetSubmit.text = when (step) {
            Step.REQUEST -> if (accounts.isEmpty()) "افزودن حساب" else "ثبت درخواست"
            Step.ADD -> "ذخیره حساب"
            Step.PICK -> "ثبت درخواست"
        }
        val list = binding.withdrawBankList
        list.removeAllViews()
        binding.withdrawBankEmpty.isVisible = accounts.isEmpty()
        for (account in accounts) {
            val row = ItemBankAccountBinding.inflate(layoutInflater, list, false)
            row.bankAccountTitle.text = WalletWithdraw.accountTitle(account)
            row.bankAccountSheba.text = if (account.sheba.isNotBlank())
                WalletWithdraw.formatSheba(account.sheba) else account.maskedPan
            val picked = account.id == selectedId
            row.bankAccountTitle.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (picked) R.color.vinor_accent_green else R.color.vinor_text,
                ),
            )
            row.bankAccountMain.setOnClickListener {
                pendingDeleteId = null
                selectedId = account.id
                showStep(Step.REQUEST)
            }
            row.bankAccountDelete.text = if (pendingDeleteId == account.id) "حذف شود؟" else "حذف"
            if (account.isDefault) {
                row.bankAccountDefault.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_text_muted))
                row.bankAccountDefault.setOnClickListener(null)
            } else {
                row.bankAccountDefault.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_accent_green))
                row.bankAccountDefault.setOnClickListener { postAccount(WalletWithdraw.BANK_DEFAULT, account.id) }
            }
            row.bankAccountDelete.setOnClickListener {
                if (pendingDeleteId == account.id) {
                    pendingDeleteId = null
                    postAccount(WalletWithdraw.BANK_DELETE, account.id)
                } else {
                    pendingDeleteId = account.id
                    bindAccounts()
                }
            }
            list.addView(row.root)
        }
    }

    private fun showStep(next: Step) {
        step = next
        binding.withdrawStepRequest.isVisible = next == Step.REQUEST
        binding.withdrawStepPick.isVisible = next == Step.PICK
        binding.withdrawStepAdd.isVisible = next == Step.ADD
        binding.withdrawSheetSubmit.isVisible = next != Step.PICK
        binding.withdrawSheetTitle.text = when (next) {
            Step.REQUEST -> "درخواست برداشت"
            Step.PICK -> "انتخاب حساب"
            Step.ADD -> "حساب جدید"
        }
        if (next == Step.ADD) showAddError(null)
        if (next == Step.REQUEST) showRequestError(null)
        bindAccounts()
    }

    private fun onSheetBack() {
        when (step) {
            Step.ADD -> showStep(if (addFromPick) Step.PICK else Step.REQUEST)
            Step.PICK -> showStep(Step.REQUEST)
            Step.REQUEST -> closeSheet()
        }
    }

    private fun closeSheet() {
        if (_binding == null) return
        binding.withdrawSheet.visibility = View.GONE
        step = Step.REQUEST
        busy = false
    }

    private fun onSheetSubmit() {
        if (busy) return
        when (step) {
            Step.ADD -> submitAdd()
            Step.REQUEST -> if (accounts.isEmpty()) {
                addFromPick = false
                showStep(Step.ADD)
            } else {
                submitWithdraw()
            }
            Step.PICK -> showStep(Step.REQUEST)
        }
    }

    private fun submitWithdraw() {
        if (!VinorNetworkStatus.isOnline(requireContext())) {
            // موجودی آفلاین ممکن است قدیمی باشد؛ برداشت فقط آنلاین
            showRequestError("برای ثبت برداشت به اینترنت وصل شوید.")
            return
        }
        val amountError = WalletWithdraw.amountError(
            binding.withdrawAmount.text?.toString().orEmpty(),
            if (withdrawableKnown) withdrawable else null,
            minWithdrawal,
        )
        if (amountError != null) {
            showRequestError(amountError)
            return
        }
        val account = accounts.firstOrNull { it.id == selectedId }
        if (account == null) {
            showRequestError("یک حساب شبا انتخاب کنید.")
            showStep(Step.PICK)
            return
        }
        val amount = WalletWithdraw.parseAmount(binding.withdrawAmount.text?.toString().orEmpty()) ?: return
        val body = WalletWithdraw.withdrawBody(amount, account.id, fields)
            .put("client_request_id", stableWithdrawRequestId("$amount|${account.id}"))
        postFinance(WalletWithdraw.REQUEST, body, requestError = true) { response ->
            withdrawRequestKey = null
            withdrawRequestId = null
            val pending = WalletWithdraw.parsePending(response)
            if (pending != null) showPending(pending)
            val posted = response.optString("message").trim().ifBlank { "درخواست برداشت ثبت شد." }
            Toast.makeText(requireContext(), posted, Toast.LENGTH_SHORT).show()
            closeSheet()
            loadData()
        }
    }

    private fun stableWithdrawRequestId(key: String): String {
        val existing = withdrawRequestId
        if (existing != null && withdrawRequestKey == key) return existing
        return UUID.randomUUID().toString().also {
            withdrawRequestKey = key
            withdrawRequestId = it
        }
    }

    private fun submitAdd() {
        val bank = binding.withdrawBankName.text?.toString().orEmpty()
        val key = WalletWithdraw.bankKey(bank, bankCatalog)
        val pan = PartnerApi.asciiDigits(binding.withdrawPan.text?.toString().orEmpty())
        val owner = binding.withdrawOwnerName.text?.toString().orEmpty()
        val sheba = binding.withdrawSheba.text?.toString().orEmpty()
        val error = if (key.isBlank()) "بانک معتبر انتخاب کنید." else null
            ?: if (pan.length != 16) "شماره کارت باید ۱۶ رقم باشد." else null
            ?: WalletWithdraw.ownerError(owner)
            ?: if (sheba.isNotBlank()) WalletWithdraw.shebaError(sheba) else null
        if (error != null) {
            showAddError(error)
            return
        }
        val body = WalletWithdraw.addBody(sheba, owner, key, fields, pan)
        postFinance(WalletWithdraw.BANK_ADD, body, requestError = false) {
            binding.withdrawBankName.setText("")
            binding.withdrawPan.setText("")
            binding.withdrawOwnerName.setText("")
            binding.withdrawSheba.setText("")
            Toast.makeText(requireContext(), "حساب ذخیره شد.", Toast.LENGTH_SHORT).show()
            showStep(Step.PICK)
            refreshWithdrawContext()
        }
    }

    private fun postAccount(path: String, accountId: String) {
        val body = if (path == WalletWithdraw.BANK_DEFAULT)
            WalletWithdraw.defaultBody(accountId, fields) else WalletWithdraw.idBody(accountId, fields)
        postFinance(path, body, requestError = false) {
            if (path == WalletWithdraw.BANK_DELETE && selectedId == accountId) selectedId = null
            if (path == WalletWithdraw.BANK_DEFAULT) selectedId = accountId
            refreshWithdrawContext()
        }
    }

    private fun postFinance(
        path: String,
        body: JSONObject,
        requestError: Boolean,
        onOk: (JSONObject) -> Unit,
    ) {
        val ctx = context ?: return
        if (!PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openLogin()
            return
        }
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                PartnerApi.postFinance(path, body, csrf, ctx)
            }
            if (_binding == null) return@launch
            setBusy(false)
            WalletWithdraw.csrfOf(response).takeIf { it.isNotBlank() }?.let { csrf = it }
            if (WalletWithdraw.isUnauthorized(response)) {
                closeSheet()
                (activity as? MainActivity)?.openLogin()
                return@launch
            }
            if (!WalletWithdraw.isOk(response)) {
                val message = WalletWithdraw.userMessage(response)
                if (requestError) showRequestError(message) else showAddError(message)
                if (step == Step.PICK) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            onOk(response)
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        if (_binding == null) return
        binding.withdrawSheetProgress.isVisible = value
        binding.withdrawSheetSubmit.isEnabled = !value
        binding.commissionsWithdrawBtn.isEnabled = !value
    }

    private fun showRequestError(text: String?) {
        if (_binding == null) return
        binding.withdrawRequestError.text = text.orEmpty()
        binding.withdrawRequestError.isVisible = !text.isNullOrBlank()
    }

    private fun showAddError(text: String?) {
        if (_binding == null) return
        binding.withdrawAddError.text = text.orEmpty()
        binding.withdrawAddError.isVisible = !text.isNullOrBlank()
    }

    private fun openCharge(prefill: Long = 0L) {
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = "مبلغ به تومان"
        if (prefill > 0L) input.setText(prefill.toString())
        AlertDialog.Builder(requireContext())
            .setTitle("شارژ کیف پول")
            .setMessage("۱۰۰٬۰۰۰ · ۵۰۰٬۰۰۰ · ۱٬۰۰۰٬۰۰۰ · ۵٬۰۰۰٬۰۰۰ یا مبلغ دلخواه")
            .setView(input)
            .setPositiveButton("پرداخت") { _, _ ->
                val amount = input.text?.toString()?.trim()?.toLongOrNull() ?: 0L
                if (amount < 1000L) {
                    Toast.makeText(requireContext(), "مبلغ شارژ خیلی کم است.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        PartnerApi.postFinance(
                            "/express/partner/wallet/charge",
                            JSONObject().put("amount", amount),
                            csrf,
                            requireContext(),
                        )
                    }
                    val url = res.optString("pay_url")
                    if (res.optBoolean("ok") && url.isNotBlank()) {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } else {
                        Toast.makeText(
                            requireContext(),
                            res.optString("error").ifBlank { "شروع پرداخت ممکن نشد." },
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    companion object {
        private const val TAG = "CommissionsFragment"
        private const val COMMISSIONS_DATA = "/express/partner/commissions/data"
        private var remembered: JSONObject? = null
        private var sessionStamp = 0

        fun clearRemembered() {
            remembered = null
            sessionStamp++
        }

        var pendingShortfall: Long = 0L
    }
}
