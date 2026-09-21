package ir.vinor.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentMyPropertyBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MyPropertiesFragment : Fragment() {

    private var _binding: FragmentMyPropertyBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var history = JSONArray()
    private var rangeMonths = 12
    private var skipNextResumeLoad = true
    private var openedForm = false
    private var selectedPayment = "نقدی"
    private var publishing = false
    private var propertyCode = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        skipNextResumeLoad = true
        _binding = FragmentMyPropertyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.mpHeader.pageTitle.text = "ملک من"
        binding.mpHeader.pageBack.setOnClickListener {
            if (!findNavController().navigateUp()) {
                (activity as? MainActivity)?.openPartnerHome()
            }
        }
        binding.mpRefresh.setColorSchemeColors(Color.parseColor("#10B981"))
        binding.mpRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1C1C1E"))
        binding.mpRefresh.setOnRefreshListener { load(true) }
        PartnerImages.roundClip(binding.mpCover, 16f)
        binding.mpEmptyCta.setOnClickListener {
            (activity as? MainActivity)?.openOwnerHomeForm()
        }
        load(false)
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResumeLoad) {
            skipNextResumeLoad = false
            return
        }
        if (_binding != null && PartnerApi.hasSession()) load(true)
    }

    private fun load(forceNet: Boolean) {
        val ctx = context ?: return
        if (!PartnerApi.hasSession()) {
            binding.mpRefresh.isRefreshing = false
            Toast.makeText(ctx, "برای دیدن ملک من وارد شوید.", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.openDrawer()
            return
        }
        scope.launch {
            if (!forceNet) {
                val cached = withContext(Dispatchers.IO) { PartnerApi.peekJson(PATH, ctx) }
                if (_binding != null && cached != null) bind(cached, fromNetwork = false)
                else _binding?.mpRefresh?.isRefreshing = true
            }
            try {
                val fresh = withContext(Dispatchers.IO) { PartnerApi.get(PATH, ctx) }
                if (_binding == null) return@launch
                if (fresh.optInt("http_status") == 401) {
                    Toast.makeText(ctx, "برای دیدن ملک من وارد شوید.", Toast.LENGTH_SHORT).show()
                    (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                bind(fresh)
            } catch (e: Exception) {
                if (_binding != null && !binding.mpContent.isVisible) {
                    Toast.makeText(ctx, e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
                }
            } finally {
                _binding?.mpRefresh?.isRefreshing = false
            }
        }
    }

    private fun bind(obj: JSONObject, fromNetwork: Boolean = true) {
        val prop = obj.optJSONObject("property")
        val has = obj.optBoolean("has_property", false) &&
            prop != null &&
            !prop.optBoolean("is_demo")
        if (!has || prop == null) {
            binding.mpContent.isVisible = false
            binding.mpEmpty.isVisible = true
            if (fromNetwork && !openedForm) {
                openedForm = true
                binding.root.post {
                    if (_binding == null) return@post
                    (activity as? MainActivity)?.openOwnerHomeForm()
                }
            }
            return
        }
        binding.mpEmpty.isVisible = false
        binding.mpContent.isVisible = true
        binding.mpAddress.text = prop.optString("address")
        val city = prop.optString("city")
        val state = prop.optString("state")
        val zip = prop.optString("zip")
        binding.mpCity.text = listOf(city, state, zip).filter { it.isNotBlank() }.joinToString("، ")
        binding.mpMeta.text = "${fa(prop.optInt("beds"))} تخت · ${fa(prop.optInt("baths"))} حمام · ${fa(prop.optInt("sqm"))} متر مربع · ساخت ${fa(prop.optInt("built_year"))}"
        binding.mpGalleryCount.text = "${fa(prop.optInt("gallery_count"))} عکس"
        binding.mpClaimed.isVisible = true
        binding.mpClaimed.text = prop.optString("badge_label").ifBlank { "خصوصی" }
        binding.mpDemo.isVisible = true
        binding.mpDemo.text = prop.optString("privacy_note").ifBlank {
            if (prop.optBoolean("published_for_sale")) {
                "این ملک برای فروش در آگهی‌های وینور منتشر شده است"
            } else {
                "فقط شما این قیمت را می‌بینید · برای فروش هنوز منتشر نشده"
            }
        }
        PartnerImages.bind(scope, binding.mpCover, prop.optString("cover_image"))
        binding.mpValue.text = PartnerFormat.toman(prop.optLong("estimated_value")) + " تومان"
        val monthly = prop.optDouble("monthly_change_pct")
        val yearly = prop.optDouble("yearly_change_pct")
        val label = prop.optString("monthly_change_label").ifBlank { faPct(monthly) }
        val yearlyLabel = prop.optString("yearly_change_label").ifBlank { faPct(yearly) }
        binding.mpChange.text = (if (monthly >= 0) "↑ " else "↓ ") + label + "٪ نسبت به ماه گذشته"
        binding.mpChange.setTextColor(Color.parseColor(if (monthly >= 0) "#6EE7B7" else "#FDA4AF"))
        binding.mpPpm.text = PartnerFormat.toman(prop.optLong("price_per_sqm"))
        binding.mpYearly.text = yearlyLabel + "٪"
        binding.mpViews.text = PartnerFormat.toman(prop.optLong("neighborhood_avg"))
        binding.mpLegend.text = "خط‌چین: میانگین محله " + PartnerFormat.toman(prop.optLong("neighborhood_avg")) + " تومان"
        binding.mpTrend.text = "نسبت به ماه گذشته $label٪ و نسبت به سال گذشته $yearlyLabel٪ تغییر کرده است."
        history = prop.optJSONArray("price_history") ?: JSONArray()
        bindRangeTabs()
        drawChart()
        bindActivity(prop.optJSONArray("activity") ?: JSONArray())
        val act = activity as? MainActivity
        binding.mpEdit.setOnClickListener { act?.openOwnerHomeForm() }
        binding.mpPhotos.setOnClickListener { act?.openOwnerHomeForm() }
        propertyCode = prop.optString("code").ifBlank { prop.optString("id") }
        bindPublish(obj, prop)
        bindExtra(obj.optJSONArray("listings") ?: JSONArray())
    }

    private fun bindPublish(obj: JSONObject, prop: JSONObject) {
        val published = prop.optBoolean("published_for_sale") || obj.optBoolean("published_for_sale")
        binding.mpSellCard.isVisible = true
        binding.mpPayRow.isVisible = !published
        binding.mpCommissionRow.isVisible = !published
        binding.mpSendAll.isVisible = !published
        if (published) {
            binding.mpSellTitle.text = "برای فروش منتشر شده"
            binding.mpSellHint.text = "این ملک در آگهی‌های وینور نمایش داده می‌شود."
            binding.mpSellCta.text = "مشاهده آگهی"
            binding.mpSellCta.setOnClickListener {
                if (propertyCode.isNotBlank()) (activity as? MainActivity)?.openLand(propertyCode)
            }
            return
        }
        binding.mpSellTitle.text = "انتشار برای فروش"
        binding.mpSellHint.text = "بعد از انتشار، ملک در آگهی‌های وینور دیده می‌شود."
        binding.mpSellCta.text = "انتشار برای فروش"
        paintPayChips()
        binding.mpPayCash.setOnClickListener { selectedPayment = "نقدی"; paintPayChips() }
        binding.mpPayDeal.setOnClickListener { selectedPayment = "توافقی"; paintPayChips() }
        binding.mpPaySwap.setOnClickListener { selectedPayment = "معاوضه"; paintPayChips() }
        if (binding.mpCommission.text.isNullOrBlank()) {
            binding.mpCommission.setText(obj.optString("default_commission_pct").ifBlank { "2" })
        }
        binding.mpSellCta.setOnClickListener { publish() }
    }

    private fun paintPayChips() {
        fun paint(tv: TextView, on: Boolean) {
            tv.setBackgroundColor(Color.parseColor(if (on) "#10B981" else "#2F2F2F"))
            tv.setTextColor(Color.parseColor(if (on) "#052E16" else "#ECECEC"))
        }
        paint(binding.mpPayCash, selectedPayment == "نقدی")
        paint(binding.mpPayDeal, selectedPayment == "توافقی")
        paint(binding.mpPaySwap, selectedPayment == "معاوضه")
    }

    private fun publish() {
        val ctx = context ?: return
        if (publishing) return
        val pct = binding.mpCommission.text?.toString()?.trim().orEmpty().ifBlank { "2" }
        val body = JSONObject()
            .put("payment_method", selectedPayment)
            .put("commission_pct", pct)
            .put("auto_send_all_partners", binding.mpSendAll.isChecked)
        publishing = true
        binding.mpSellCta.alpha = 0.6f
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/api/my-property/publish", body, ctx)
                }
                if (_binding == null) return@launch
                if (fresh.optBoolean("ok", false)) {
                    Toast.makeText(ctx, fresh.optString("message").ifBlank { "ملک برای فروش منتشر شد." }, Toast.LENGTH_SHORT).show()
                    bind(fresh, fromNetwork = true)
                } else {
                    Toast.makeText(ctx, fresh.optString("error").ifBlank { "انتشار ناموفق بود." }, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
            } finally {
                publishing = false
                _binding?.mpSellCta?.alpha = 1f
            }
        }
    }

    private fun bindExtra(arr: JSONArray) {
        binding.mpExtraList.removeAllViews()
        binding.mpExtraBox.isVisible = arr.length() > 0
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val code = row.optString("code")
            val tv = TextView(requireContext())
            tv.setTextColor(Color.parseColor("#ECECEC"))
            tv.textSize = 13f
            tv.setPadding(0, dp(8), 0, dp(8))
            val city = row.optString("city")
            val title = row.optString("title")
            tv.text = buildString {
                append(title)
                if (city.isNotBlank()) append(" · ").append(city)
                append("\n").append(PartnerFormat.toman(row.optLong("price_total"))).append(" تومان")
            }
            tv.setOnClickListener {
                if (code.isNotBlank()) (activity as? MainActivity)?.openLand(code)
            }
            binding.mpExtraList.addView(tv)
        }
    }

    private fun bindRangeTabs() {
        val row = binding.mpRangeRow
        row.removeAllViews()
        listOf(1 to "۱ ماه", 6 to "۶ ماه", 12 to "۱ سال", 60 to "۵ سال").forEach { (months, title) ->
            val tv = TextView(requireContext())
            val lp = LinearLayout.LayoutParams(0, dp(32), 1f)
            lp.marginEnd = dp(4)
            tv.layoutParams = lp
            tv.gravity = android.view.Gravity.CENTER
            tv.text = title
            tv.textSize = 11f
            tv.setTextColor(if (months == rangeMonths) Color.parseColor("#052E16") else Color.parseColor("#A1A1AA"))
            tv.setBackgroundColor(if (months == rangeMonths) Color.parseColor("#10B981") else Color.parseColor("#2F2F2F"))
            tv.setOnClickListener {
                rangeMonths = months
                bindRangeTabs()
                drawChart()
            }
            row.addView(tv)
        }
    }

    private fun drawChart() {
        val start = (history.length() - rangeMonths).coerceAtLeast(0)
        val values = mutableListOf<Float>()
        val neigh = mutableListOf<Float>()
        for (i in start until history.length()) {
            val row = history.optJSONObject(i) ?: continue
            values.add(row.optDouble("value").toFloat())
            neigh.add(row.optDouble("neighborhood").toFloat())
        }
        binding.mpChart.setSeries(values, neigh)
    }

    private fun bindActivity(arr: JSONArray) {
        binding.mpActivity.removeAllViews()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val tv = TextView(requireContext())
            tv.setTextColor(Color.parseColor("#ECECEC"))
            tv.textSize = 13f
            tv.setPadding(0, dp(6), 0, dp(6))
            tv.text = "${row.optString("text")}\n${row.optString("date")}"
            binding.mpActivity.addView(tv)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fa(n: Int): String = PartnerFormat.toman(n.toLong())

    private fun faPct(n: Double): String = n.toString().replace(".", "٫").map { c ->
        if (c in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[c - '0'] else c
    }.joinToString("")

    companion object {
        private const val PATH = "/express/partner/api/my-property/data"
    }
}
