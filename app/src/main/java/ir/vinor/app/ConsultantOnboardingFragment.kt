package ir.vinor.app

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentConsultantOnboardingBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** فلو نیتیو بازاریاب وینور: پروفایل، مدارک، وضعیت، سه نکته، داشبورد خالی. */
class ConsultantOnboardingFragment : Fragment() {

    private var _binding: FragmentConsultantOnboardingBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)

    private var cities: List<ConsultantOnboarding.City> = emptyList()
    private var cityIds: List<String> = emptyList()
    private var national: LocalFile? = null
    private var license: LocalFile? = null
    private var pickSlot = "national_id"
    private var tipIndex = 0
    private var ignoreToggle = false
    private var latestStatus = ""

    private data class LocalFile(val uri: Uri, val name: String, val mime: String, val size: Long)

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || _binding == null) return@registerForActivityResult
        val file = describe(uri)
        val error = ConsultantOnboarding.validateDocument(pickSlot, file.name, file.mime, file.size)
        if (error != null) {
            showError(error)
            return@registerForActivityResult
        }
        clearError()
        if (pickSlot == "national_id") {
            national = file
            binding.consultantNationalName.text = file.name
        } else {
            license = file
            binding.consultantLicenseName.text = file.name
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        _binding = FragmentConsultantOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.consultantHeader.pageTitle.text = ConsultantOnboarding.UI_LABEL
        binding.consultantHeader.pageBack.setOnClickListener {
            if (!findNavController().navigateUp()) {
                (activity as? MainActivity)?.openPartnerHome()
            }
        }
        binding.consultantRefresh.setColorSchemeColors(Color.parseColor("#10B981"))
        binding.consultantRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1C1C1E"))
        binding.consultantRefresh.setOnRefreshListener { refreshFromServer() }
        binding.consultantProfileSubmit.setOnClickListener { submitProfile() }
        binding.consultantNationalBtn.setOnClickListener {
            pickSlot = "national_id"
            pickFile.launch("*/*")
        }
        binding.consultantLicenseBtn.setOnClickListener {
            pickSlot = "license"
            pickFile.launch("*/*")
        }
        binding.consultantDocumentsSubmit.setOnClickListener { submitDocuments() }
        binding.consultantTipNext.setOnClickListener { advanceTip() }
        binding.consultantOnline.setOnCheckedChangeListener { _, checked ->
            if (!ignoreToggle) setAvailability(checked)
        }
        if (!PartnerApi.hasSession()) {
            ConsultantResume.arm(requireContext())
            (activity as? MainActivity)?.openLogin()
            return
        }
        startApply()
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun startApply() {
        val ctx = context ?: return
        showProgress(true)
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { ConsultantApi.apply(ctx) }
                if (_binding == null) return@launch
                if (handleAuth(resp)) return@launch
                if (!resp.optBoolean("ok", resp.optInt("http_status") in 200..299)) {
                    showError(ConsultantOnboarding.userMessage(resp))
                }
                route(resp)
            } catch (e: Exception) {
                if (_binding != null) showError(e.message ?: "خطای شبکه")
            } finally {
                if (_binding != null) showProgress(false)
            }
        }
    }

    private fun refreshFromServer() {
        val ctx = context ?: return
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { ConsultantApi.status(ctx) }
                if (_binding == null) return@launch
                if (handleAuth(resp)) return@launch
                val missing = resp.optInt("http_status") == 404
                if (missing) {
                    startApply()
                    return@launch
                }
                if (resp.optBoolean("ok") || resp.optString("status").isNotBlank()) {
                    clearError()
                    route(resp)
                } else {
                    showError(ConsultantOnboarding.userMessage(resp))
                }
            } catch (e: Exception) {
                if (_binding != null) showError(e.message ?: "خطای شبکه")
            } finally {
                if (_binding != null) binding.consultantRefresh.isRefreshing = false
            }
        }
    }

    private fun route(obj: JSONObject) {
        if (obj.optString("status").isNotBlank()) latestStatus = obj.optString("status")
        val seen = ConsultantOnboarding.slidesSeen(
            obj.optBoolean("onboarding_slides_seen"),
            context?.let { ConsultantResume.slidesSeen(it) } == true,
        )
        val screen = ConsultantOnboarding.resolveScreen(obj.optString("next_step"), latestStatus, seen)
        show(screen, obj)
    }

    private fun show(screen: ConsultantOnboarding.Screen, obj: JSONObject) {
        if (_binding == null) return
        binding.consultantStep.text = ConsultantOnboarding.stepCaption(screen, tipIndex)
        binding.consultantProfile.isVisible = screen == ConsultantOnboarding.Screen.PROFILE
        binding.consultantDocuments.isVisible = screen == ConsultantOnboarding.Screen.DOCUMENTS
        binding.consultantStatus.isVisible = screen == ConsultantOnboarding.Screen.STATUS
        binding.consultantTips.isVisible = screen == ConsultantOnboarding.Screen.TIPS
        binding.consultantDashboard.isVisible = screen == ConsultantOnboarding.Screen.DASHBOARD
        when (screen) {
            ConsultantOnboarding.Screen.PROFILE -> ensureCities()
            ConsultantOnboarding.Screen.STATUS -> bindStatus(obj)
            ConsultantOnboarding.Screen.TIPS -> bindTip()
            ConsultantOnboarding.Screen.DASHBOARD -> bindDashboard(obj)
            ConsultantOnboarding.Screen.DOCUMENTS -> Unit
        }
    }

    private fun ensureCities() {
        if (cities.isNotEmpty()) return
        val ctx = context ?: return
        scope.launch {
            val payload = try {
                withContext(Dispatchers.IO) { ConsultantApi.cities(ctx) }
            } catch (_: Exception) {
                null
            }
            if (_binding == null) return@launch
            val parsed = ConsultantOnboarding.parseCities(payload)
            if (parsed.isEmpty()) {
                showError("فهرست شهرها دریافت نشد.")
            }
            cities = parsed
            val labels = ArrayList<String>()
            val ids = ArrayList<String>()
            labels.add("شهر (تهران و حومه)")
            ids.add("")
            parsed.forEach {
                labels.add(it.label)
                ids.add(it.id)
            }
            cityIds = ids
            val adapter = ArrayAdapter(requireContext(), R.layout.item_spinner_dark, labels)
            binding.consultantCity.adapter = adapter
            binding.consultantCity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {}
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun submitProfile() {
        val ctx = context ?: return
        if (!PartnerApi.hasSession()) {
            ConsultantResume.arm(ctx)
            (activity as? MainActivity)?.openLogin()
            return
        }
        val cityId = cityIds.getOrNull(binding.consultantCity.selectedItemPosition).orEmpty()
        val specs = ArrayList<String>()
        if (binding.consultantBuy.isChecked) specs.add("buy")
        if (binding.consultantSell.isChecked) specs.add("sell")
        if (cities.isEmpty()) {
            showError("فهرست شهرها دریافت نشد.")
            return
        }
        when (val draft = ConsultantOnboarding.profileBody(
            binding.consultantName.text?.toString().orEmpty(),
            cityId,
            specs,
            cities,
        )) {
            is ConsultantOnboarding.Draft.Invalid -> showError(draft.error.message)
            is ConsultantOnboarding.Draft.Ok -> {
                clearError()
                binding.consultantProfileSubmit.isEnabled = false
                scope.launch {
                    try {
                        val resp = withContext(Dispatchers.IO) { ConsultantApi.updateProfile(ctx, draft.json) }
                        if (_binding == null) return@launch
                        if (handleAuth(resp)) return@launch
                        if (resp.optBoolean("ok")) {
                            route(resp)
                        } else {
                            showError(ConsultantOnboarding.userMessage(resp))
                        }
                    } catch (e: Exception) {
                        if (_binding != null) showError(e.message ?: "خطای شبکه")
                    } finally {
                        if (_binding != null) binding.consultantProfileSubmit.isEnabled = true
                    }
                }
            }
        }
    }

    private fun submitDocuments() {
        val ctx = context ?: return
        val idFile = national
        val idError = ConsultantOnboarding.validateDocument(
            "national_id",
            idFile?.name.orEmpty(),
            idFile?.mime.orEmpty(),
            idFile?.size ?: -1L,
        )
        if (idError != null || idFile == null) {
            showError(idError ?: "کارت ملی الزامی است.")
            return
        }
        val permit = license
        if (permit != null) {
            val licenseError = ConsultantOnboarding.validateDocument("license", permit.name, permit.mime, permit.size)
            if (licenseError != null) {
                showError(licenseError)
                return
            }
        }
        clearError()
        binding.consultantDocumentsSubmit.isEnabled = false
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ConsultantApi.uploadDocuments(
                        ctx,
                        ConsultantApi.Part(idFile.uri, idFile.name, idFile.mime),
                        permit?.let { ConsultantApi.Part(it.uri, it.name, it.mime) },
                    )
                }
                if (_binding == null) return@launch
                if (handleAuth(resp)) return@launch
                if (resp.optBoolean("ok")) route(resp) else showError(ConsultantOnboarding.userMessage(resp))
            } catch (e: Exception) {
                if (_binding != null) showError(e.message ?: "خطای شبکه")
            } finally {
                if (_binding != null) binding.consultantDocumentsSubmit.isEnabled = true
            }
        }
    }

    private fun bindStatus(obj: JSONObject) {
        val status = obj.optString("status").ifBlank { latestStatus }
        binding.consultantStatusTitle.text = ConsultantOnboarding.statusHeadline(status)
        val reason = ConsultantOnboarding.rejectReason(obj)
        binding.consultantStatusBody.text = when {
            reason.isNotBlank() -> reason
            status == "pending" -> "پس از بررسی، نتیجه همین‌جا دیده می‌شود."
            else -> obj.optString("message")
        }
    }

    private fun bindTip() {
        val tip = ConsultantOnboarding.tips[tipIndex.coerceIn(0, ConsultantOnboarding.tips.lastIndex)]
        binding.consultantTipTitle.text = tip.title
        binding.consultantTipBody.text = tip.body
        binding.consultantTipNext.text = if (tipIndex >= ConsultantOnboarding.tips.lastIndex) "ورود به داشبورد" else "بعدی"
        binding.consultantStep.text = ConsultantOnboarding.stepCaption(ConsultantOnboarding.Screen.TIPS, tipIndex)
    }

    private fun advanceTip() {
        if (tipIndex < ConsultantOnboarding.tips.lastIndex) {
            tipIndex += 1
            bindTip()
            return
        }
        context?.let { ConsultantResume.markSlidesSeen(it) }
        show(
            ConsultantOnboarding.Screen.DASHBOARD,
            JSONObject().put("status", latestStatus).put("online", binding.consultantOnline.isChecked),
        )
    }

    private fun bindDashboard(obj: JSONObject) {
        ignoreToggle = true
        binding.consultantOnline.isChecked = obj.optBoolean("online")
        ignoreToggle = false
    }

    private fun setAvailability(online: Boolean) {
        val ctx = context ?: return
        if (!ConsultantOnboarding.canSetAvailability(latestStatus)) {
            ignoreToggle = true
            binding.consultantOnline.isChecked = !online
            ignoreToggle = false
            showError("پس از تأیید حساب می‌توانید آمادهٔ دریافت لید شوید.")
            return
        }
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { ConsultantApi.availability(ctx, online) }
                if (_binding == null) return@launch
                if (handleAuth(resp)) return@launch
                if (!resp.optBoolean("ok")) {
                    ignoreToggle = true
                    binding.consultantOnline.isChecked = !online
                    ignoreToggle = false
                    showError(ConsultantOnboarding.userMessage(resp))
                } else {
                    clearError()
                }
            } catch (e: Exception) {
                if (_binding != null) {
                    ignoreToggle = true
                    binding.consultantOnline.isChecked = !online
                    ignoreToggle = false
                    showError(e.message ?: "خطای شبکه")
                }
            }
        }
    }

    private fun handleAuth(obj: JSONObject): Boolean {
        if (!ConsultantOnboarding.isUnauthorized(obj)) return false
        ConsultantResume.arm(requireContext())
        showError(ConsultantOnboarding.userMessage(obj))
        (activity as? MainActivity)?.openLogin()
        return true
    }

    private fun describe(uri: Uri): LocalFile {
        val resolver = requireContext().contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        val mime = resolver.getType(uri).orEmpty()
        return LocalFile(uri, name, mime, size)
    }

    private fun showProgress(on: Boolean) {
        binding.consultantProgress.isVisible = on
        if (!on) binding.consultantRefresh.isRefreshing = false
    }

    private fun showError(text: String) {
        binding.consultantError.isVisible = text.isNotBlank()
        binding.consultantError.text = text
        if (text.isNotBlank() && isAdded) {
            Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearError() {
        binding.consultantError.isVisible = false
        binding.consultantError.text = ""
    }
}
