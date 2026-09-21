package ir.vinor.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentLandDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LandDetailFragment : Fragment() {

    private var _binding: FragmentLandDetailBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var publishing = false
    private var selectedPayment = "نقدی"
    private var listingCode = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        _binding = FragmentLandDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.landDetailBack.setOnClickListener { findNavController().navigateUp() }
        val code = arguments?.getString("code").orEmpty().trim()
        listingCode = code
        if (code.isBlank()) {
            binding.landDetailError.isVisible = true
            binding.landDetailError.text = "کد فایل نامعتبر است."
            return
        }
        val canFav = !code.startsWith("divar:", ignoreCase = true)
        binding.landDetailFav.isVisible = canFav
        if (canFav) {
            syncFav(code)
            binding.landDetailFav.setOnClickListener {
                VinorFavoritesStore.toggle(requireContext(), code)
                syncFav(code)
            }
        }
        load(code)
    }

    private fun syncFav(code: String) {
        val on = VinorFavoritesStore.contains(requireContext(), code)
        binding.landDetailFav.imageTintList =
            android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (on) "#FBBF24" else "#A1A1AA"),
            )
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun apiPath(code: String): String {
        return if (code.startsWith("divar:", ignoreCase = true)) {
            val token = code.substringAfter(':')
            "/express/partner/api/divar/${Uri.encode(token)}/data"
        } else {
            "/express/partner/api/lands/${Uri.encode(code)}/data"
        }
    }

    private fun load(code: String) {
        val ctx = context ?: return
        binding.landDetailProgress.isVisible = true
        scope.launch {
            try {
                val obj = PartnerApi.loadDisplay(apiPath(code), ctx) { data ->
                    if (_binding == null) return@loadDisplay
                    bindLand(code, data)
                }
                if (_binding == null) return@launch
                if (!obj.optBoolean("ok") && obj.optString("title").isBlank()) {
                    binding.landDetailError.isVisible = true
                    binding.landDetailError.text = obj.optString("error", "آگهی پیدا نشد.")
                }
            } catch (e: Exception) {
                if (_binding != null) {
                    binding.landDetailError.isVisible = true
                    binding.landDetailError.text = e.message ?: "خطا در بارگذاری"
                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (_binding != null) binding.landDetailProgress.isVisible = false
            }
        }
    }

    private fun bindLand(code: String, obj: JSONObject) {
        if (!obj.optBoolean("ok") && obj.optString("title").isBlank()) return
        binding.landDetailError.isVisible = false
        val source = obj.optString("source")
        val isDivar = source == "divar" || code.startsWith("divar:", ignoreCase = true)
        val badge = obj.optString("owner_badge")
        binding.landDetailBadge.isVisible = isDivar || badge.isNotBlank()
        binding.landDetailBadge.text = when {
            isDivar -> "دیوار"
            badge.isNotBlank() -> badge
            else -> "دیوار"
        }
        binding.landDetailTitle.text = obj.optString("title").ifBlank { "آگهی" }
        val priceText = obj.optString("price").trim()
        val priceToman = obj.optLong("price_toman")
        binding.landDetailPrice.text = when {
            priceText.isNotBlank() -> priceText
            priceToman > 0L -> PartnerFormat.toman(priceToman) + " تومان"
            else -> ""
        }
        val comm = obj.optInt("commission_amount")
        binding.landDetailCommission.isVisible = comm > 0
        if (comm > 0) {
            binding.landDetailCommission.text = "پورسانت ${PartnerFormat.toman(comm)} تومان"
        }
        val meta = obj.optString("meta").ifBlank {
            listOf(
                obj.optString("city"),
                obj.optString("location"),
                obj.optString("size"),
                obj.optString("category"),
                if (isDivar) "" else code,
            ).filter { it.isNotBlank() }.joinToString(" · ")
        }
        binding.landDetailMeta.text = meta
        binding.landDetailBody.text = obj.optString("description").ifBlank { obj.optString("body") }
        val cover = obj.optString("image_url").ifBlank {
            obj.optJSONArray("images")?.optString(0).orEmpty()
        }
        PartnerImages.bind(scope, binding.landDetailImage, cover)
        bindRows(obj.optJSONArray("rows") ?: JSONArray())
        val divarUrl = obj.optString("divar_url")
        val showDivar = obj.optBoolean("show_divar") || (isDivar && divarUrl.isNotBlank())
        binding.landDetailDivar.isVisible = showDivar
        if (showDivar) {
            binding.landDetailDivar.setOnClickListener {
                val url = divarUrl.ifBlank {
                    "https://divar.ir/v/${code.substringAfter(':')}"
                }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        bindPublish(code, obj)
    }

    private fun bindPublish(code: String, obj: JSONObject) {
        val canPublish = obj.optBoolean("can_publish")
        val published = obj.optBoolean("published_for_sale") && obj.optBoolean("is_mine")
        binding.landDetailPublish.isVisible = canPublish || published
        binding.landDetailPayRow.isVisible = canPublish
        binding.landDetailCommissionRow.isVisible = canPublish
        binding.landDetailSendAll.isVisible = canPublish
        if (!canPublish) {
            binding.landDetailPublishTitle.text = "برای فروش منتشر شده"
            binding.landDetailPublishHint.text = "این ملک در آگهی‌های وینور نمایش داده می‌شود."
            binding.landDetailPublishCta.isVisible = false
            return
        }
        binding.landDetailPublishTitle.text = "انتشار برای فروش"
        binding.landDetailPublishHint.text = "بعد از انتشار، این ملک در آگهی‌های وینور دیده می‌شود."
        binding.landDetailPublishCta.isVisible = true
        paintPayChips()
        binding.landDetailPayCash.setOnClickListener { selectedPayment = "نقدی"; paintPayChips() }
        binding.landDetailPayDeal.setOnClickListener { selectedPayment = "توافقی"; paintPayChips() }
        binding.landDetailPaySwap.setOnClickListener { selectedPayment = "معاوضه"; paintPayChips() }
        binding.landDetailPublishCta.setOnClickListener { publish(code) }
    }

    private fun paintPayChips() {
        fun paint(tv: TextView, on: Boolean) {
            tv.setBackgroundColor(Color.parseColor(if (on) "#10B981" else "#2F2F2F"))
            tv.setTextColor(Color.parseColor(if (on) "#052E16" else "#ECECEC"))
        }
        paint(binding.landDetailPayCash, selectedPayment == "نقدی")
        paint(binding.landDetailPayDeal, selectedPayment == "توافقی")
        paint(binding.landDetailPaySwap, selectedPayment == "معاوضه")
    }

    private fun publish(code: String) {
        val ctx = context ?: return
        if (publishing) return
        val pct = binding.landDetailCommissionPct.text?.toString()?.trim().orEmpty().ifBlank { "2" }
        val body = JSONObject()
            .put("code", code)
            .put("payment_method", selectedPayment)
            .put("commission_pct", pct)
            .put("auto_send_all_partners", binding.landDetailSendAll.isChecked)
        publishing = true
        binding.landDetailPublishCta.alpha = 0.6f
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/api/my-property/publish", body, ctx)
                }
                if (_binding == null) return@launch
                if (fresh.optBoolean("ok", false)) {
                    Toast.makeText(ctx, fresh.optString("message").ifBlank { "ملک برای فروش منتشر شد." }, Toast.LENGTH_SHORT).show()
                    load(code)
                } else {
                    Toast.makeText(ctx, fresh.optString("error").ifBlank { "انتشار ناموفق بود." }, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
            } finally {
                publishing = false
                _binding?.landDetailPublishCta?.alpha = 1f
            }
        }
    }

    private fun bindRows(rows: JSONArray) {
        binding.landDetailRows.removeAllViews()
        if (rows.length() <= 0) {
            binding.landDetailRows.isVisible = false
            return
        }
        binding.landDetailRows.isVisible = true
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8f,
            resources.displayMetrics,
        ).toInt()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val label = row.optString("label").trim()
            val value = row.optString("value").trim()
            if (label.isBlank() || value.isBlank()) continue
            val line = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, pad, 0, pad)
            }
            val keyView = TextView(requireContext()).apply {
                text = label
                setTextColor(Color.parseColor("#8E8E93"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valView = TextView(requireContext()).apply {
                text = value
                setTextColor(Color.parseColor("#ECECEC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
            }
            line.addView(keyView)
            line.addView(valView)
            binding.landDetailRows.addView(line)
        }
    }
}
