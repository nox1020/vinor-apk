package ir.vinor.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import ir.vinor.app.databinding.FragmentSearchBinding
import ir.vinor.app.databinding.ItemAdsAllRowBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** تب جستجو — لیست ملک‌ها، جدا از صفحهٔ آگهی‌ها. */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var allAds = JSONArray()
    private var approved = false
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { bindVisibleImages() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.searchRefresh.setColorSchemeColors(Color.parseColor("#10B981"))
        binding.searchRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1C1C1E"))
        binding.searchRefresh.setOnRefreshListener { load(fromPull = true) }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                render(s?.toString().orEmpty())
            }
        })
        binding.searchScroll.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        load()
    }

    override fun onDestroyView() {
        runCatching { binding.searchScroll.viewTreeObserver.removeOnScrollChangedListener(scrollListener) }
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun load(fromPull: Boolean = false) {
        val b = _binding ?: return
        if (!fromPull && b.searchList.childCount == 0) b.searchProgress.isVisible = true
        val ctx = context ?: return
        scope.launch {
            val obj = try {
                withContext(Dispatchers.IO) { PartnerApi.get("/express/partner/home/data", ctx) }
            } catch (_: Exception) {
                JSONObject()
            }
            if (_binding == null) return@launch
            approved = obj.optBoolean("is_approved")
            allAds = obj.optJSONArray("divar_pro_ads") ?: JSONArray()
            binding.searchProgress.isVisible = false
            binding.searchRefresh.isRefreshing = false
            render(binding.searchInput.text?.toString().orEmpty())
        }
    }

    private fun render(query: String) {
        val b = _binding ?: return
        val q = norm(query)
        b.searchList.removeAllViews()
        var shown = 0
        for (i in 0 until allAds.length()) {
            val ad = allAds.optJSONObject(i) ?: continue
            val hay = norm(
                listOf(ad.optString("title"), ad.optString("price"), ad.optString("location"))
                    .joinToString(" "),
            )
            if (q.isNotEmpty() && !hay.contains(q)) continue
            val row = ItemAdsAllRowBinding.inflate(layoutInflater, b.searchList, false)
            row.adsAllTitle.text = ad.optString("title")
            row.adsAllPrice.text = ad.optString("price")
            row.adsAllPrice.isVisible = ad.optString("price").isNotBlank()
            val loc = ad.optString("location")
            row.adsAllLoc.text = when {
                loc.isBlank() -> ""
                loc.startsWith("در ") -> loc
                else -> "در $loc"
            }
            row.adsAllLoc.isVisible = loc.isNotBlank()
            val commission = ad.optInt("commission_amount")
            if (approved && commission > 0) {
                row.adsAllComm.isVisible = true
                row.adsAllComm.text = "پورسانت ${PartnerFormat.toman(commission)} تومان"
            } else {
                row.adsAllComm.isVisible = false
            }
            PartnerImages.roundClip(row.adsAllThumb, 8f)
            row.adsAllThumb.tag = ad.optString("image_url")
            row.root.setOnClickListener { openAd(ad) }
            b.searchList.addView(row.root)
            shown += 1
        }
        val emptyText = when {
            allAds.length() == 0 -> "ملکی برای نمایش نیست."
            shown == 0 -> "ملکی با این عبارت پیدا نشد."
            else -> ""
        }
        b.searchEmpty.isVisible = emptyText.isNotEmpty()
        b.searchEmpty.text = emptyText
        b.searchRefresh.isVisible = shown > 0 || allAds.length() > 0
        b.searchList.post { bindVisibleImages() }
    }

    private fun bindVisibleImages() {
        val b = _binding ?: return
        val extra = (80 * resources.displayMetrics.density).toInt()
        val vis = Rect()
        if (!b.searchScroll.getGlobalVisibleRect(vis)) return
        vis.inset(0, -extra)
        val childBox = Rect()
        for (i in 0 until b.searchList.childCount) {
            val child = b.searchList.getChildAt(i)
            val img = child.findViewById<ImageView>(R.id.adsAllThumb) ?: continue
            if (img.getTag(R.id.adsAllThumb) == true) continue
            if (!child.getGlobalVisibleRect(childBox) || !Rect.intersects(vis, childBox)) continue
            img.setTag(R.id.adsAllThumb, true)
            PartnerImages.bind(scope, img, img.tag as? String)
        }
    }

    private fun openAd(ad: JSONObject) {
        val token = ad.optString("token")
        val source = ad.optString("source")
        val url = ad.optString("url")
        when {
            source == "vinor" && token.isNotBlank() -> (activity as? MainActivity)?.openLand(token)
            source == "divar" && token.isNotBlank() -> (activity as? MainActivity)?.openLand("divar:$token")
            url.isNotBlank() -> openVinorOrExternal(url)
        }
    }

    private fun openVinorOrExternal(raw: String) {
        val abs = PartnerImages.resolve(raw)
        val uri = Uri.parse(abs)
        val host = (uri.host ?: "").lowercase().removePrefix("www.")
        if (host == "vinor.ir" || host.endsWith(".vinor.ir")) {
            (activity as? MainActivity)?.openPath(abs)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun norm(raw: String): String {
        val mapped = buildString(raw.length) {
            for (ch in raw.trim()) {
                append(
                    when (ch) {
                        'ي', 'ى' -> 'ی'
                        'ك' -> 'ک'
                        in '۰'..'۹' -> '0' + (ch - '۰')
                        in '٠'..'٩' -> '0' + (ch - '٠')
                        else -> ch
                    },
                )
            }
        }
        return mapped.lowercase()
    }
}
