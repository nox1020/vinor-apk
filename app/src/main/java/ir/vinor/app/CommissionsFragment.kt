package ir.vinor.app

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentCommissionsBinding
import ir.vinor.app.databinding.ItemCommissionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.webkit.CookieManager
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * تب پورسانت - کاملاً نیتیو (بدون WebView).
 * داده را از API /express/partner/commissions/data با کوکی دریافت می‌کند.
 */
class CommissionsFragment : Fragment() {

    private var _binding: FragmentCommissionsBinding? = null
    private val binding get() = _binding!!

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "CommissionsFragment"
        private const val BASE = "https://vinor.ir"
        private const val COMMISSIONS_DATA = "/express/partner/commissions/data"
    }

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
            if (!findNavController().navigateUp()) {
                (activity as? MainActivity)?.openPartnerHome()
            }
        }
        loadData()
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun cookieHeader(): String = CookieManager.getInstance().getCookie(BASE) ?: ""

    private fun formatToman(value: Int): String {
        if (value == 0) return "۰"
        val s = value.toString().reversed().chunked(3).joinToString("،").reversed()
        return toPersianDigits(s)
    }

    private fun toPersianDigits(s: String): String {
        val persian = "۰۱۲۳۴۵۶۷۸۹"
        return s.map { c -> if (c in '0'..'9') persian[c - '0'] else c }.joinToString("")
    }

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

    private fun statusText(status: String): String = when (status) {
        "paid" -> "پرداخت‌شده"
        "approved" -> "تایید شده"
        "rejected" -> "رد شده"
        else -> "در انتظار"
    }

    private fun statusColor(status: String): Int = when (status) {
        "paid" -> 0xFF16a34a.toInt()
        "approved" -> 0xFF2563EB.toInt()
        "rejected" -> 0xFFDC2626.toInt()
        else -> 0xFFD97706.toInt()
    }

    private fun loadData() {
        val ctx = context ?: return
        binding.commissionsProgress.visibility = View.VISIBLE
        binding.commissionsList.visibility = View.VISIBLE
        binding.commissionsEmpty.visibility = View.GONE

        scope.launch {
            try {
                val obj = PartnerApi.loadDisplay(COMMISSIONS_DATA, ctx) { data ->
                    if (_binding == null) return@loadDisplay
                    if (!data.optBoolean("success", false) && !data.has("total_commission")) return@loadDisplay
                    bindCommissionPayload(data)
                }
                if (_binding == null) return@launch
                if (!obj.optBoolean("success", false) && !obj.has("total_commission")) {
                    showError()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadData error", e)
                if (_binding != null) showError()
            }
        }
    }

    private fun bindCommissionPayload(obj: JSONObject) {
        val total = obj.optInt("total_commission", 0)
        val pending = obj.optInt("pending_commission", 0)
        val paid = obj.optInt("paid_commission", 0)
        val soldCount = obj.optInt("sold_count", 0)
        val itemsArr = obj.optJSONArray("items") ?: org.json.JSONArray()
        val items = mutableListOf<JSONObject>()
        for (i in 0 until itemsArr.length()) {
            items.add(itemsArr.getJSONObject(i))
        }
        bind(total, pending, paid, soldCount, items)
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
        total: Int,
        pending: Int,
        paid: Int,
        soldCount: Int,
        items: List<JSONObject>
    ) {
        if (_binding == null) return
        binding.commissionsProgress.visibility = View.GONE
        binding.commissionsTotal.text = formatToman(total) + " تومان"
        binding.commissionsPending.text = formatToman(pending) + " تومان"
        binding.commissionsPaid.text = formatToman(paid) + " تومان"
        binding.commissionsSoldCount.text = toPersianDigits(soldCount.toString())

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
            val status = (c.optString("status", "pending")).trim()
            itemBinding.itemCommissionStatus.text = statusText(status)
            itemBinding.itemCommissionStatus.setTextColor(statusColor(status))
            val pct = c.optDouble("commission_pct", 0.0)
            itemBinding.itemCommissionPct.text = if (pct == pct.toLong().toDouble()) "${pct.toLong()}%" else "$pct%"
            val amount = (c.optInt("commission_amount", 0)).coerceAtLeast(0)
            itemBinding.itemCommissionAmount.text = formatToman(amount) + " تومان"
            val saleAmount = (c.optInt("sale_amount", 0)).coerceAtLeast(0)
            if (saleAmount > 0) {
                itemBinding.itemCommissionSaleRow.visibility = View.VISIBLE
                itemBinding.itemCommissionSaleAmount.text = formatToman(saleAmount) + " تومان"
            } else {
                itemBinding.itemCommissionSaleRow.visibility = View.GONE
            }
            list.addView(itemBinding.root)
        }
    }
}
