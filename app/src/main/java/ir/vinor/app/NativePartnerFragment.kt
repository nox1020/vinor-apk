package ir.vinor.app

import android.text.Editable
import android.text.TextWatcher
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import ir.vinor.app.databinding.FragmentNativePartnerBinding
import ir.vinor.app.databinding.ItemHelpStepBinding
import ir.vinor.app.databinding.ItemLandCardBinding
import ir.vinor.app.databinding.ItemNativeCardBinding
import ir.vinor.app.databinding.ItemNativeRequestBinding
import ir.vinor.app.databinding.SheetFollowConfirmBinding
import ir.vinor.app.databinding.SheetFollowCounterBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** صفحات کشو و داشبورد — کاملاً نیتیو، بدون WebView. */
class NativePartnerFragment : Fragment() {

    private var _binding: FragmentNativePartnerBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var activeTab = ""
    private var requestFilter = FollowRequests.FILTER_ALL
    private var archiveStatus = ListingArchive.ACTIVE
    private val openHistory = HashSet<String>()
    private var followEpoch = 0
    private var followReload = false
    private var lastPayload = JSONObject()
    private var searchQuery = ""
    private var followBusy = false
    private var listingBusy = false
    private var followCsrf = ""
    private var followSheet: BottomSheetDialog? = null
    private var loadGen = 0
    private var pageRendered = false
    private var skipNextResume = true
    private var paintedGeneration = -1

    val page: String
        get() = arguments?.getString(ARG_PAGE).orEmpty().ifBlank { PAGE_TRAINING }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        skipNextResume = true
        _binding = FragmentNativePartnerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.nativeHeader.pageBack.setOnClickListener { goBack() }
        binding.nativeHeader.pageTitle.text = titleFor(page)
        if (page == PAGE_APPLY) {
            binding.root.post {
                val act = activity as? MainActivity
                if (!findNavController().popBackStack()) act?.openPartnerHome()
                act?.openConsultantOnboarding()
            }
            return
        }
        binding.nativeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.vinor_primary_red),
        )
        binding.nativeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.vinor_raised),
        )
        binding.nativeRefresh.setOnRefreshListener {
            if (page == PAGE_REQUESTS) followEpoch++
            load(pull = true)
        }
        binding.nativeApplySubmit.setOnClickListener { submitApply() }
        binding.nativeFavoritesCta.setOnClickListener {
            (activity as? MainActivity)?.openSearch()
        }
        binding.nativeInviteCopy.setOnClickListener { copyInvite() }
        binding.nativeInviteShare.setOnClickListener { shareInvite() }
        binding.nativeSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                if (page == PAGE_TRAINING && lastPayload.length() > 0) renderFaqs(lastPayload, activeTab)
            }
        })
        binding.nativeSearchBtn.setOnClickListener {
            searchQuery = binding.nativeSearch.text?.toString().orEmpty().trim()
            if (page == PAGE_TRAINING && lastPayload.length() > 0) renderFaqs(lastPayload, activeTab)
        }
        if (page == PAGE_REQUESTS && !PartnerApi.hasSession()) {
            showInlineLogin()
            return
        }
        ensureAccountScope()
        if (!dropStaleAccountPaint()) {
            // Favorites have a separate cards API and renderer, not bindPayload().
            if (page != PAGE_FAVORITES) {
                val privatePage = page !in PUBLIC_PAGES
                val memory = rememberedByPage[memoryKey()]
                val disk = currentDataPath()?.let { PartnerApi.peekJson(it, context) }
                val cached = memory ?: disk
                if (VinorPageLoad.canShowCached(cached, privatePage, PartnerApi.hasSession()) &&
                    cached != null && canPaint(cached)) {
                    bindPayload(cached)
                    pageRendered = true
                }
            }
            paintedGeneration = sessionStamp
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (page == PAGE_REQUESTS && !PartnerApi.hasSession()) {
            showInlineLogin()
            return
        }
        if (page == PAGE_REQUESTS && _binding?.nativeInlineLogin?.isVisible == true) {
            binding.nativeInlineLogin.isVisible = false
            binding.nativeInlineLogin.removeAllViews()
            binding.nativeRefresh.isVisible = true
            load()
            return
        }
        ensureAccountScope()
        if (dropStaleAccountPaint()) {
            skipNextResume = false
            if (page != PAGE_APPLY) load()
            return
        }
        if (skipNextResume) {
            skipNextResume = false
            return
        }
        if (_binding != null && page != PAGE_APPLY) load()
    }

    override fun onDestroyView() {
        followSheet?.dismiss()
        followSheet = null
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun goBack() {
        if (page == PAGE_SETTINGS || page == PAGE_FAVORITES) {
            (activity as? MainActivity)?.returnToVinorMan() ?: findNavController().navigateUp()
            return
        }
        if (!findNavController().navigateUp()) {
            (activity as? MainActivity)?.openPartnerHome()
        }
    }

    private fun setTitle(text: String) {
        binding.nativeHeader.pageTitle.text = text
    }

    /** Requests keep authentication inside their own tab, other pages retain navigation. */
    private fun requestTabLogin(sessionExpired: Boolean = false) {
        if (!showInlineLogin(sessionExpired)) {
            (activity as? MainActivity)?.openLogin(sessionExpired = sessionExpired)
        }
    }

    fun showInlineLogin(expired: Boolean = false): Boolean {
        if (page != PAGE_REQUESTS) return false
        val b = _binding ?: return false
        ++loadGen
        ++followEpoch
        pageRendered = false
        b.nativeProgress.isVisible = false
        b.nativeRefresh.isRefreshing = false
        b.nativeRefresh.isVisible = false
        b.nativeTabsScroll.isVisible = false
        b.nativeArchiveScroll.isVisible = false
        b.nativeInlineLogin.isVisible = true
        InlineTabLogin.show(this, b.nativeInlineLogin, "پیگیری", expired) {
            val current = _binding ?: return@show
            current.nativeInlineLogin.isVisible = false
            current.nativeInlineLogin.removeAllViews()
            current.nativeRefresh.isVisible = true
            load()
        }
        return true
    }

    private fun load(pull: Boolean = false) {
        if (page == PAGE_REQUESTS && !PartnerApi.hasSession()) {
            showInlineLogin()
            return
        }
        when (page) {
            PAGE_SETTINGS -> {
                binding.nativeRefresh.isRefreshing = false
                bindSettings()
                pageRendered = true
            }
            PAGE_FAVORITES -> loadFavorites(pull)
            else -> loadJson(pull)
        }
    }

    private fun loadJson(pull: Boolean) {
        val epoch = followEpoch
        val gen = ++loadGen
        val stamp = sessionStamp
        val ctx = context ?: run {
            finishFollowReload(epoch)
            return
        }
        val path = currentDataPath() ?: run {
            if (!pageRendered) bindEmpty("صفحه پیدا نشد.")
            finishFollowReload(epoch)
            return
        }
        if (pageRendered) {
            if (pull) binding.nativeRefresh.isRefreshing = true
        } else {
            binding.nativeProgress.isVisible = true
        }
        scope.launch {
            val wasFresh = withContext(Dispatchers.IO) { PartnerApi.isFresh(path, ctx) }
            try {
                var paintedByLoader = false
                val fresh = if (pull) withContext(Dispatchers.IO) {
                    PartnerApi.getFresh(path, ctx)
                } else PartnerApi.loadDisplay(path, ctx) { data ->
                    if (_binding == null || gen != loadGen || stamp != sessionStamp || !followCurrent(epoch)) {
                        return@loadDisplay
                    }
                    if (!canPaint(data)) return@loadDisplay
                    bindPayload(data)
                    paintedByLoader = true
                    pageRendered = true
                    binding.nativeProgress.isVisible = false
                }
                if (_binding == null || gen != loadGen || stamp != sessionStamp || !followCurrent(epoch)) return@launch
                val unauthorized = fresh.optString("error") == "unauthorized" ||
                    (page == PAGE_REQUESTS && FollowRequests.isUnauthorized(fresh))
                if (VinorPageLoad.needsLogin(fresh) || unauthorized) {
                    clearRemembered()
                    if (page == PAGE_REQUESTS) {
                        showInlineLogin(expired = VinorPageLoad.needsLogin(fresh))
                    } else {
                        if (!pageRendered) bindEmpty("برای ادامه وارد شوید.")
                        requestTabLogin(sessionExpired = VinorPageLoad.needsLogin(fresh))
                    }
                    return@launch
                }
                if (fresh.optInt("http_status") == 403) {
                    if (!pageRendered) bindEmpty(fresh.optString("error").ifBlank { "دسترسی ندارید." })
                    else VinorPageLoad.showOffline(binding.root) { load(pull = true) }
                    return@launch
                }
                if (page == PAGE_REQUESTS && !FollowRequests.isOk(fresh) && !fresh.optBoolean("from_cache")) {
                    if (!pageRendered) bindEmpty(FollowRequests.userMessage(fresh, "بارگذاری پیگیری ناموفق بود."))
                    else VinorPageLoad.showOffline(binding.root) { load(pull = true) }
                    return@launch
                }
                if (canPaint(fresh)) {
                    if (!paintedByLoader) bindPayload(fresh)
                    pageRendered = true
                    if (VinorPageLoad.canRemember(fresh)) {
                        rememberedByPage[memoryKey()] = VinorPageLoad.rememberedCopy(fresh)
                    }
                }
                if (pageRendered && VinorPageLoad.networkFailed(wasFresh, fresh)) {
                    VinorPageLoad.showOffline(binding.root) { load(pull = true) }
                } else if (!pageRendered) {
                    bindEmpty(VinorPageLoad.OFFLINE)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding == null || gen != loadGen || stamp != sessionStamp || !followCurrent(epoch)) return@launch
                if (pageRendered) VinorPageLoad.showOffline(binding.root) { load(pull = true) }
                else bindEmpty(VinorPageLoad.OFFLINE)
            } finally {
                if (_binding != null && followCurrent(epoch) && gen == loadGen) {
                    binding.nativeProgress.isVisible = false
                    binding.nativeRefresh.isRefreshing = false
                }
                finishFollowReload(epoch)
            }
        }
    }

    private fun canPaint(obj: JSONObject): Boolean {
        if (VinorPageLoad.needsLogin(obj)) return false
        if (obj.optInt("http_status") == 403) return false
        if (obj.optBoolean("offline") || obj.optBoolean("non_json")) return false
        if (obj.optString("error") == "unauthorized") return false
        if (page == PAGE_REQUESTS && !FollowRequests.isOk(obj)) return false
        val code = if (obj.has("http_status")) obj.optInt("http_status") else 200
        return code in 200..299
    }

    private fun followCurrent(epoch: Int): Boolean = page != PAGE_REQUESTS || epoch == followEpoch

    private fun finishFollowReload(epoch: Int) {
        if (page != PAGE_REQUESTS || epoch != followEpoch || !followReload) return
        followReload = false
        if (_binding != null) setFollowBusy(false) else followBusy = false
    }

    private fun loadFavorites(pull: Boolean) {
        val ctx = context ?: return
        if (!PartnerApi.hasSession()) {
            clearRemembered()
            bindEmpty("برای دیدن نشان‌ها وارد شوید.")
            binding.nativeRefresh.isRefreshing = false
            requestTabLogin()
            return
        }
        val codes = VinorFavoritesStore.codes(ctx)
        if (codes.isEmpty()) {
            rememberedByPage.remove(PAGE_FAVORITES)
            bindEmpty(FAVORITES_EMPTY)
            pageRendered = true
            binding.nativeRefresh.isRefreshing = false
            return
        }
        rememberedByPage[PAGE_FAVORITES]?.let { memory ->
            bindLandItems(memory.optJSONArray("items") ?: JSONArray(), emptyMsg = FAVORITES_EMPTY)
            pageRendered = true
        }
        if (pageRendered) {
            if (pull) binding.nativeRefresh.isRefreshing = true
        } else {
            binding.nativeProgress.isVisible = true
        }
        val gen = ++loadGen
        val stamp = sessionStamp
        scope.launch {
            try {
                val body = JSONObject().put("codes", JSONArray().also { arr ->
                    codes.forEach { arr.put(it) }
                })
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/api/favorites/cards", body, ctx)
                }
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                if (VinorPageLoad.needsLogin(resp)) {
                    clearRemembered()
                    requestTabLogin(sessionExpired = true)
                    return@launch
                }
                val live = VinorPageLoad.canRemember(resp)
                val hasCards = resp.optInt("http_status") in 200..299 || resp.has("items")
                if (live) rememberedByPage[PAGE_FAVORITES] = VinorPageLoad.rememberedCopy(resp)
                if (hasCards && (live || resp.has("items"))) {
                    bindLandItems(resp.optJSONArray("items") ?: JSONArray(), emptyMsg = FAVORITES_EMPTY)
                    pageRendered = true
                } else if (pageRendered) {
                    VinorPageLoad.showOffline(binding.root) { loadFavorites(pull = true) }
                } else {
                    bindEmpty(VinorPageLoad.OFFLINE)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                if (pageRendered) VinorPageLoad.showOffline(binding.root) { loadFavorites(pull = true) }
                else bindEmpty(VinorPageLoad.OFFLINE)
            } finally {
                if (_binding != null && gen == loadGen) {
                    binding.nativeProgress.isVisible = false
                    binding.nativeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun bindPayload(obj: JSONObject) {
        lastPayload = obj
        if (page == PAGE_REQUESTS) {
            val freshCsrf = obj.optString("csrf_token").trim()
            if (freshCsrf.isNotBlank()) followCsrf = freshCsrf
        }
        hideSpecials()
        binding.nativeEmpty.isVisible = false
        binding.nativeList.removeAllViews()
        when (page) {
            PAGE_TRAINING -> bindTraining(obj)
            PAGE_LEVELS -> bindLevels(obj)
            PAGE_INVITE -> bindInvite(obj)
            PAGE_APPLY -> bindApply(obj)
            PAGE_REQUESTS -> bindRequests(obj)
            PAGE_LISTINGS -> bindOwnerListings(obj)
            PAGE_TERMS, PAGE_PRIVACY -> bindLegal(obj)
            else -> bindEmpty("صفحه آماده نیست.")
        }
    }

    private fun bindSettings() {
        hideSpecials()
        binding.nativeEmpty.isVisible = false
        binding.nativeList.removeAllViews()
        addActionRow("خروج", danger = true) {
            VinorLogoutSheet.show(this) { finish ->
                (activity as? MainActivity)?.logoutFromDrawer(finish) ?: finish()
            }
        }
    }

    private fun bindTraining(obj: JSONObject) {
        binding.nativeHero.isVisible = true
        binding.nativeHeroTitle.text = obj.optString("hero_title").ifBlank { "چطور می‌تونم کمکت کنم؟" }
        binding.nativeHeroSub.text = obj.optString("hero_sub").ifBlank { "آموزش ۳ مرحله‌ای جذب مشتری، بازدید و بستن معامله." }
        binding.nativeSectionTitle.isVisible = true
        binding.nativeFaqTitle.isVisible = true
        binding.nativeSteps.isVisible = true
        renderTrainingSteps(obj)
        if (activeTab.isBlank()) activeTab = "market"
        renderFaqs(obj, activeTab)
    }

    private fun renderTrainingSteps(obj: JSONObject) {
        binding.nativeSteps.removeAllViews()
        val steps = obj.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val id = step.optString("id")
            val row = ItemHelpStepBinding.inflate(layoutInflater, binding.nativeSteps, false)
            row.helpStepNum.text = step.optString("num").ifBlank { "${i + 1}" }
            row.helpStepTitle.text = step.optString("title")
            row.helpStepSub.text = step.optString("sub")
            val on = id == activeTab || (activeTab.isBlank() && i == 0)
            row.root.setBackgroundResource(if (on) R.drawable.bg_help_step_active else R.drawable.bg_help_step)
            row.root.setOnClickListener {
                activeTab = id
                renderTrainingSteps(obj)
                renderFaqs(obj, id)
            }
            binding.nativeSteps.addView(row.root)
        }
    }

    private fun renderFaqs(obj: JSONObject, tab: String) {
        binding.nativeList.removeAllViews()
        val faqs = obj.optJSONArray("faqs") ?: JSONArray()
        val q = searchQuery
        var shown = 0
        for (i in 0 until faqs.length()) {
            val faq = faqs.optJSONObject(i) ?: continue
            if (tab.isNotBlank() && q.isBlank() && faq.optString("tab") != tab) continue
            val question = faq.optString("q")
            val answer = faq.optString("a")
            if (q.isNotBlank() && !question.contains(q, ignoreCase = true) && !answer.contains(q, ignoreCase = true)) {
                continue
            }
            addCard(question, answer, expanded = shown == 0)
            shown++
        }
        binding.nativeEmpty.isVisible = shown == 0
        if (shown == 0) binding.nativeEmpty.text = if (q.isBlank()) "موردی پیدا نشد." else "نتیجه‌ای برای این جستجو نیست."
    }

    private fun bindLevels(obj: JSONObject) {
        binding.nativeLeadBox.isVisible = true
        binding.nativeLead.text = obj.optString("lead")
        val current = obj.optString("current_level")
        val levels = obj.optJSONArray("levels") ?: JSONArray()
        for (i in 0 until levels.length()) {
            val lv = levels.optJSONObject(i) ?: continue
            val you = if (lv.optString("id") == current) "  · سطح شما" else ""
            val points = lv.optJSONArray("points") ?: JSONArray()
            val body = buildString {
                append(lv.optString("copy"))
                for (p in 0 until points.length()) {
                    append("\n• ").append(points.optString(p))
                }
            }
            addCard(lv.optString("title") + you, body, lv.optString("kicker"))
        }
        val approved = obj.optBoolean("is_approved")
        val special = obj.optBoolean("is_special")
        binding.nativeCta.isVisible = !approved || !special
        binding.nativeCta.text = if (!approved) ConsultantOnboarding.UI_LABEL else "ملک من"
        binding.nativeCta.setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            when {
                !approved -> act.openConsultantOnboarding()
                else -> act.openMyProperty()
            }
        }
    }

    private fun bindInvite(obj: JSONObject) {
        binding.nativeInviteBox.isVisible = true
        setTitle(InvitePage.TITLE)
        val approved = obj.optBoolean("is_approved")
        binding.nativeInviteIcon.setImageResource(
            if (approved) R.drawable.ic_person_add else R.drawable.ic_share_nodes,
        )
        binding.nativeInviteLead.text = InvitePage.lead(approved, obj.optInt("bonus_per_invite"))
        val url = InvitePage.linkOf(obj.optString("invite_url"), "")
        binding.nativeInviteUrl.text = if (url.isBlank()) InvitePage.EMPTY_LINK else url
        binding.nativeInviteUrl.setTextColor(
            requireContext().getColor(if (url.isBlank()) R.color.vinor_text_muted else R.color.vinor_text),
        )
        val earned = InvitePage.earnedText(approved, obj.optJSONObject("ref_stats"))
        binding.nativeInviteStat.isVisible = earned != null
        if (earned != null) binding.nativeInviteEarned.text = earned
    }

    private fun bindApply(obj: JSONObject) {
        when (obj.optString("status")) {
            "approved" -> {
                bindEmpty(obj.optString("message").ifBlank { "حساب همکاری فعال است." })
                binding.root.post { (activity as? MainActivity)?.openPartnerHome() }
            }
            "pending" -> {
                binding.nativeApplyForm.isVisible = false
                addCard(
                    "درخواست در صف بررسی",
                    obj.optString("message").ifBlank {
                        "درخواست شما ثبت شده است. پس از بررسی، همکاری فعال می‌شود."
                    },
                )
            }
            else -> {
                binding.nativeApplyForm.isVisible = true
                if (binding.nativeApplyFirst.text.isNullOrBlank()) {
                    binding.nativeApplyFirst.setText(obj.optString("first_name"))
                }
                if (binding.nativeApplyLast.text.isNullOrBlank()) {
                    binding.nativeApplyLast.setText(obj.optString("last_name"))
                }
                if (binding.nativeApplyNational.text.isNullOrBlank()) {
                    binding.nativeApplyNational.setText(obj.optString("national_id"))
                }
            }
        }
    }

    private fun bindRequests(obj: JSONObject) {
        binding.nativeArchiveScroll.isVisible = false
        val counts = FollowRequests.counters(obj, viewerPhone())
        val filters = FollowRequests.filters(counts)
        if (filters.none { it.id == requestFilter }) requestFilter = FollowRequests.FILTER_ALL
        val archivedCount = FollowRequests.archiveCounts(obj).archived
        bindRequestFilters(FollowRequests.requestChips(counts, archivedCount))
        renderRequestItems(obj, FollowRequests.listFilter(archiveStatus, requestFilter))
    }

    private fun showArchiveBar(active: Int, archived: Int) {
        binding.nativeArchiveScroll.isVisible = true
        ArchiveChips.show(binding.nativeArchiveTabs, active, archived, archiveStatus) { next ->
            val status = ListingArchive.normalize(next)
            if (status == archiveStatus) return@show
            archiveStatus = status
            pageRendered = false
            if (page == PAGE_REQUESTS) followEpoch++
            rememberedByPage[memoryKey()]?.let {
                bindPayload(it)
                pageRendered = true
            }
            binding.nativeRefresh.isRefreshing = true
            load(pull = true)
        }
    }

    /** RAM snapshots must never cross an account switch, even without an explicit logout. */
    private fun ensureAccountScope() {
        val ctx = context ?: return
        val account = VinorCacheSession.fingerprint(ctx)
        if (rememberedAccount != null && rememberedAccount != account) clearRemembered()
        rememberedAccount = account
    }

    private fun dropStaleAccountPaint(): Boolean {
        if (!AccountPaint.isStale(paintedGeneration, sessionStamp)) return false
        pageRendered = false
        lastPayload = JSONObject()
        followCsrf = ""
        if (_binding != null) {
            hideSpecials()
            binding.nativeList.removeAllViews()
        }
        paintedGeneration = sessionStamp
        return true
    }

    private fun viewerPhone(): String {
        val ctx = context ?: return ""
        return SessionAccount.captureFromCache(ctx)
    }

    private fun memoryKey(): String = when (page) {
        PAGE_REQUESTS, PAGE_LISTINGS -> "$page:$archiveStatus"
        else -> page
    }

    private fun currentDataPath(): String? = when (page) {
        PAGE_REQUESTS -> FollowRequests.dataPath(archiveStatus)
        PAGE_LISTINGS -> ListingArchive.listingsPath(archiveStatus)
        else -> dataPath(page)
    }

    private fun bindRequestFilters(filters: List<FollowRequests.Filter>) {
        binding.nativeTabsScroll.isVisible = filters.isNotEmpty()
        binding.nativeTabs.removeAllViews()
        val labels = filters.map { "${it.title} ${ListingWeek.persianDigits(it.count)}" }
        val sample = TextView(requireContext()).apply { textSize = 13f }
        val pad = dp(8) * 2
        val gap = dp(6)
        val needed = labels.sumOf { sample.paint.measureText(it).toInt() + pad } +
            gap * (filters.size - 1).coerceAtLeast(0)
        val available = resources.displayMetrics.widthPixels - dp(32)
        val equal = filters.isNotEmpty() && needed <= available
        binding.nativeTabsScroll.isFillViewport = equal
        binding.nativeTabs.layoutParams = binding.nativeTabs.layoutParams.apply {
            width = if (equal) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        filters.forEachIndexed { index, tab ->
            val on = FollowRequests.chipSelected(requestFilter, archiveStatus, tab.id)
            val chip = TextView(requireContext()).apply {
                text = labels[index]
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                minHeight = dp(36)
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (on) R.color.vinor_on_primary else R.color.vinor_text_muted,
                    ),
                )
                setBackgroundResource(if (on) R.drawable.bg_follow_tab_on else R.drawable.bg_follow_tab)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { onRequestChip(tab.id) }
            }
            val lp = if (equal) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            if (index < filters.lastIndex) lp.marginEnd = gap
            binding.nativeTabs.addView(chip, lp)
            if (on && !equal) {
                chip.post {
                    val scroller = binding.nativeTabsScroll
                    val target = (chip.left + chip.width / 2 - scroller.width / 2).coerceAtLeast(0)
                    scroller.smoothScrollTo(target, 0)
                }
            }
        }
    }

    private fun onRequestChip(chipId: String) {
        val (nextFilter, nextArchive) = FollowRequests.afterChip(requestFilter, archiveStatus, chipId)
        val archiveChanged = ListingArchive.normalize(nextArchive) != ListingArchive.normalize(archiveStatus)
        if (!archiveChanged && nextFilter == requestFilter) return
        requestFilter = nextFilter
        if (archiveChanged) {
            switchRequestArchive(nextArchive)
            return
        }
        bindRequests(lastPayload)
    }

    private fun switchRequestArchive(next: String) {
        val status = ListingArchive.normalize(next)
        if (status == archiveStatus) return
        archiveStatus = status
        pageRendered = false
        if (page == PAGE_REQUESTS) followEpoch++
        rememberedByPage[memoryKey()]?.let {
            bindPayload(it)
            pageRendered = true
        }
        binding.nativeRefresh.isRefreshing = true
        load(pull = true)
    }

    private fun renderRequestItems(obj: JSONObject, filter: String) {
        binding.nativeList.removeAllViews()
        binding.nativeEmpty.isVisible = false
        val seen = HashSet<String>()
        val rows = ArrayList<FollowRequests.Card>()
        for (row in FollowRequests.feed(obj, filter, viewerPhone())) {
            val card = FollowRequests.card(row, filter)
            if (card.id.isNotBlank() && !seen.add(card.id)) continue
            rows.add(card)
        }
        if (rows.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = FollowRequests.emptyText(archiveStatus, filter)
                setTextColor(ContextCompat.getColor(context, R.color.vinor_text_muted))
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(24), dp(32), dp(24), dp(32))
            }
            binding.nativeList.addView(
                empty,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            return
        }
        for (row in rows) addRequestCard(row)
    }

    private fun bindLegal(obj: JSONObject) {
        setTitle(obj.optString("title").ifBlank { titleFor(page) })
        val sections = obj.optJSONArray("sections") ?: JSONArray()
        for (i in 0 until sections.length()) {
            val s = sections.optJSONObject(i) ?: continue
            addCard(s.optString("heading"), s.optString("body"))
        }
        if (sections.length() == 0) bindEmpty("متن در دسترس نیست.")
    }

    private fun bindOwnerListings(obj: JSONObject) {
        val state = ListingArchive.page(obj, archiveStatus)
        showArchiveBar(state.activeCount, state.archivedCount)
        val rows = JSONArray()
        state.items.forEach { rows.put(it) }
        bindLandItems(rows, state.emptyMessage, ownerArchive = true)
    }

    private fun bindLandItems(items: JSONArray, emptyMsg: String, ownerArchive: Boolean = false) {
        if (!ownerArchive) hideSpecials()
        binding.nativeList.removeAllViews()
        if (items.length() == 0) {
            if (page == PAGE_FAVORITES) bindEmpty(FAVORITES_EMPTY) else bindEmpty(emptyMsg)
            return
        }
        binding.nativeFavoritesEmpty.isVisible = false
        binding.nativeEmpty.isVisible = false
        for (i in 0 until items.length()) {
            val land = items.optJSONObject(i) ?: continue
            val card = ItemLandCardBinding.inflate(layoutInflater, binding.nativeList, false)
            card.landCardTitle.text = land.optString("title")
            val price = land.optInt("price_total")
            card.landCardPrice.text = if (price > 0) PartnerFormat.toman(price) + " تومان" else ""
            val meta = listOf(land.optString("city"), land.optString("location"), land.optString("size"))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            card.landCardMeta.text = meta
            val comm = land.optInt("commission_amount")
            card.landCardCommission.isVisible = comm > 0
            if (comm > 0) card.landCardCommission.text = "پورسانت ${PartnerFormat.toman(comm)} تومان"
            PartnerImages.bind(scope, card.landCardImage, land.optString("image_url"), card.landCardImagePlaceholder)
            val code = land.optString("code").ifBlank { land.optString("id") }
            if (ownerArchive) {
                val archived = ListingArchive.isArchived(land)
                card.landCardBadge.isVisible = archived
                card.landCardArchive.isVisible = true
                card.landCardArchive.text = ListingArchive.actionLabel(archived)
                card.landCardArchive.contentDescription = card.landCardArchive.text
                card.landCardArchive.setOnClickListener { postListingArchive(land) }
            }
            card.root.setOnClickListener { (activity as? MainActivity)?.openLand(code) }
            binding.nativeList.addView(card.root)
        }
    }

    private fun addCard(title: String, body: String, kicker: String = "", expanded: Boolean = true) {
        val card = ItemNativeCardBinding.inflate(layoutInflater, binding.nativeList, false)
        card.nativeCardTitle.text = title
        card.nativeCardBody.text = body
        card.nativeCardBody.isVisible = body.isNotBlank() && expanded
        card.nativeCardKicker.isVisible = kicker.isNotBlank()
        card.nativeCardKicker.text = kicker
        if (body.isNotBlank()) {
            card.root.setOnClickListener {
                card.nativeCardBody.isVisible = !card.nativeCardBody.isVisible
            }
        }
        binding.nativeList.addView(card.root)
    }

    private fun addActionRow(title: String, danger: Boolean = false, onClick: () -> Unit) {
        val row = TextView(requireContext()).apply {
            text = title
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (danger) R.color.vinor_error else R.color.vinor_on_primary,
                ),
            )
            textSize = 15f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.bg_routine_card)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        binding.nativeList.addView(row, lp)
    }

    private fun bindRequestBadges(card: ItemNativeRequestBinding, item: FollowRequests.Card) {
        card.requestCardBadges.removeAllViews()
        val labels = (if (item.archived) listOf(ListingArchive.BADGE) else emptyList()) +
            listOf(item.stageLabel, item.directionLabel).filter { it.isNotBlank() } +
            if (item.revisitCount > 0) listOf("بازدید ${ListingWeek.persianDigits(item.revisitCount)}") else emptyList()
        labels.forEachIndexed { index, label ->
            val badge = TextView(requireContext()).apply {
                text = label
                textSize = 12f
                setTypeface(card.requestCardTitle.typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.vinor_text))
                setBackgroundResource(R.drawable.bg_follow_status)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            if (index > 0) lp.marginStart = dp(6)
            card.requestCardBadges.addView(badge, lp)
        }
    }

    private fun bindRequestHistory(card: ItemNativeRequestBinding, item: FollowRequests.Card) {
        val history = item.history
        card.requestCardHistory.removeAllViews()
        card.requestCardHistoryToggle.isVisible = history.isNotEmpty()
        if (history.isEmpty()) {
            card.requestCardHistory.isVisible = false
            return
        }
        card.requestCardHistoryToggle.text = "تاریخچه ${ListingWeek.persianDigits(history.size)}"
        val open = item.id.isNotBlank() && openHistory.contains(item.id)
        card.requestCardHistory.isVisible = open
        card.requestCardHistoryToggle.setOnClickListener {
            val show = !card.requestCardHistory.isVisible
            card.requestCardHistory.isVisible = show
            if (item.id.isBlank()) return@setOnClickListener
            if (show) openHistory.add(item.id) else openHistory.remove(item.id)
        }
        for (past in history) {
            val bits = listOf(past.whenText, past.status, past.outcome, past.notes, past.updatedAt)
                .filter { it.isNotBlank() }
            val line = TextView(requireContext()).apply {
                text = bits.joinToString(" · ")
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.vinor_text_muted))
                setPadding(0, dp(6), 0, 0)
            }
            card.requestCardHistory.addView(line)
        }
    }

    private fun addRequestCard(item: FollowRequests.Card) {
        val card = ItemNativeRequestBinding.inflate(layoutInflater, binding.nativeList, false)
        card.root.tag = item.id
        if (item.kind == "offer") {
            bindOfferCard(card, item)
            binding.nativeList.addView(card.root)
            return
        }
        bindRequestBadges(card, item)
        card.requestCardStatus.isVisible = item.status.isNotBlank()
        card.requestCardStatus.text = item.status
        card.requestCardStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when (FollowRequests.statusMark(item.status)) {
                    FollowRequests.Mark.GOOD -> R.color.vinor_accent_green
                    FollowRequests.Mark.BAD -> R.color.vinor_error
                    FollowRequests.Mark.NEUTRAL -> R.color.vinor_text_muted
                },
            ),
        )
        card.requestCardTitle.text = item.title
        val statusLp = card.requestCardStatus.layoutParams as ViewGroup.MarginLayoutParams
        statusLp.topMargin = if (card.requestCardBadges.childCount > 0) dp(8) else 0
        card.requestCardStatus.layoutParams = statusLp
        val titleLp = card.requestCardTitle.layoutParams as ViewGroup.MarginLayoutParams
        titleLp.topMargin = if (item.status.isNotBlank() || card.requestCardBadges.childCount > 0) dp(8) else 0
        card.requestCardTitle.layoutParams = titleLp
        val meta = listOf(item.whenText, item.phone).filter { it.isNotBlank() }.joinToString(" · ")
        val showPerson = item.customer.isNotBlank() || meta.isNotBlank()
        card.requestCardPerson.isVisible = showPerson
        card.requestCardAvatar.isVisible = item.customer.isNotBlank()
        card.requestCardAvatar.text = item.customer.trim().firstOrNull()?.toString().orEmpty()
        card.requestCardCustomer.isVisible = item.customer.isNotBlank()
        card.requestCardCustomer.text = item.customer
        card.requestCardWhen.isVisible = meta.isNotBlank()
        card.requestCardWhen.text = meta
        card.requestCardUpdated.isVisible = item.updatedAt.isNotBlank()
        card.requestCardUpdated.text = "به‌روزرسانی ${item.updatedAt}"
        bindRequestHistory(card, item)
        val dial = PartnerApi.asciiDigits(item.phone)
        if (dial.length >= 8) {
            card.requestCardPerson.isClickable = true
            card.requestCardPerson.isFocusable = true
            card.requestCardPerson.setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dial")))
                }
            }
        }
        card.requestCardCommission.isVisible = item.commission.isNotBlank()
        card.requestCardCommission.text = item.commission
        card.requestCardPersonBody.updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = if (item.customer.isNotBlank()) dp(12) else 0
        }
        item.actions.forEachIndexed { index, action ->
            addFollowButton(card, action, index)
        }
        val openPrimary = item.actions.isEmpty()
        card.requestCardOpen.isVisible = item.showLand
        if (item.showLand) {
            card.requestCardOpen.setBackgroundResource(
                if (openPrimary) R.drawable.bg_hub_start else R.drawable.bg_flat_outline_card,
            )
            card.requestCardOpen.setTextColor(
                if (openPrimary) Color.parseColor("#052E16")
                else ContextCompat.getColor(requireContext(), R.color.vinor_text),
            )
            card.requestCardOpen.setOnClickListener {
                (activity as? MainActivity)?.openLand(item.landCode)
            }
        }
        binding.nativeList.addView(card.root)
    }

    private fun bindOfferCard(card: ItemNativeRequestBinding, item: FollowRequests.Card) {
        card.requestCardBadges.isVisible = false
        card.requestCardPerson.isVisible = false
        card.requestCardUpdated.isVisible = false
        card.requestCardHistoryToggle.isVisible = false
        card.requestCardHistory.isVisible = false
        card.requestCardCommission.isVisible = false
        card.requestCardOpen.isVisible = false
        card.requestCardTitle.text = item.title
        val host = card.root
        host.removeView(card.requestCardStatus)
        var at = host.indexOfChild(card.requestCardTitle) + 1
        fun addLine(text: String, size: Float, color: Int): TextView {
            val view = TextView(requireContext()).apply {
                this.text = text
                textSize = size
                setTextColor(ContextCompat.getColor(context, color))
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(8)
            host.addView(view, at, lp)
            at += 1
            return view
        }
        if (item.amountText.isNotBlank()) addLine(item.amountText, 15f, R.color.vinor_text)
        if (item.terms.isNotBlank()) addLine(item.terms, 13f, R.color.vinor_text_muted)
        if (item.status.isNotBlank()) {
            card.requestCardStatus.isVisible = true
            card.requestCardStatus.text = item.status
            card.requestCardStatus.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    when (FollowRequests.statusMark(item.status)) {
                        FollowRequests.Mark.GOOD -> R.color.vinor_accent_green
                        FollowRequests.Mark.BAD -> R.color.vinor_error
                        FollowRequests.Mark.NEUTRAL -> R.color.vinor_text_muted
                    },
                ),
            )
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(8)
            host.addView(card.requestCardStatus, at, lp)
            at += 1
        }
        if (item.phone.isNotBlank()) {
            val phone = addLine(item.phone, 13f, R.color.vinor_text_muted)
            val dial = PartnerApi.asciiDigits(item.phone)
            if (dial.length >= 8) {
                phone.isClickable = true
                phone.isFocusable = true
                phone.setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dial")))
                    }
                }
            }
        }
        item.actions.forEachIndexed { index, action ->
            addFollowButton(card, action, index)
        }
    }

    private fun addFollowButton(card: ItemNativeRequestBinding, action: FollowRequests.Action, index: Int) {
        val primary = action.tone == FollowRequests.Tone.PRIMARY
        val danger = action.tone == FollowRequests.Tone.DANGER
        val btn = TextView(requireContext()).apply {
            text = action.label
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            minHeight = dp(44)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(if (primary) R.drawable.bg_hub_start else R.drawable.bg_flat_outline_card)
            setTextColor(
                when {
                    primary -> Color.parseColor("#052E16")
                    danger -> ContextCompat.getColor(context, R.color.vinor_error)
                    else -> ContextCompat.getColor(context, R.color.vinor_text)
                },
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                when {
                    action.opensWallet -> (activity as? MainActivity)?.openPath(action.path)
                    action.counter -> askCounter(action)
                    action.confirm != null -> confirmFollow(action)
                    else -> dispatchFollow(action)
                }
            }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(if (index == 0) 12 else 8)
        card.requestCardActions.addView(btn, lp)
    }

    private fun askCounter(action: FollowRequests.Action) {
        val sheet = SheetFollowCounterBinding.inflate(layoutInflater)
        val dialog = openFollowSheet(sheet.root)
        sheet.followCounterNo.setOnClickListener { dialog.dismiss() }
        sheet.followCounterYes.setOnClickListener {
            val next = FollowRequests.withCounter(
                action,
                sheet.followCounterAmount.text?.toString().orEmpty(),
                sheet.followCounterTerms.text?.toString().orEmpty(),
            )
            if (next == null) {
                Toast.makeText(requireContext(), "مبلغ را وارد کنید.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            dispatchFollow(next)
        }
    }

    private fun confirmFollow(action: FollowRequests.Action) {
        val sheet = SheetFollowConfirmBinding.inflate(layoutInflater)
        sheet.followConfirmTitle.text = action.label
        sheet.followConfirmMessage.text = action.confirm
        sheet.followConfirmYes.text = action.label
        if (action.tone != FollowRequests.Tone.DANGER) {
            sheet.followConfirmYes.setBackgroundResource(R.drawable.bg_hub_start)
            sheet.followConfirmYes.setTextColor(Color.parseColor("#052E16"))
        }
        val dialog = openFollowSheet(sheet.root)
        sheet.followConfirmNo.setOnClickListener { dialog.dismiss() }
        sheet.followConfirmYes.setOnClickListener {
            dialog.dismiss()
            dispatchFollow(action)
        }
    }

    private fun dispatchFollow(action: FollowRequests.Action) {
        when {
            action.json -> postJsonAction(action)
            action.form -> postInboxAction(action)
            else -> postRequest(action.path, action.body)
        }
    }

    private fun postInboxAction(action: FollowRequests.Action) {
        val ctx = context ?: return
        if (followBusy) return
        setFollowBusy(true)
        scope.launch {
            try {
                var token = withContext(Dispatchers.IO) { ensureFollowCsrf(ctx) }
                var resp = withContext(Dispatchers.IO) {
                    PartnerApi.postInboxForm(
                        action.path,
                        FollowRequests.formFields(action),
                        token,
                        FollowRequests.PAGE_PATH,
                        ctx,
                    )
                }
                if (PartnerCustomers.isCsrfExpired(resp)) {
                    val rejected = token
                    val rotated = resp.optString("csrf_token").trim()
                    followCsrf = rotated.takeIf { it.isNotBlank() && it != rejected }.orEmpty()
                    token = withContext(Dispatchers.IO) { ensureFollowCsrf(ctx, rejected) }
                    if (token.isNotBlank() && token != rejected) {
                        resp = withContext(Dispatchers.IO) {
                            PartnerApi.postInboxForm(
                                action.path,
                                FollowRequests.formFields(action),
                                token,
                                FollowRequests.PAGE_PATH,
                                ctx,
                            )
                        }
                    }
                }
                if (_binding == null) return@launch
                val freshCsrf = resp.optString("csrf_token").trim()
                if (freshCsrf.isNotBlank()) followCsrf = freshCsrf
                if (PartnerApi.isSessionExpired(resp) || resp.optInt("http_status") == 401) {
                    requestTabLogin(sessionExpired = true)
                    return@launch
                }
                if (FollowRequests.isUnauthorized(resp)) {
                    followCsrf = ""
                    Toast.makeText(ctx, FollowRequests.userMessage(resp), Toast.LENGTH_SHORT).show()
                    requestTabLogin()
                    return@launch
                }
                if (FollowRequests.isOk(resp)) {
                    val note = FollowRequests.doneMessage(action.path, resp, action.body)
                    Toast.makeText(
                        ctx,
                        note,
                        if (FollowRequests.listingStillArchived(resp)) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                    ).show()
                    FollowRequests.cachePaths().forEach { PartnerApi.forget(it, ctx) }
                    if (FollowRequests.refreshesWallet(action.path)) {
                        FollowRequests.walletRefreshPaths().forEach { PartnerApi.forget(it, ctx) }
                    }
                    if (FollowRequests.movesCard(action.path, action.body)) requestFilter = FollowRequests.FILTER_ALL
                    forgetPage(PAGE_REQUESTS)
                    binding.nativeRefresh.isRefreshing = true
                    followReload = true
                    followEpoch++
                    load(pull = true)
                } else {
                    Toast.makeText(ctx, FollowRequests.userMessage(resp), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding != null) Toast.makeText(ctx, "اتصال برقرار نشد", Toast.LENGTH_SHORT).show()
            } finally {
                if (!followReload) {
                    if (_binding != null) setFollowBusy(false) else followBusy = false
                }
            }
        }
    }

    private fun openFollowSheet(content: View): BottomSheetDialog {
        followSheet?.dismiss()
        val dialog = BottomSheetDialog(requireContext())
        SystemUi.installSheet(content)
        dialog.setContentView(content)
        dialog.setOnShowListener {
            val panel = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            panel.setBackgroundColor(Color.TRANSPARENT)
            BottomSheetBehavior.from(panel).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        followSheet = dialog
        dialog.show()
        return dialog
    }

    private fun postJsonAction(action: FollowRequests.Action) {
        val ctx = context ?: return
        if (followBusy) return
        setFollowBusy(true)
        scope.launch {
            var handedOff = false
            try {
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson(action.path, action.body, ctx)
                }
                if (_binding == null) return@launch
                if (PartnerApi.isSessionExpired(resp) || resp.optInt("http_status") == 401 || FollowRequests.isUnauthorized(resp)) {
                    requestTabLogin(sessionExpired = true)
                    return@launch
                }
                val ok = FollowRequests.isOk(resp)
                val note = if (ok) {
                    FollowRequests.doneMessage(action.path, resp, action.body)
                } else {
                    FollowRequests.userMessage(resp, "بایگانی پیگیری انجام نشد.")
                }
                Toast.makeText(
                    ctx,
                    note,
                    if (ok && FollowRequests.listingStillArchived(resp)) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
                val status = resp.optInt("http_status")
                if (ok || status == 403 || status == 404) {
                    FollowRequests.cachePaths().forEach { PartnerApi.forget(it, ctx) }
                    forgetPage(PAGE_REQUESTS)
                    handedOff = true
                    binding.nativeRefresh.isRefreshing = true
                    followReload = true
                    followEpoch++
                    load(pull = true)
                }
            } catch (_: Exception) {
                if (_binding != null) Toast.makeText(ctx, "اتصال برقرار نشد", Toast.LENGTH_SHORT).show()
            } finally {
                if (!handedOff) {
                    if (_binding != null) setFollowBusy(false) else followBusy = false
                }
            }
        }
    }

    private fun postListingArchive(land: JSONObject) {
        val code = MyPropertyManage.codeOf(land)
        val ctx = context ?: return
        if (code.isBlank()) {
            Toast.makeText(ctx, "کد ملک نامعتبر است.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!listingBusy) listingBusy = true else return
        binding.nativeProgress.isVisible = true
        val restore = ListingArchive.isArchived(land)
        scope.launch {
            var handedOff = false
            try {
                val fresh = withContext(Dispatchers.IO) {
                    PartnerApi.postJson(ListingArchive.path(restore), ListingArchive.body(code), ctx)
                }
                if (_binding == null) return@launch
                val outcome = ListingArchive.outcome(fresh)
                if (outcome == ListingArchive.Outcome.UNAUTHORIZED) {
                    ListingMemory.clearSession()
                    requestTabLogin(sessionExpired = true)
                    return@launch
                }
                Toast.makeText(
                    ctx,
                    ListingArchive.message(fresh, "بایگانی آگهی انجام نشد."),
                    Toast.LENGTH_LONG,
                ).show()
                if (
                    outcome == ListingArchive.Outcome.OK ||
                    outcome == ListingArchive.Outcome.NOT_FOUND ||
                    outcome == ListingArchive.Outcome.FORBIDDEN
                ) {
                    ListingArchive.cachePaths().forEach { PartnerApi.forget(it, ctx) }
                    MyPropertiesFragment.clearRemembered()
                    forgetPage(PAGE_LISTINGS)
                    handedOff = true
                    pageRendered = false
                    binding.nativeRefresh.isRefreshing = true
                    load(pull = true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding != null) Toast.makeText(ctx, "بایگانی آگهی انجام نشد.", Toast.LENGTH_SHORT).show()
            } finally {
                listingBusy = false
                if (_binding != null && !handedOff) binding.nativeProgress.isVisible = false
            }
        }
    }

    private fun postRequest(path: String, body: JSONObject) {
        val ctx = context ?: return
        if (followBusy) return
        setFollowBusy(true)
        scope.launch {
            try {
                var token = withContext(Dispatchers.IO) { ensureFollowCsrf(ctx) }
                var resp = withContext(Dispatchers.IO) {
                    PartnerApi.postPage(path, body, token, FollowRequests.PAGE_PATH, ctx)
                }
                if (PartnerCustomers.isCsrfExpired(resp)) {
                    val rejected = token
                    val rotated = resp.optString("csrf_token").trim()
                    followCsrf = rotated.takeIf { it.isNotBlank() && it != rejected }.orEmpty()
                    token = withContext(Dispatchers.IO) { ensureFollowCsrf(ctx, rejected) }
                    if (token.isNotBlank() && token != rejected) {
                        resp = withContext(Dispatchers.IO) {
                            PartnerApi.postPage(path, body, token, FollowRequests.PAGE_PATH, ctx)
                        }
                    }
                }
                if (_binding == null) return@launch
                val freshCsrf = resp.optString("csrf_token").trim()
                if (freshCsrf.isNotBlank()) followCsrf = freshCsrf
                if (PartnerApi.isSessionExpired(resp) || resp.optInt("http_status") == 401) {
                    requestTabLogin(sessionExpired = true)
                    return@launch
                }
                if (FollowRequests.isUnauthorized(resp)) {
                    followCsrf = ""
                    Toast.makeText(ctx, FollowRequests.userMessage(resp), Toast.LENGTH_SHORT).show()
                    requestTabLogin()
                    return@launch
                }
                if (FollowRequests.isOk(resp)) {
                    val note = FollowRequests.doneMessage(path, resp, body)
                    Toast.makeText(
                        ctx,
                        note,
                        if (FollowRequests.listingStillArchived(resp)) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                    ).show()
                    FollowRequests.cachePaths().forEach { PartnerApi.forget(it, ctx) }
                    if (FollowRequests.refreshesWallet(path)) {
                        FollowRequests.walletRefreshPaths().forEach { PartnerApi.forget(it, ctx) }
                    }
                    if (FollowRequests.movesCard(path, body)) requestFilter = FollowRequests.FILTER_ALL
                    forgetPage(PAGE_REQUESTS)
                    binding.nativeRefresh.isRefreshing = true
                    followReload = true
                    followEpoch++
                    load(pull = true)
                } else {
                    Toast.makeText(ctx, FollowRequests.userMessage(resp), Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                if (_binding != null) Toast.makeText(ctx, "اتصال برقرار نشد", Toast.LENGTH_SHORT).show()
            } finally {
                if (!followReload) {
                    if (_binding != null) setFollowBusy(false) else followBusy = false
                }
            }
        }
    }

    private fun ensureFollowCsrf(ctx: Context, reject: String = ""): String {
        if (followCsrf.isNotBlank() && followCsrf != reject) return followCsrf
        val profile = try {
            PartnerApi.get("/express/partner/profile/data", ctx)
        } catch (_: Exception) {
            JSONObject()
        }
        var token = profile.optString("csrf_token").trim()
        if (token.isBlank() || token == reject) {
            val page = PartnerApi.getPage(FollowRequests.PAGE_PATH, ctx)
            val htmlToken = PartnerCustomers.csrfFromHtml(page.body)
            if (htmlToken.isNotBlank()) token = htmlToken
        }
        if (token == reject) token = ""
        followCsrf = token
        return token
    }

    private fun setFollowBusy(busy: Boolean) {
        followBusy = busy
        val b = _binding ?: return
        b.nativeProgress.isVisible = busy
        setEnabledRecursively(b.nativeList, !busy)
        setEnabledRecursively(b.nativeTabs, !busy)
        setEnabledRecursively(b.nativeArchiveTabs, !busy)
        b.nativeRefresh.isEnabled = !busy
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setEnabledRecursively(view.getChildAt(i), enabled)
        }
    }

    private fun submitApply() {
        if (!PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openDrawer()
            return
        }
        val body = JSONObject()
            .put("first_name", binding.nativeApplyFirst.text?.toString()?.trim().orEmpty())
            .put("last_name", binding.nativeApplyLast.text?.toString()?.trim().orEmpty())
            .put("national_id", PartnerApi.asciiDigits(binding.nativeApplyNational.text?.toString().orEmpty()))
            .put("source", "apk")
        binding.nativeApplySubmit.isEnabled = false
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/api/apply/submit", body, requireContext())
                }
                if (_binding == null) return@launch
                if (PartnerApi.isSessionExpired(resp) || resp.optInt("http_status") == 401) {
                    requestTabLogin(sessionExpired = resp.optBoolean("session_expired"))
                    return@launch
                }
                if (resp.optBoolean("ok") || resp.optString("status") == "pending" || resp.optString("status") == "approved") {
                    bindApply(resp)
                    if (resp.optString("status") == "approved") {
                        Toast.makeText(requireContext(), resp.optString("message").ifBlank { "همکاری تأیید شد." }, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), resp.optString("error").ifBlank { "ثبت ناموفق بود." }, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(requireContext(), "اتصال برقرار نشد", Toast.LENGTH_SHORT).show()
            } finally {
                if (_binding != null) binding.nativeApplySubmit.isEnabled = true
            }
        }
    }

    private fun inviteLink(): String = InvitePage.linkOf(
        lastPayload.optString("invite_url"),
        binding.nativeInviteUrl.text?.toString().orEmpty(),
    )

    private fun copyInvite() {
        val url = inviteLink()
        if (url.isBlank()) {
            Toast.makeText(requireContext(), InvitePage.MISSING, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("invite", url))
        Toast.makeText(requireContext(), "لینک کپی شد.", Toast.LENGTH_SHORT).show()
    }

    private fun shareInvite() {
        val message = InvitePage.shareMessage(lastPayload.optString("share_text"), inviteLink())
        if (message.isNullOrBlank()) {
            Toast.makeText(requireContext(), InvitePage.MISSING, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }, "اشتراک‌گذاری"))
    }

    private fun hideSpecials() {
        binding.nativeFavoritesEmpty.isVisible = false
        binding.nativeApplyForm.isVisible = false
        binding.nativeInviteBox.isVisible = false
        binding.nativeLeadBox.isVisible = false
        binding.nativeCta.isVisible = false
        binding.nativeTabsScroll.isVisible = false
        binding.nativeArchiveScroll.isVisible = false
        binding.nativeEmpty.isVisible = false
        binding.nativeHero.isVisible = false
        binding.nativeSectionTitle.isVisible = false
        binding.nativeFaqTitle.isVisible = false
        binding.nativeSteps.isVisible = false
        binding.nativeSteps.removeAllViews()
    }

    private fun bindEmpty(msg: String) {
        val keepArchive = page == PAGE_LISTINGS
        hideSpecials()
        if (keepArchive) binding.nativeArchiveScroll.isVisible = true
        binding.nativeList.removeAllViews()
        binding.nativeProgress.isVisible = false
        binding.nativeRefresh.isRefreshing = false
        if (page == PAGE_FAVORITES && msg == FAVORITES_EMPTY) {
            binding.nativeEmpty.isVisible = false
            binding.nativeFavoritesEmpty.isVisible = true
            return
        }
        binding.nativeFavoritesEmpty.isVisible = false
        binding.nativeEmpty.isVisible = true
        binding.nativeEmpty.text = msg
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_PAGE = "page"
        const val PAGE_TRAINING = "training"
        const val PAGE_LEVELS = "levels"
        const val PAGE_INVITE = "invite"
        const val PAGE_SETTINGS = "settings"
        const val PAGE_FAVORITES = "favorites"
        const val PAGE_REQUESTS = "requests"
        const val PAGE_APPLY = "apply"
        const val PAGE_LISTINGS = "listings"
        const val PAGE_TERMS = "terms"
        const val PAGE_PRIVACY = "privacy"

        fun titleFor(page: String): String = when (page) {
            PAGE_TRAINING -> "آموزش"
            PAGE_LEVELS -> "سطوح همکاری"
            PAGE_INVITE -> "دعوت"
            PAGE_SETTINGS -> "تنظیمات"
            PAGE_FAVORITES -> "نشان‌شده‌ها"
            PAGE_REQUESTS -> "پیگیری"
            PAGE_APPLY -> ConsultantOnboarding.UI_LABEL
            PAGE_LISTINGS -> "ملک من"
            PAGE_TERMS -> "قوانین استفاده"
            PAGE_PRIVACY -> "حریم خصوصی"
            else -> "وینور"
        }

        private val rememberedByPage = HashMap<String, JSONObject>()
        private var rememberedAccount: String? = null
        private val PUBLIC_PAGES = setOf(PAGE_TRAINING, PAGE_LEVELS, PAGE_TERMS, PAGE_PRIVACY)
        private var sessionStamp = 0

        fun generation(): Int = sessionStamp

        fun clearRemembered() {
            rememberedByPage.clear()
            rememberedAccount = null
            sessionStamp++
        }

        fun forgetPage(page: String) {
            rememberedByPage.keys.removeAll { it == page || it.startsWith("$page:") }
        }

        fun dataPath(page: String): String? = when (page) {
            PAGE_TRAINING -> "/express/partner/api/training/data"
            PAGE_LEVELS -> "/express/partner/api/levels/data"
            PAGE_INVITE -> "/express/partner/api/invite/data"
            PAGE_APPLY -> "/express/partner/api/apply/data"
            PAGE_REQUESTS -> FollowRequests.dataPath()
            PAGE_LISTINGS -> "/express/partner/api/listings/data"
            PAGE_TERMS -> "/express/partner/api/legal/data?doc=terms"
            PAGE_PRIVACY -> "/express/partner/api/legal/data?doc=privacy"
            else -> null
        }

        private const val FAVORITES_EMPTY = "هنوز آگهی‌ای نشان نکرده‌اید"
    }
}
