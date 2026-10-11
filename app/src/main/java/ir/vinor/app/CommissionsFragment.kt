package ir.vinor.app

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
    private var busy: Boolean = false
    private var step: Step = Step.REQUEST
    private var addFromPick: Boolean = false
    private var pendingDeleteId: String? = null

    private enum class Step { REQUEST, PICK, ADD }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
        loadData()
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
        binding.commissionsProgress.visibility = View.VISIBLE
        binding.commissionsList.visibility = View.VISIBLE
        binding.commissionsEmpty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val obj = PartnerApi.loadDisplay(COMMISSIONS_DATA, ctx) { data ->
                    if (_binding == null) return@loadDisplay
                    if (!WalletWithdraw.hasCommissionPayload(data)) return@loadDisplay
                    bindCommissionPayload(data)
                }
                if (_binding == null) return@launch
                if (PartnerApi.isSessionExpired(obj)) {
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (!WalletWithdraw.hasCommissionPayload(obj)) {
                    showError()
                    return@launch
                }
                if (WalletWithdraw.readBalance(obj) == null) {
                    val home = withContext(Dispatchers.IO) {
                        PartnerApi.peekJson(WalletWithdraw.HOME_DATA, ctx)
                            ?: PartnerApi.get(WalletWithdraw.HOME_DATA, ctx)
                    }
                    if (_binding == null) return@launch
                    WalletWithdraw.readBalance(home)?.let { setWithdrawable(it) }
                }
                WalletWithdraw.parsePending(obj)?.let { showPending(it) }
            } catch (e: Exception) {
                Log.e(TAG, "loadData error", e)
                if (_binding != null) showError()
            }
        }
    }

    private fun bindCommissionPayload(obj: JSONObject) {
        val root = WalletWithdraw.commissionRoot(obj)
        val total = WalletWithdraw.money(root, "total_commission")
        val pending = WalletWithdraw.money(root, "pending_commission")
        val paid = WalletWithdraw.money(root, "paid_commission")
        val soldCount = WalletWithdraw.money(root, "sold_count")
        val itemsArr = WalletWithdraw.commissionItems(obj)
        val items = mutableListOf<JSONObject>()
        for (i in 0 until itemsArr.length()) {
            itemsArr.optJSONObject(i)?.let { items.add(it) }
        }
        bind(total, pending, paid, soldCount, items)
        WalletWithdraw.readBalance(obj)?.let { setWithdrawable(it) }
        WalletWithdraw.csrfOf(obj).takeIf { it.isNotBlank() }?.let { csrf = it }
    }

    private fun setWithdrawable(amount: Long) {
        withdrawable = amount.coerceAtLeast(0L)
        withdrawableKnown = true
        val label = PartnerFormat.toman(withdrawable) + " تومان"
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

    private fun showError() {
        if (_binding == null) return
        binding.commissionsProgress.visibility = View.GONE
        context?.let { Toast.makeText(it, "خطا در بارگذاری پورسانت‌ها", Toast.LENGTH_SHORT).show() }
        binding.commissionsEmptyText.text = "خطا در بارگذاری پورسانت‌ها."
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
            itemBinding.itemCommissionLandCode.text = "کد فایل: ${c.optString("land_code", "—")}"
            itemBinding.itemCommissionDate.text = formatDate(c.optString("created_at"))
            val status = c.optString("status").trim().ifBlank { c.optString("commission_status").trim() }
                .ifBlank { "pending" }
            itemBinding.itemCommissionStatus.text = statusText(status)
            itemBinding.itemCommissionStatus.setTextColor(statusColor(status))
            val pct = c.optDouble("commission_pct", 0.0)
            itemBinding.itemCommissionPct.text = if (pct == pct.toLong().toDouble()) "${pct.toLong()}%" else "$pct%"
            val amount = WalletWithdraw.money(c, "commission_amount", "payout_amount", "amount", "commission")
            itemBinding.itemCommissionAmount.text = formatToman(amount) + " تومان"
            val saleAmount = WalletWithdraw.money(c, "sale_amount")
            if (saleAmount > 0) {
                itemBinding.itemCommissionSaleRow.visibility = View.VISIBLE
                itemBinding.itemCommissionSaleAmount.text = formatToman(saleAmount) + " تومان"
            } else {
                itemBinding.itemCommissionSaleRow.visibility = View.GONE
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
            val banks = withContext(Dispatchers.IO) { PartnerApi.get(WalletWithdraw.BANK_ACCOUNTS, ctx) }
            val html = withContext(Dispatchers.IO) { PartnerApi.getHtml(WalletWithdraw.COMMISSIONS_HTML, ctx) }
            val profile = withContext(Dispatchers.IO) { PartnerApi.get(WalletWithdraw.PROFILE_DATA, ctx) }
            if (_binding == null) return@launch
            setBusy(false)
            fields = WalletWithdraw.fieldsFromHtml(html)
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
            binding.withdrawAccountSub.text = WalletWithdraw.formatSheba(selected.sheba)
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
            row.bankAccountSheba.text = WalletWithdraw.formatSheba(account.sheba)
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
        val amountError = WalletWithdraw.amountError(
            binding.withdrawAmount.text?.toString().orEmpty(),
            if (withdrawableKnown) withdrawable else null,
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
        postFinance(WalletWithdraw.REQUEST, body, requestError = true) { response ->
            val pending = WalletWithdraw.parsePending(response)
            if (pending != null) showPending(pending)
            val posted = response.optString("message").trim().ifBlank { "درخواست برداشت ثبت شد." }
            Toast.makeText(requireContext(), posted, Toast.LENGTH_SHORT).show()
            closeSheet()
            loadData()
        }
    }

    private fun submitAdd() {
        val bank = binding.withdrawBankName.text?.toString().orEmpty()
        val owner = binding.withdrawOwnerName.text?.toString().orEmpty()
        val sheba = binding.withdrawSheba.text?.toString().orEmpty()
        val error = WalletWithdraw.bankError(bank)
            ?: WalletWithdraw.ownerError(owner)
            ?: WalletWithdraw.shebaError(sheba)
        if (error != null) {
            showAddError(error)
            return
        }
        val body = WalletWithdraw.addBody(sheba, owner, bank, fields)
        postFinance(WalletWithdraw.BANK_ADD, body, requestError = false) {
            binding.withdrawBankName.setText("")
            binding.withdrawOwnerName.setText("")
            binding.withdrawSheba.setText("")
            Toast.makeText(requireContext(), "حساب ذخیره شد.", Toast.LENGTH_SHORT).show()
            showStep(Step.PICK)
            refreshWithdrawContext()
        }
    }

    private fun postAccount(path: String, accountId: String) {
        val body = WalletWithdraw.idBody(accountId, fields)
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

    companion object {
        private const val TAG = "CommissionsFragment"
        private const val COMMISSIONS_DATA = "/express/partner/commissions/data"
    }
}
