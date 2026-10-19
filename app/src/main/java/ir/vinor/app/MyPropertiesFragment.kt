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
import org.json.JSONObject

class MyPropertiesFragment : Fragment() {

    private var _binding: FragmentMyPropertyBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var skipNextResumeLoad = true
    private var loadGen = 0
    private var rendered = false
    private var archiveStatus = ListingArchive.ACTIVE

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
        paintArchiveTabs(0, 0)
        peek(archiveStatus)?.let {
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
        val status = archiveStatus
        val path = ListingArchive.portfolioPath(status)
        scope.launch {
            val wasFresh = withContext(Dispatchers.IO) { PartnerApi.isFresh(path, ctx) }
            try {
                val fresh = PartnerApi.loadDisplay(path, ctx) { data ->
                    if (_binding == null || gen != loadGen || stamp != sessionStamp || status != archiveStatus) {
                        return@loadDisplay
                    }
                    if (!acceptable(data)) return@loadDisplay
                    bind(data)
                    rendered = true
                }
                if (_binding == null || gen != loadGen || stamp != sessionStamp || status != archiveStatus) return@launch
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
                if (_binding == null || gen != loadGen || stamp != sessionStamp || status != archiveStatus) return@launch
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
        val page = ListingArchive.page(obj, archiveStatus)
        paintArchiveTabs(page.activeCount, page.archivedCount)
        if (page.items.isEmpty()) {
            binding.mpContent.isVisible = false
            binding.mpEmpty.isVisible = true
            binding.mpEmptyTitle.text = page.emptyMessage
            binding.mpEmptyMessage.isVisible = page.showRegister
            binding.mpEmptyCta.isVisible = page.showRegister
            return
        }
        binding.mpEmpty.isVisible = false
        binding.mpContent.isVisible = true
        binding.mpList.removeAllViews()
        for (row in page.items) addCard(row)
    }

    private fun paintArchiveTabs(active: Int, archived: Int) {
        val b = _binding ?: return
        ArchiveChips.show(b.mpArchiveTabs, active, archived, archiveStatus) { next ->
            val status = ListingArchive.normalize(next)
            if (status == archiveStatus) return@show
            archiveStatus = status
            rendered = false
            peek(status)?.let {
                bind(it)
                rendered = true
            } ?: run {
                binding.mpList.removeAllViews()
                binding.mpContent.isVisible = false
                binding.mpEmpty.isVisible = false
                paintArchiveTabs(active, archived)
            }
            load(showSpinner = true)
        }
    }

    private fun addCard(land: JSONObject) {
        val card = ItemLandCardBinding.inflate(layoutInflater, binding.mpList, false)
        card.landCardTitle.text = land.optString("title").ifBlank { "ملک شما" }
        val price = land.optLong("price_total")
        card.landCardPrice.text = if (price > 0L) PartnerFormat.toman(price) + " تومان" else ""
        val badge = Listings.statusLabel(land)
        val meta = listOf(badge, land.optString("city"), land.optString("location"), land.optString("size"))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        card.landCardMeta.text = meta
        val comm = land.optInt("commission_amount")
        card.landCardCommission.isVisible = comm > 0 && Listings.published(land)
        if (card.landCardCommission.isVisible) {
            card.landCardCommission.text = "پورسانت ${PartnerFormat.toman(comm)} تومان"
        }
        PartnerImages.bind(scope, card.landCardImage, land.optString("image_url"), card.landCardImagePlaceholder)
        val code = MyPropertyManage.codeOf(land)
        val archived = ListingArchive.isArchived(land)
        card.landCardBadge.isVisible = archived
        card.landCardArchive.isVisible = CARD_ARCHIVE_ACTION
        card.root.setOnClickListener {
            if (code.isNotBlank()) (activity as? MainActivity)?.openMyPropertyManage(land)
        }
        binding.mpList.addView(card.root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** بایگانی از کارت آگهی‌های من برداشته شده؛ همان کار داخل مدیریت ملک مانده است. */
        internal const val CARD_ARCHIVE_ACTION = false

        private val remembered = HashMap<String, JSONObject>()
        private var sessionStamp = 0

        fun clearRemembered() {
            remembered.clear()
            sessionStamp++
        }

        internal fun remember(obj: JSONObject) {
            if (!VinorPageLoad.canRemember(obj)) return
            val copy = VinorPageLoad.rememberedCopy(obj)
            ListingRepository.ingestTree(copy)
            remembered[ListingArchive.statusOf(copy)] = copy
        }

        internal fun peekRemembered(): JSONObject? = remembered[ListingArchive.ACTIVE]

        internal fun peek(status: String): JSONObject? = remembered[ListingArchive.normalize(status)]
    }
}
