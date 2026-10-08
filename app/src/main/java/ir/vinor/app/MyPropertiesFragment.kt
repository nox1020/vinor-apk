package ir.vinor.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
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
    private var openedForm = false

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
        val add = ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_add)
            background = null
            imageTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.vinor_text),
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
        binding.mpRefresh.setColorSchemeColors(Color.parseColor("#10B981"))
        binding.mpRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1C1C1E"))
        binding.mpRefresh.setOnRefreshListener { load(true) }
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
                if (cached != null && withContext(Dispatchers.IO) { PartnerApi.isFresh(PATH, ctx) }) {
                    _binding?.mpRefresh?.isRefreshing = false
                    return@launch
                }
            }
            try {
                val fresh = withContext(Dispatchers.IO) { PartnerApi.get(PATH, ctx) }
                if (_binding == null) return@launch
                if (fresh.optInt("http_status") == 401) {
                    Toast.makeText(ctx, "برای دیدن ملک من وارد شوید.", Toast.LENGTH_SHORT).show()
                    (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                bind(fresh, fromNetwork = true)
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
        val items = obj.optJSONArray("items") ?: obj.optJSONArray("listings") ?: JSONArray()
        val has = items.length() > 0 || (
            obj.optBoolean("has_property", false) &&
                obj.optJSONObject("property") != null &&
                !(obj.optJSONObject("property")?.optBoolean("is_demo") ?: true)
            )
        if (items.length() == 0 && !has) {
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
    }
}
