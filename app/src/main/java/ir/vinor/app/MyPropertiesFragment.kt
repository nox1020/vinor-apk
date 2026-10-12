package ir.vinor.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentMyPropertyBinding
import ir.vinor.app.databinding.ItemLandCardBinding
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
    private var skipNextResumeLoad = true
    private var loadGen = 0
    private var rendered = false

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
        binding.mpHeader.pageTitle.text = "آگهی‌های من"
        binding.mpHeader.pageBack.setOnClickListener {
            (activity as? MainActivity)?.returnToVinorMan() ?: findNavController().navigateUp()
        }
        val add = ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_add)
            setBackgroundResource(R.drawable.bg_header_orb)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.vinor_text),
            )
            contentDescription = "ثبت ملک"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { (activity as? MainActivity)?.openOwnerHomeForm() }
        }
        binding.mpHeader.pageHeaderEnd.removeAllViews()
        binding.mpHeader.pageHeaderEnd.addView(
            add,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        binding.mpRefresh.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.vinor_primary_red))
        binding.mpRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.vinor_raised),
        )
        binding.mpRefresh.setOnRefreshListener { load(showSpinner = true) }
        binding.mpEmptyCta.setOnClickListener {
            (activity as? MainActivity)?.openOwnerHomeForm()
        }
        remembered?.let {
            bind(it)
            rendered = true
        }
        load(showSpinner = !rendered)
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
        if (_binding != null && PartnerApi.hasSession()) load(showSpinner = !rendered)
    }

    private fun load(showSpinner: Boolean) {
        val ctx = context ?: return
        if (!PartnerApi.hasSession()) {
            binding.mpRefresh.isRefreshing = false
            ListingMemory.clearSession()
            (activity as? MainActivity)?.openLogin()
            return
        }
        if (!rendered || showSpinner) binding.mpRefresh.isRefreshing = true
        val gen = ++loadGen
        val stamp = sessionStamp
        scope.launch {
            val wasFresh = withContext(Dispatchers.IO) { PartnerApi.isFresh(PATH, ctx) }
            try {
                val fresh = PartnerApi.loadDisplay(PATH, ctx) { data ->
                    if (_binding == null || gen != loadGen || stamp != sessionStamp) return@loadDisplay
                    if (!acceptable(data)) return@loadDisplay
                    bind(data)
                    rendered = true
                }
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                if (VinorPageLoad.needsLogin(fresh)) {
                    ListingMemory.clearSession()
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (acceptable(fresh)) {
                    bind(fresh)
                    rendered = true
                    remember(fresh)
                }
                if (rendered && VinorPageLoad.networkFailed(wasFresh, fresh)) {
                    VinorPageLoad.showOffline(binding.root) { load(showSpinner = true) }
                } else if (!rendered) {
                    VinorPageLoad.showOffline(binding.root) { load(showSpinner = true) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                VinorPageLoad.showOffline(binding.root) { load(showSpinner = true) }
            } finally {
                if (_binding != null && gen == loadGen) binding.mpRefresh.isRefreshing = false
            }
        }
    }

    private fun acceptable(obj: JSONObject): Boolean {
        if (VinorPageLoad.needsLogin(obj) || obj.optInt("http_status") == 403) return false
        if (obj.optBoolean("offline") || obj.optBoolean("non_json")) return false
        val code = if (obj.has("http_status")) obj.optInt("http_status") else 200
        return code in 200..299
    }

    private fun bind(obj: JSONObject) {
        val items = obj.optJSONArray("items") ?: obj.optJSONArray("listings") ?: JSONArray()
        val has = items.length() > 0 || (
            obj.optBoolean("has_property", false) &&
                obj.optJSONObject("property") != null &&
                !(obj.optJSONObject("property")?.optBoolean("is_demo") ?: true)
            )
        if (items.length() == 0 && !has) {
            binding.mpContent.isVisible = false
            binding.mpEmpty.isVisible = true
            return
        }
        binding.mpEmpty.isVisible = false
        binding.mpContent.isVisible = true
        binding.mpList.removeAllViews()
        if (items.length() == 0) {
            val prop = obj.optJSONObject("property") ?: JSONObject()
            addCard(
                JSONObject()
                    .put("code", prop.optString("code").ifBlank { prop.optString("id") })
                    .put("title", prop.optString("address"))
                    .put("city", prop.optString("city"))
                    .put("price_total", prop.optLong("estimated_value"))
                    .put("image_url", prop.optString("cover_image"))
                    .put("badge_label", prop.optString("badge_label")),
            )
            return
        }
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            addCard(row)
        }
    }

    private fun addCard(land: JSONObject) {
        val card = ItemLandCardBinding.inflate(layoutInflater, binding.mpList, false)
        card.landCardTitle.text = land.optString("title").ifBlank { "ملک شما" }
        val price = land.optLong("price_total")
        card.landCardPrice.text = if (price > 0L) PartnerFormat.toman(price) + " تومان" else ""
        val badge = land.optString("badge_label").ifBlank {
            if (land.optBoolean("published_for_sale")) "منتشرشده" else "خصوصی"
        }
        val meta = listOf(badge, land.optString("city"), land.optString("location"), land.optString("size"))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        card.landCardMeta.text = meta
        val comm = land.optInt("commission_amount")
        card.landCardCommission.isVisible = comm > 0 && land.optBoolean("published_for_sale")
        if (card.landCardCommission.isVisible) {
            card.landCardCommission.text = "پورسانت ${PartnerFormat.toman(comm)} تومان"
        }
        PartnerImages.bind(scope, card.landCardImage, land.optString("image_url"), card.landCardImagePlaceholder)
        val code = MyPropertyManage.codeOf(land)
        card.root.setOnClickListener {
            if (code.isNotBlank()) (activity as? MainActivity)?.openMyPropertyManage(land)
        }
        binding.mpList.addView(card.root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PATH = "/express/partner/api/my-property/data"
        private var remembered: JSONObject? = null
        private var sessionStamp = 0

        fun clearRemembered() {
            remembered = null
            sessionStamp++
        }

        internal fun remember(obj: JSONObject) {
            if (!VinorPageLoad.canRemember(obj)) return
            remembered = VinorPageLoad.rememberedCopy(obj)
        }

        internal fun peekRemembered(): JSONObject? = remembered
    }
}
