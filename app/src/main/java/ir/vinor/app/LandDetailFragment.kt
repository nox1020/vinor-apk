package ir.vinor.app

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
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
    private var bodyExpanded = false
    private var galleryCount = 0
    private val galleryScrollListener = ViewTreeObserver.OnScrollChangedListener { updateGalleryCounter() }

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
        binding.landDetailGallery.viewTreeObserver.addOnScrollChangedListener(galleryScrollListener)
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
                ContextCompat.getColor(
                    requireContext(),
                    if (on) R.color.vinor_primary_red else R.color.vinor_text,
                ),
            )
    }

    override fun onDestroyView() {
        _binding?.landDetailGallery?.viewTreeObserver?.removeOnScrollChangedListener(galleryScrollListener)
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
                if (PartnerApi.isSessionExpired(obj)) {
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
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
        binding.landDetailPrice.isVisible = binding.landDetailPrice.text.isNotBlank()
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
        binding.landDetailMeta.isVisible = meta.isNotBlank()
        bindGallery(obj)
        bindBody(obj.optString("description").ifBlank { obj.optString("body") })
        bindRows(obj.optJSONArray("rows") ?: JSONArray())
        val divarUrl = obj.optString("divar_url")
        val showDivar = obj.optBoolean("show_divar") || (isDivar && divarUrl.isNotBlank())
        binding.landDetailSticky.isVisible = showDivar
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

    private fun bindGallery(obj: JSONObject) {
        val urls = mutableListOf<String>()
        val images = obj.optJSONArray("images")
        if (images != null) {
            for (i in 0 until images.length()) {
                val u = images.optString(i).trim()
                if (u.isNotBlank()) urls.add(u)
            }
        }
        if (urls.isEmpty()) {
            val cover = obj.optString("image_url").trim()
            if (cover.isNotBlank()) urls.add(cover)
        }
        galleryCount = urls.size
        binding.landDetailGalleryInner.removeAllViews()
        if (urls.isEmpty()) {
            binding.landDetailGallery.isVisible = false
            binding.landDetailImage.isVisible = true
            binding.landDetailImage.setImageDrawable(null)
            binding.landDetailImage.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.vinor_raised_2),
            )
            binding.landDetailCounter.isVisible = false
            return
        }
        binding.landDetailImage.isVisible = false
        binding.landDetailGallery.isVisible = true
        val width = resources.displayMetrics.widthPixels
        val height = binding.landDetailGallery.layoutParams.height
        urls.forEach { url ->
            val img = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(width, height)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.vinor_raised_2))
            }
            binding.landDetailGalleryInner.addView(img)
            PartnerImages.bind(scope, img, url)
        }
        binding.landDetailCounter.isVisible = urls.size > 1
        binding.landDetailGallery.post {
            binding.landDetailGallery.scrollTo(0, 0)
            updateGalleryCounter()
        }
    }

    private fun updateGalleryCounter() {
        val b = _binding ?: return
        if (galleryCount <= 1) {
            b.landDetailCounter.isVisible = false
            return
        }
        val hsv: HorizontalScrollView = b.landDetailGallery
        val page = hsv.width.coerceAtLeast(1)
        val idx = ((hsv.scrollX + page / 2) / page).coerceIn(0, galleryCount - 1) + 1
        b.landDetailCounter.isVisible = true
        b.landDetailCounter.text = "${PartnerFormat.digits(idx)} از ${PartnerFormat.digits(galleryCount)}"
    }

    private fun bindBody(raw: String) {
        val text = raw.trim()
        bodyExpanded = false
        binding.landDetailDescLabel.isVisible = text.isNotBlank()
        binding.landDetailBody.isVisible = text.isNotBlank()
        binding.landDetailBody.text = text
        if (text.isBlank()) {
            binding.landDetailMore.isVisible = false
            return
        }
        binding.landDetailBody.maxLines = 5
        binding.landDetailBody.ellipsize = android.text.TextUtils.TruncateAt.END
        binding.landDetailBody.post {
            val b = _binding ?: return@post
            val layout = b.landDetailBody.layout ?: return@post
            val needsMore = layout.lineCount >= 5 || b.landDetailBody.lineCount >= 5
            b.landDetailMore.isVisible = needsMore
            b.landDetailMore.text = "نمایش بیشتر"
            b.landDetailMore.setOnClickListener {
                bodyExpanded = !bodyExpanded
                if (bodyExpanded) {
                    b.landDetailBody.maxLines = Integer.MAX_VALUE
                    b.landDetailBody.ellipsize = null
                    b.landDetailMore.text = "نمایش کمتر"
                } else {
                    b.landDetailBody.maxLines = 5
                    b.landDetailBody.ellipsize = android.text.TextUtils.TruncateAt.END
                    b.landDetailMore.text = "نمایش بیشتر"
                }
            }
        }
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
            tv.setBackgroundResource(if (on) R.drawable.bg_ld_chip_on else R.drawable.bg_ld_chip)
            tv.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (on) R.color.vinor_bg else R.color.vinor_text,
                ),
            )
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
            10f,
            resources.displayMetrics,
        ).toInt()
        val muted = ContextCompat.getColor(requireContext(), R.color.vinor_text_muted)
        val text = ContextCompat.getColor(requireContext(), R.color.vinor_text)
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
                this.text = label
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valView = TextView(requireContext()).apply {
                this.text = value
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
            }
            line.addView(keyView)
            line.addView(valView)
            binding.landDetailRows.addView(line)
        }
    }
}
