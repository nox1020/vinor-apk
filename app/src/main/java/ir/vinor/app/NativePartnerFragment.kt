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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentNativePartnerBinding
import ir.vinor.app.databinding.ItemHelpStepBinding
import ir.vinor.app.databinding.ItemLandCardBinding
import ir.vinor.app.databinding.ItemNativeCardBinding
import ir.vinor.app.databinding.ItemNativeRequestBinding
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
                if (_binding != null && binding.nativeList.childCount == 0) {
                    bindEmpty(e.message ?: "خطای شبکه")
                } else {
                    Toast.makeText(requireContext(), e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
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
        val logged = obj.optBoolean("logged_in")
        binding.nativeCta.isVisible = !approved || !special
        binding.nativeCta.text = if (!approved) "بازاریاب شو" else "ملک من"
        binding.nativeCta.setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            when {
                !approved && !logged -> act.openDrawer()
                !approved -> act.openNativePage(PAGE_APPLY)
                else -> act.openMyProperty()
            }
        }
    }

    private fun bindInvite(obj: JSONObject) {
        binding.nativeInviteBox.isVisible = true
        binding.nativeLeadBox.isVisible = true
        val approved = obj.optBoolean("is_approved")
        val bonus = obj.optInt("bonus_per_invite")
        setTitle(if (approved) "دعوت از همکاران" else "دعوت از دوستان")
        binding.nativeLead.text = if (approved) {
            "هر همکار تأییدشده، ${PartnerFormat.toman(bonus)} تومان به موجودی شما اضافه می‌شود."
        } else {
            "لینک زیر را بفرست تا دوستانت راحت‌تر به وینور بپیوندند."
        }
        binding.nativeInviteUrl.text = obj.optString("invite_url")
        val stats = obj.optJSONObject("ref_stats")
        if (approved && stats != null) {
            addCard("دریافت‌شده", PartnerFormat.toman(stats.optInt("total_earned")) + " تومان")
        }
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
        val tabs = obj.optJSONArray("tabs") ?: JSONArray()
        if (activeTab.isBlank()) activeTab = obj.optString("active_tab").ifBlank { "requests" }
        bindTabs(tabs, "id", "title") { tab ->
            activeTab = tab
            renderRequestItems(obj, tab)
        }
        renderRequestItems(obj, activeTab)
    }

    private fun renderRequestItems(obj: JSONObject, tab: String) {
        binding.nativeList.removeAllViews()
        val items = obj.optJSONArray("items") ?: obj.optJSONArray("inbox_items") ?: JSONArray()
        var shown = 0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val itemTab = it.optString("pipeline_tab").ifBlank { "requests" }
            if (tab.isNotBlank() && itemTab != tab) continue
            addRequestCard(it)
            shown++
        }
        binding.nativeEmpty.isVisible = shown == 0
        if (shown == 0) binding.nativeEmpty.text = "موردی در این تب نیست."
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

    private fun bindTabs(tabs: JSONArray, idKey: String, titleKey: String, onPick: (String) -> Unit) {
        binding.nativeTabsScroll.isVisible = tabs.length() > 0
        binding.nativeTabs.removeAllViews()
        for (i in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(i) ?: continue
            val id = tab.optString(idKey)
            val chip = TextView(requireContext()).apply {
                text = tab.optString(titleKey)
                setTextColor(Color.parseColor(if (id == activeTab || (activeTab.isBlank() && i == 0)) "#FFFFFF" else "#A1A1AA"))
                textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundResource(if (id == activeTab || (activeTab.isBlank() && i == 0)) R.drawable.bg_button_primary else R.drawable.bg_routine_card)
                setOnClickListener {
                    activeTab = id
                    bindTabs(tabs, idKey, titleKey, onPick)
                    onPick(id)
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            binding.nativeTabs.addView(chip, lp)
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

    private fun addRequestCard(item: JSONObject) {
        val card = ItemNativeRequestBinding.inflate(layoutInflater, binding.nativeList, false)
        card.requestCardTitle.text = item.optString("title").ifBlank { item.optString("land_title") }
        val meta = listOf(
            item.optString("status_label"),
            item.optString("customer_name"),
            item.optString("visit_when"),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        card.requestCardMeta.text = meta
        val id = item.optString("id")
        fun action(label: String, path: String, body: JSONObject = JSONObject()) {
            val btn = TextView(requireContext()).apply {
                text = label
                setTextColor(Color.parseColor("#86EFAC"))
                textSize = 12f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { postRequest(path, body) }
            }
            card.requestCardActions.addView(btn)
        }
        if (item.optBoolean("can_remove")) action("حذف", "/express/partner/requests/$id/remove")
        if (item.optBoolean("can_revisit")) action("بازدید مجدد", "/express/partner/requests/$id/revisit")
        if (item.optBoolean("can_complete")) action("انجام شد", "/express/partner/requests/$id/complete")
        if (item.optBoolean("can_no_show")) action("عدم حضور", "/express/partner/requests/$id/no-show")
        if (item.optBoolean("can_register_result")) {
            action("علاقه‌مند", "/express/partner/requests/$id/visit-result", JSONObject().put("outcome", "interested"))
            action("پیگیری", "/express/partner/requests/$id/visit-result", JSONObject().put("outcome", "follow_up"))
            action("بی‌علاقه", "/express/partner/requests/$id/visit-result", JSONObject().put("outcome", "not_interested"))
        }
        if (item.optBoolean("can_confirm_deal")) action("تأیید معامله", "/express/partner/requests/$id/deal-confirm")
        if (item.optBoolean("can_reject_deal")) action("رد معامله", "/express/partner/requests/$id/deal-reject")
        if (item.optBoolean("can_close_deal")) action("بستن", "/express/partner/requests/$id/deal-close")
        val code = item.optString("land_code")
        if (code.isNotBlank()) {
            card.root.setOnClickListener { (activity as? MainActivity)?.openLand(code) }
        }
        binding.nativeList.addView(card.root)
    }

    private fun postRequest(path: String, body: JSONObject) {
        val ctx = context ?: return
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { PartnerApi.postJson(path, body, ctx) }
                if (_binding == null) return@launch
                if (resp.optBoolean("ok")) {
                    Toast.makeText(ctx, resp.optString("message").ifBlank { "ثبت شد." }, Toast.LENGTH_SHORT).show()
                    load(forceNet = true)
                } else {
                    Toast.makeText(ctx, resp.optString("error").ifBlank { "ناموفق بود." }, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
            }
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

    private fun copyInvite() {
        val url = lastPayload.optString("invite_url").ifBlank { binding.nativeInviteUrl.text?.toString().orEmpty() }
        if (url.isBlank()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("invite", url))
        Toast.makeText(requireContext(), "لینک کپی شد.", Toast.LENGTH_SHORT).show()
    }

    private fun shareInvite() {
        val url = lastPayload.optString("invite_url")
        val text = lastPayload.optString("share_text")
        if (url.isBlank()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$text\n$url")
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
            PAGE_APPLY -> "درخواست همکاری"
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
            PAGE_REQUESTS -> "/express/partner/api/requests/data"
            PAGE_LISTINGS -> "/express/partner/api/listings/data"
            PAGE_TERMS -> "/express/partner/api/legal/data?doc=terms"
            PAGE_PRIVACY -> "/express/partner/api/legal/data?doc=privacy"
            else -> null
        }
    }
}
