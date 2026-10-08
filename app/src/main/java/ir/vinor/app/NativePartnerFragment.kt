package ir.vinor.app

import android.text.Editable
import android.text.TextWatcher
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
    private var lastPayload = JSONObject()
    private var searchQuery = ""
    private var followBusy = false
    private var followCsrf = ""
    private var followSheet: BottomSheetDialog? = null

    val page: String
        get() = arguments?.getString(ARG_PAGE).orEmpty().ifBlank { PAGE_TRAINING }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
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
        binding.nativeRefresh.setColorSchemeColors(Color.parseColor("#10B981"))
        binding.nativeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1C1C1E"))
        binding.nativeRefresh.setOnRefreshListener { load(forceNet = true) }
        binding.nativeApplySubmit.setOnClickListener { submitApply() }
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
        load()
    }

    override fun onDestroyView() {
        followSheet?.dismiss()
        followSheet = null
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun goBack() {
        if (!findNavController().navigateUp()) {
            (activity as? MainActivity)?.openPartnerHome()
        }
    }

    private fun setTitle(text: String) {
        binding.nativeHeader.pageTitle.text = text
    }

    private fun load(forceNet: Boolean = false) {
        when (page) {
            PAGE_SETTINGS -> {
                binding.nativeRefresh.isRefreshing = false
                bindSettings()
            }
            PAGE_FAVORITES -> loadFavorites()
            else -> loadJson(forceNet)
        }
    }

    private fun loadJson(forceNet: Boolean) {
        val ctx = context ?: return
        val path = dataPath(page) ?: return bindEmpty("صفحه پیدا نشد.")
        scope.launch {
            if (!forceNet) {
                val cached = withContext(Dispatchers.IO) { PartnerApi.peekJson(path, ctx) }
                if (_binding != null && cached != null) bindPayload(cached)
                else if (_binding != null) binding.nativeProgress.isVisible = true
                if (cached != null && withContext(Dispatchers.IO) { PartnerApi.isFresh(path, ctx) }) {
                    if (_binding != null) {
                        binding.nativeProgress.isVisible = false
                        binding.nativeRefresh.isRefreshing = false
                    }
                    return@launch
                }
            }
            try {
                val fresh = withContext(Dispatchers.IO) { PartnerApi.get(path, ctx) }
                if (_binding == null) return@launch
                val code = fresh.optInt("http_status")
                if (code == 401 || fresh.optString("error") == "unauthorized") {
                    bindEmpty("برای ادامه وارد شوید.")
                    (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                if (code == 403) {
                    bindEmpty(fresh.optString("error").ifBlank { "دسترسی ندارید." })
                    return@launch
                }
                bindPayload(fresh)
            } catch (e: Exception) {
                if (_binding == null) return@launch
                val showing = binding.nativeList.childCount > 0 || binding.nativeInviteBox.isVisible
                if (showing) {
                    Toast.makeText(requireContext(), e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
                } else {
                    bindEmpty(e.message ?: "خطای شبکه")
                }
            } finally {
                if (_binding != null) {
                    binding.nativeProgress.isVisible = false
                    binding.nativeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun loadFavorites() {
        val ctx = context ?: return
        if (!PartnerApi.hasSession()) {
            bindEmpty("برای دیدن نشان‌ها وارد شوید.")
            binding.nativeRefresh.isRefreshing = false
            return
        }
        val codes = VinorFavoritesStore.codes(ctx)
        if (codes.isEmpty()) {
            bindEmpty("هنوز فایلی نشان نکرده‌اید.")
            binding.nativeRefresh.isRefreshing = false
            return
        }
        scope.launch {
            binding.nativeProgress.isVisible = binding.nativeList.childCount == 0
            try {
                val body = JSONObject().put("codes", JSONArray().also { arr ->
                    codes.forEach { arr.put(it) }
                })
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/api/favorites/cards", body, ctx)
                }
                if (_binding == null) return@launch
                bindLandItems(resp.optJSONArray("items") ?: JSONArray(), emptyMsg = "فایل نشان‌شده‌ای پیدا نشد.")
            } catch (e: Exception) {
                if (_binding != null) bindEmpty(e.message ?: "خطای شبکه")
            } finally {
                if (_binding != null) {
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
            PAGE_NEWS -> bindNews(obj)
            PAGE_TRAINING -> bindTraining(obj)
            PAGE_LEVELS -> bindLevels(obj)
            PAGE_INVITE -> bindInvite(obj)
            PAGE_APPLY -> bindApply(obj)
            PAGE_REQUESTS -> bindRequests(obj)
            PAGE_LISTINGS -> bindLandItems(obj.optJSONArray("items") ?: JSONArray(), "آگهی‌ای ثبت نشده.")
            PAGE_TERMS, PAGE_PRIVACY -> bindLegal(obj)
            else -> bindEmpty("صفحه آماده نیست.")
        }
    }

    private fun bindSettings() {
        hideSpecials()
        binding.nativeEmpty.isVisible = false
        binding.nativeList.removeAllViews()
        addActionRow("ویرایش پروفایل") { (activity as? MainActivity)?.openProfileEdit() }
        addActionRow("خروج", danger = true) { (activity as? MainActivity)?.logoutFromDrawer() }
    }

    private fun bindNews(obj: JSONObject) {
        val news = obj.optJSONArray("home_news_items") ?: JSONArray()
        var shown = 0
        for (i in 0 until news.length()) {
            val raw = news.opt(i)
            val text: String
            val time: String
            if (raw is JSONObject) {
                text = raw.optString("text")
                time = raw.optString("time")
            } else {
                text = news.optString(i)
                time = ""
            }
            if (text.isBlank()) continue
            addCard(text, "", time)
            shown++
        }
        if (shown == 0) bindEmpty("خبری ثبت نشده.")
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
        val tabs = FollowRequests.tabs(obj.optJSONArray("tabs"))
        activeTab = FollowRequests.activeId(activeTab, obj.optString("active_tab"), tabs)
        bindRequestTabs(tabs)
        renderRequestItems(obj, activeTab)
    }

    private fun bindRequestTabs(tabs: List<FollowRequests.Tab>) {
        binding.nativeTabsScroll.isVisible = tabs.isNotEmpty()
        binding.nativeTabs.removeAllViews()
        for (tab in tabs) {
            val on = tab.id == activeTab
            val chip = TextView(requireContext()).apply {
                text = tab.title
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                minHeight = dp(36)
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (on) R.color.vinor_on_primary else R.color.vinor_text_muted,
                    ),
                )
                setBackgroundResource(if (on) R.drawable.bg_follow_tab_on else R.drawable.bg_follow_tab)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    if (activeTab == tab.id) return@setOnClickListener
                    activeTab = tab.id
                    bindRequestTabs(tabs)
                    renderRequestItems(lastPayload, activeTab)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8)
            binding.nativeTabs.addView(chip, lp)
        }
    }

    private fun renderRequestItems(obj: JSONObject, tab: String) {
        binding.nativeList.removeAllViews()
        binding.nativeEmpty.isVisible = false
        val rows = FollowRequests.items(obj, tab)
        if (rows.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = FollowRequests.EMPTY
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
        for (row in rows) addRequestCard(FollowRequests.card(row))
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

    private fun bindLandItems(items: JSONArray, emptyMsg: String) {
        hideSpecials()
        binding.nativeEmpty.isVisible = items.length() == 0
        binding.nativeEmpty.text = emptyMsg
        binding.nativeList.removeAllViews()
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
            val code = land.optString("code")
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
            setTextColor(Color.parseColor(if (danger) "#FB7185" else "#FFFFFF"))
            textSize = 15f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.bg_routine_card)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        binding.nativeList.addView(row, lp)
    }

    private fun addRequestCard(item: FollowRequests.Card) {
        val card = ItemNativeRequestBinding.inflate(layoutInflater, binding.nativeList, false)
        card.requestCardStatus.isVisible = item.status.isNotBlank()
        card.requestCardStatus.text = item.status
        card.requestCardTitle.text = item.title
        val titleLp = card.requestCardTitle.layoutParams as ViewGroup.MarginLayoutParams
        titleLp.topMargin = if (item.status.isNotBlank()) dp(8) else 0
        card.requestCardTitle.layoutParams = titleLp
        val showPerson = item.customer.isNotBlank() || item.whenText.isNotBlank()
        card.requestCardPerson.isVisible = showPerson
        card.requestCardAvatar.isVisible = item.customer.isNotBlank()
        card.requestCardAvatar.text = item.customer.trim().firstOrNull()?.toString().orEmpty()
        card.requestCardCustomer.isVisible = item.customer.isNotBlank()
        card.requestCardCustomer.text = item.customer
        card.requestCardWhen.isVisible = item.whenText.isNotBlank()
        card.requestCardWhen.text = item.whenText
        card.requestCardPersonBody.updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = if (item.customer.isNotBlank()) dp(12) else 0
        }
        item.actions.forEachIndexed { index, action ->
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
                    if (action.confirm != null) confirmFollow(action) else postRequest(action.path, action.body)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(if (index == 0) 12 else 8)
            card.requestCardActions.addView(btn, lp)
        }
        val openPrimary = item.actions.isEmpty()
        card.requestCardOpen.isVisible = item.landCode.isNotBlank()
        if (item.landCode.isNotBlank()) {
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

    private fun confirmFollow(action: FollowRequests.Action) {
        val sheet = SheetFollowConfirmBinding.inflate(layoutInflater)
        sheet.followConfirmTitle.text = action.label
        sheet.followConfirmMessage.text = action.confirm
        sheet.followConfirmYes.text = action.label
        val dialog = openFollowSheet(sheet.root)
        sheet.followConfirmNo.setOnClickListener { dialog.dismiss() }
        sheet.followConfirmYes.setOnClickListener {
            dialog.dismiss()
            postRequest(action.path, action.body)
        }
    }

    private fun openFollowSheet(content: View): BottomSheetDialog {
        followSheet?.dismiss()
        val dialog = BottomSheetDialog(requireContext())
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

    private fun postRequest(path: String, body: JSONObject) {
        val ctx = context ?: return
        if (followBusy) return
        setFollowBusy(true)
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { ensureFollowCsrf(ctx) }
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postPage(path, body, token, FollowRequests.PAGE_PATH, ctx)
                }
                if (_binding == null) return@launch
                val freshCsrf = resp.optString("csrf_token").trim()
                if (freshCsrf.isNotBlank()) followCsrf = freshCsrf
                if (FollowRequests.isUnauthorized(resp)) {
                    followCsrf = ""
                    Toast.makeText(ctx, FollowRequests.userMessage(resp), Toast.LENGTH_SHORT).show()
                    (activity as? MainActivity)?.openLogin()
                    return@launch
                }
                if (FollowRequests.isOk(resp)) {
                    val note = resp.optString("message").trim()
                    val toast = if (note.isBlank() || note.equals("null", true)) "ثبت شد." else note
                    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
                    // نتیجهٔ بازدید یا معامله ممکن است کارت را به تب بعدی منتقل کند.
                    // انتخاب پیشنهادی سرور را در بارگذاری بعدی بپذیر.
                    activeTab = ""
                    binding.nativeList.removeAllViews()
                    binding.nativeRefresh.isRefreshing = true
                    load(forceNet = true)
                } else {
                    Toast.makeText(ctx, FollowRequests.userMessage(resp), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (_binding != null) Toast.makeText(ctx, e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
            } finally {
                if (_binding != null) setFollowBusy(false) else followBusy = false
            }
        }
    }

    private fun ensureFollowCsrf(ctx: Context): String {
        if (followCsrf.isNotBlank()) return followCsrf
        val profile = try {
            PartnerApi.get("/express/partner/profile/data", ctx)
        } catch (_: Exception) {
            JSONObject()
        }
        var token = profile.optString("csrf_token").trim()
        if (token.isBlank()) {
            val page = PartnerApi.getPage(FollowRequests.PAGE_PATH, ctx)
            token = PartnerCustomers.csrfFromHtml(page.body)
        }
        followCsrf = token
        return token
    }

    private fun setFollowBusy(busy: Boolean) {
        followBusy = busy
        val b = _binding ?: return
        b.nativeProgress.isVisible = busy
        setEnabledRecursively(b.nativeList, !busy)
        setEnabledRecursively(b.nativeTabs, !busy)
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
                if (resp.optBoolean("ok") || resp.optString("status") == "pending" || resp.optString("status") == "approved") {
                    bindApply(resp)
                    if (resp.optString("status") == "approved") {
                        Toast.makeText(requireContext(), resp.optString("message").ifBlank { "همکاری تأیید شد." }, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), resp.optString("error").ifBlank { "ثبت ناموفق بود." }, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
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
        binding.nativeApplyForm.isVisible = false
        binding.nativeInviteBox.isVisible = false
        binding.nativeLeadBox.isVisible = false
        binding.nativeCta.isVisible = false
        binding.nativeTabsScroll.isVisible = false
        binding.nativeEmpty.isVisible = false
        binding.nativeHero.isVisible = false
        binding.nativeSectionTitle.isVisible = false
        binding.nativeFaqTitle.isVisible = false
        binding.nativeSteps.isVisible = false
        binding.nativeSteps.removeAllViews()
    }

    private fun bindEmpty(msg: String) {
        hideSpecials()
        binding.nativeList.removeAllViews()
        binding.nativeEmpty.isVisible = true
        binding.nativeEmpty.text = msg
        binding.nativeProgress.isVisible = false
        binding.nativeRefresh.isRefreshing = false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_PAGE = "page"
        const val PAGE_NEWS = "news"
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
            PAGE_NEWS -> "خبر وینور"
            PAGE_TRAINING -> "آموزش"
            PAGE_LEVELS -> "سطوح همکاری"
            PAGE_INVITE -> "دعوت"
            PAGE_SETTINGS -> "تنظیمات"
            PAGE_FAVORITES -> "نشان‌ها"
            PAGE_REQUESTS -> "پیگیری"
            PAGE_APPLY -> ConsultantOnboarding.UI_LABEL
            PAGE_LISTINGS -> "ملک من"
            PAGE_TERMS -> "قوانین استفاده"
            PAGE_PRIVACY -> "حریم خصوصی"
            else -> "وینور"
        }

        fun dataPath(page: String): String? = when (page) {
            PAGE_NEWS -> "/express/partner/home/data"
            PAGE_TRAINING -> "/express/partner/api/training/data"
            PAGE_LEVELS -> "/express/partner/api/levels/data"
            PAGE_INVITE -> "/express/partner/api/invite/data"
            PAGE_APPLY -> "/express/partner/api/apply/data"
            PAGE_REQUESTS -> FollowRequests.DATA_PATH
            PAGE_LISTINGS -> "/express/partner/api/listings/data"
            PAGE_TERMS -> "/express/partner/api/legal/data?doc=terms"
            PAGE_PRIVACY -> "/express/partner/api/legal/data?doc=privacy"
            else -> null
        }
    }
}
