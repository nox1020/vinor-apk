package ir.vinor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentCustomersBinding
import ir.vinor.app.databinding.ItemCustomerDetailBinding
import ir.vinor.app.databinding.ItemCustomerFactBinding
import ir.vinor.app.databinding.ItemCustomerFieldBinding
import ir.vinor.app.databinding.ItemCustomerRowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * مشتریان نیتیو. داده همان HTML صفحهٔ `/express/partner/customers` است.
 */
class CustomersFragment : Fragment() {

    private var _binding: FragmentCustomersBinding? = null
    private val binding get() = _binding!!

    private var pageUrl: String = PartnerApi.BASE + PartnerCrm.PATH
    private var page = PartnerCustomers.Page(
        rows = emptyList(),
        fields = emptyList(),
        tabs = emptyList(),
        search = null,
        emptyText = "",
        submitLabel = "ثبت مشتری",
        actionPath = PartnerCrm.PATH,
        canDelete = false,
        deleteIdKey = "",
    )
    private var csrf: String = ""
    private var busy: Boolean = false
    private var loadGen = 0
    private var rendered = false
    private var boundUrl = ""
    private var skipNextResume = true
    private var detail: PartnerCustomers.Row? = null
    private var pendingDelete: Boolean = false
    private val inputs = ArrayList<Pair<PartnerCustomers.Field, EditText>>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        skipNextResume = true
        _binding = FragmentCustomersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pageUrl = arguments?.getString("url").orEmpty().ifBlank { pageUrl }
        binding.customersBack.setOnClickListener { leaveOrCloseSheet() }
        binding.customersSheetBack.setOnClickListener { closeSheet() }
        binding.customersAdd.setOnClickListener { openCreate() }
        binding.customersSheetSubmit.setOnClickListener { onSheetSubmit() }
        binding.customersSheetDelete.setOnClickListener { onDelete() }
        binding.customersSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applySearch()
                true
            } else {
                false
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (_binding?.customersSheet?.isVisible == true) {
                        closeSheet()
                        return
                    }
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            },
        )
        if (!PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openLogin()
            runCatching { findNavController().navigateUp() }
            return
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResume) {
            skipNextResume = false
            return
        }
        if (_binding == null || !PartnerApi.hasSession()) return
        if (binding.customersSheet.isVisible) return
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        inputs.clear()
    }

    private fun leaveOrCloseSheet() {
        if (binding.customersSheet.isVisible) {
            closeSheet()
            return
        }
        if (!findNavController().navigateUp()) {
            (activity as? MainActivity)?.openPartnerHome()
        }
    }

    private fun load() {
        val ctx = context ?: return
        val url = pageUrl
        val gen = ++loadGen
        val stamp = sessionStamp
        // Like Home, paint the last snapshot synchronously, then revalidate in background.
        // Customer names/phones stay in session-scoped RAM, never in the disk API cache.
        val account = VinorCacheSession.fingerprint(ctx)
        val snap = remembered[url]?.takeIf { it.account == account }
        if (snap != null) {
            page = snap.page
            csrf = snap.csrf
            rendered = true
            boundUrl = url
            bindPage()
            binding.customersProgress.isVisible = false
        } else if (!rendered || boundUrl != url) {
            binding.customersList.removeAllViews()
            binding.customersProgress.isVisible = true
            binding.customersEmpty.isVisible = false
            rendered = false
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Fetch the HTML and the auth/CSRF fallback concurrently; no serial wait.
                val (doc, profile) = coroutineScope {
                    val document = async(Dispatchers.IO) { PartnerApi.getPage(url, ctx) }
                    val session = async(Dispatchers.IO) {
                        runCatching { PartnerApi.get("/express/partner/profile/data", ctx) }
                            .getOrElse { JSONObject() }
                    }
                    document.await() to session.await()
                }
                if (_binding == null || gen != loadGen || stamp != sessionStamp || pageUrl != url) return@launch
                binding.customersProgress.isVisible = false
                if (doc.code == 0) {
                    if (rendered && boundUrl == url) VinorPageLoad.showOffline(binding.root) { load() }
                    else showMessage(VinorPageLoad.OFFLINE)
                    return@launch
                }
                if (PartnerCustomers.isLoginDocument(doc.url, doc.body) || VinorPageLoad.needsLogin(profile)) {
                    clearRemembered()
                    (activity as? MainActivity)?.openLogin(sessionExpired = VinorPageLoad.needsLogin(profile))
                    return@launch
                }
                if (doc.code !in 200..299) {
                    if (rendered && boundUrl == url) {
                        VinorPageLoad.showOffline(binding.root) { load() }
                    } else {
                        val payload = try {
                            JSONObject(doc.body).put("http_status", doc.code)
                        } catch (_: Exception) {
                            JSONObject().put("http_status", doc.code)
                        }
                        showMessage(PartnerCustomers.userMessage(payload, VinorPageLoad.OFFLINE))
                    }
                    return@launch
                }
                val fromPage = PartnerCustomers.csrfFromHtml(doc.body)
                csrf = fromPage.ifBlank { profile.optString("csrf_token").trim() }
                page = PartnerCustomers.parse(doc.body)
                remembered[url] = CustomersMemory(page, csrf, account)
                rendered = true
                boundUrl = url
                bindPage()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                binding.customersProgress.isVisible = false
                if (rendered && boundUrl == url) VinorPageLoad.showOffline(binding.root) { load() }
                else showMessage(VinorPageLoad.OFFLINE)
            }
        }
    }

    private fun showMessage(text: String) {
        binding.customersList.removeAllViews()
        binding.customersAdd.isVisible = false
        binding.customersTabsScroll.isVisible = false
        binding.customersSearch.isVisible = false
        binding.customersEmpty.text = text
        binding.customersEmpty.isVisible = true
    }

    private fun bindPage() {
        bindTabs()
        bindSearch()
        binding.customersAddLabel.text = PartnerCustomers.addActionLabel(page.submitLabel)
        binding.customersAdd.isVisible = page.fields.isNotEmpty()
        binding.customersList.removeAllViews()
        binding.customersEmpty.isVisible = page.rows.isEmpty()
        binding.customersEmpty.text = page.emptyText
        val inflater = LayoutInflater.from(requireContext())
        for (row in page.rows) {
            val item = ItemCustomerRowBinding.inflate(inflater, binding.customersList, true)
            item.customerRowAvatar.text = row.title.trim().firstOrNull()?.toString().orEmpty()
            item.customerRowTitle.text = row.title
            item.customerRowStatus.isVisible = row.status.isNotBlank()
            item.customerRowStatus.text = row.status
            item.customerRowPhone.isVisible = row.phone.isNotBlank()
            item.customerRowPhone.text = row.phone
            item.customerRowNotes.isVisible = row.notes.isNotBlank()
            item.customerRowNotes.text = row.notes
            item.root.setOnClickListener { openDetail(row) }
        }
    }

    private fun bindTabs() {
        binding.customersTabs.removeAllViews()
        binding.customersTabsScroll.isVisible = page.tabs.isNotEmpty()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        for (tab in page.tabs) {
            val chip = TextView(requireContext())
            chip.text = tab.label
            chip.textSize = 14f
            chip.minHeight = dp(36)
            chip.gravity = android.view.Gravity.CENTER
            chip.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (tab.selected) R.color.vinor_on_primary else R.color.vinor_text_muted,
                ),
            )
            chip.setBackgroundResource(
                if (tab.selected) R.drawable.bg_follow_tab_on else R.drawable.bg_follow_tab,
            )
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.setOnClickListener {
                val next = absolute(tab.href)
                if (next != pageUrl) {
                    pageUrl = next
                    load()
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8)
            binding.customersTabs.addView(chip, lp)
        }
    }

    private fun bindSearch() {
        val search = page.search
        binding.customersSearch.isVisible = search != null
        if (search != null) binding.customersSearch.hint = search.placeholder
    }

    private fun applySearch() {
        val search = page.search ?: return
        val value = binding.customersSearch.text?.toString()?.trim().orEmpty()
        val base = Uri.parse(pageUrl)
        val next = base.buildUpon().clearQuery()
        for (name in base.queryParameterNames) {
            if (name == search.name) continue
            next.appendQueryParameter(name, base.getQueryParameter(name))
        }
        if (value.isNotEmpty()) next.appendQueryParameter(search.name, value)
        pageUrl = next.build().toString()
        load()
    }

    private fun openCreate() {
        detail = null
        pendingDelete = false
        inputs.clear()
        binding.customersSheetBody.removeAllViews()
        val addLabel = PartnerCustomers.addActionLabel(page.submitLabel)
        binding.customersSheetTitle.text = addLabel
        binding.customersSheetError.isVisible = false
        binding.customersSheetDelete.isVisible = false
        binding.customersSheetSubmit.isVisible = true
        binding.customersSheetSubmitLabel.text = addLabel
        binding.customersSheetSubmitIcon.setImageResource(R.drawable.ic_customer_plus)
        val inflater = LayoutInflater.from(requireContext())
        for (field in page.fields) {
            val item = ItemCustomerFieldBinding.inflate(inflater, binding.customersSheetBody, true)
            item.customerFieldLabel.text = field.label
            item.customerFieldInput.hint = if (field.phone) "0912…" else ""
            item.customerFieldInput.inputType = when {
                field.phone -> android.text.InputType.TYPE_CLASS_PHONE
                field.multiline -> android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }
            if (field.phone) item.customerFieldInput.textDirection = View.TEXT_DIRECTION_LTR
            if (field.multiline) item.customerFieldInput.minLines = 3
            inputs.add(field to item.customerFieldInput)
        }
        binding.customersSheet.isVisible = true
    }

    private fun openDetail(row: PartnerCustomers.Row) {
        detail = row
        pendingDelete = false
        inputs.clear()
        binding.customersSheetBody.removeAllViews()
        binding.customersSheetTitle.text = row.title
        binding.customersSheetError.isVisible = false
        binding.customersSheetDelete.isVisible = page.canDelete && row.id.isNotBlank()
        binding.customersSheetDelete.text = "حذف"
        val inflater = LayoutInflater.from(requireContext())
        val head = ItemCustomerDetailBinding.inflate(inflater, binding.customersSheetBody, true)
        head.customerDetailAvatar.text = row.title.trim().firstOrNull()?.toString().orEmpty()
        head.customerDetailName.text = row.title
        val facts = PartnerCustomers.detailFacts(row)
        head.customerDetailFacts.isVisible = facts.isNotEmpty()
        for (fact in facts) {
            val line = ItemCustomerFactBinding.inflate(inflater, head.customerDetailFacts, true)
            line.customerFactLabel.text = fact.label
            line.customerFactValue.text = fact.value
            if (fact.phone) line.customerFactValue.textDirection = View.TEXT_DIRECTION_LTR
        }
        binding.customersSheetSubmit.isVisible = row.phone.isNotBlank()
        binding.customersSheetSubmitLabel.text = "تماس"
        binding.customersSheetSubmitIcon.setImageResource(R.drawable.ic_customer_call)
        binding.customersSheet.isVisible = true
    }

    private fun closeSheet() {
        binding.customersSheet.isVisible = false
        pendingDelete = false
        detail = null
        inputs.clear()
    }

    private fun onSheetSubmit() {
        val row = detail
        if (row != null) {
            if (row.phone.isBlank()) return
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${row.phone}")))
            return
        }
        if (busy) return
        val values = HashMap<String, String>()
        for ((field, input) in inputs) {
            val raw = input.text?.toString().orEmpty()
            val error = PartnerCustomers.fieldError(field, raw)
            if (error != null) {
                binding.customersSheetError.text = error
                binding.customersSheetError.isVisible = true
                return
            }
            values[field.name] = raw
        }
        binding.customersSheetError.isVisible = false
        val body = PartnerCustomers.createBody(values, page.fields)
        post(page.actionPath, body, "مشتری اضافه شد.") {
            closeSheet()
            load()
        }
    }

    private fun onDelete() {
        val row = detail ?: return
        if (!pendingDelete) {
            pendingDelete = true
            binding.customersSheetDelete.text = "حذف شود؟"
            return
        }
        pendingDelete = false
        val body = PartnerCustomers.deleteBody(row.id, page.deleteIdKey)
        post(page.actionPath, body, "مشتری حذف شد.") {
            closeSheet()
            load()
        }
    }

    private fun post(path: String, body: JSONObject, fallback: String, onOk: () -> Unit) {
        val ctx = context ?: return
        if (busy) return
        busy = true
        binding.customersSheetProgress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = withContext(Dispatchers.IO) {
                PartnerApi.postPage(path, body, csrf, PartnerCrm.PATH, ctx)
            }
            if (_binding == null) return@launch
            busy = false
            binding.customersSheetProgress.isVisible = false
            val fresh = resp.optString("csrf_token").trim()
            if (fresh.isNotBlank()) csrf = fresh
            if (PartnerCustomers.isUnauthorized(resp)) {
                Toast.makeText(ctx, PartnerCustomers.userMessage(resp, fallback), Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.openLogin()
                return@launch
            }
            if (PartnerCustomers.isOk(resp)) {
                // All customer tabs/counts can be affected by a create/delete.
                remembered.clear()
                Toast.makeText(ctx, PartnerCustomers.userMessage(resp, fallback), Toast.LENGTH_SHORT).show()
                onOk()
            } else {
                binding.customersSheetError.text = PartnerCustomers.userMessage(resp, "ثبت ناموفق بود.")
                binding.customersSheetError.isVisible = true
            }
        }
    }

    private fun absolute(href: String): String {
        val trimmed = href.trim()
        return when {
            trimmed.startsWith("http") -> trimmed
            trimmed.startsWith("/") -> PartnerApi.BASE + trimmed
            else -> PartnerApi.BASE + PartnerCrm.PATH + "/" + trimmed
        }
    }

    private data class CustomersMemory(
        val page: PartnerCustomers.Page,
        val csrf: String,
        val account: String,
    )

    companion object {
        private val remembered = HashMap<String, CustomersMemory>()
        private var sessionStamp = 0

        fun clearRemembered() {
            remembered.clear()
            sessionStamp++
        }
    }
}
