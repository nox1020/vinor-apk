package ir.vinor.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import ir.vinor.app.databinding.FragmentLandDetailBinding
import ir.vinor.app.databinding.SheetLandVisitBinding
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
    private var listing = JSONObject()
    private var galleryCount = 0
    private var visitSheet: BottomSheetDialog? = null
    private var visitBusy = false
    private var loadGen = 0
    private var rendered = false
    private var skipNextResume = true
    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            paintCounter(position)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        skipNextResume = true
        _binding = FragmentLandDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.landDetailBack.setOnClickListener { findNavController().navigateUp() }
        binding.landDetailShare.setOnClickListener { shareListing() }
        binding.landDetailGallery.registerOnPageChangeCallback(pageCallback)
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
        remembered[code]?.let {
            bindLand(code, it)
            rendered = true
        }
        load(code)
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResume) {
            skipNextResume = false
            return
        }
        val code = listingCode
        if (_binding != null && code.isNotBlank()) load(code)
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
        visitSheet?.dismiss()
        visitSheet = null
        _binding?.landDetailGallery?.unregisterOnPageChangeCallback(pageCallback)
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
        val path = apiPath(code)
        if (!rendered) binding.landDetailProgress.isVisible = true
        val gen = ++loadGen
        val stamp = sessionStamp
        scope.launch {
            val wasFresh = withContext(Dispatchers.IO) { PartnerApi.isFresh(path, ctx) }
            try {
                val obj = PartnerApi.loadDisplay(path, ctx) { data ->
                    if (_binding == null || gen != loadGen || stamp != sessionStamp) return@loadDisplay
                    if (!acceptable(data)) return@loadDisplay
                    bindLand(code, data)
                    rendered = true
                    binding.landDetailProgress.isVisible = false
                    binding.landDetailError.isVisible = false
                }
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                if (VinorPageLoad.needsLogin(obj)) {
                    ListingMemory.clearSession()
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (acceptable(obj)) {
                    bindLand(code, obj)
                    rendered = true
                    binding.landDetailError.isVisible = false
                    remember(code, obj)
                }
                when {
                    rendered && VinorPageLoad.networkFailed(wasFresh, obj) ->
                        VinorPageLoad.showOffline(binding.root) { load(code) }
                    !rendered && missing(obj) -> showMissing(obj)
                    !rendered -> showOffline()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding == null || gen != loadGen || stamp != sessionStamp) return@launch
                if (rendered) VinorPageLoad.showOffline(binding.root) { load(code) } else showOffline()
            } finally {
                if (_binding != null && gen == loadGen) binding.landDetailProgress.isVisible = false
            }
        }
    }

    private fun acceptable(obj: JSONObject): Boolean {
        if (VinorPageLoad.needsLogin(obj) || obj.optInt("http_status") == 403) return false
        if (obj.optBoolean("offline") || obj.optBoolean("non_json")) return false
        if (!obj.optBoolean("ok") && obj.optString("title").isBlank()) return false
        val code = if (obj.has("http_status")) obj.optInt("http_status") else 200
        return code in 200..299
    }

    private fun missing(obj: JSONObject): Boolean {
        if (obj.optBoolean("from_cache") || obj.optBoolean("offline")) return false
        val code = obj.optInt("http_status")
        if (code == 0 || code >= 500) return false
        return !obj.optBoolean("ok") && obj.optString("title").isBlank()
    }

    private fun showMissing(obj: JSONObject) {
        val b = _binding ?: return
        b.landDetailError.isVisible = true
        b.landDetailError.text = missingMessage(obj)
    }

    private fun showOffline() {
        val b = _binding ?: return
        b.landDetailError.isVisible = true
        b.landDetailError.text = VinorPageLoad.OFFLINE
    }

    private fun bindLand(code: String, obj: JSONObject) {
        if (!obj.optBoolean("ok") && obj.optString("title").isBlank()) return
        listing = obj
        binding.landDetailError.isVisible = false
        val source = obj.optString("source")
        val isDivar = source == "divar" || code.startsWith("divar:", ignoreCase = true)
        val hasSession = PartnerApi.hasSession()
        val isMine = obj.optBoolean("is_mine")
        val published = Listings.published(obj)
        val audience = LandDetailRole.audience(hasSession, isMine)
        val badge = LandDetailRole.badge(
            hasSession,
            isMine,
            isDivar,
            obj.optString("owner_badge"),
            published,
        )
        binding.landDetailBadge.isVisible = badge.isNotBlank()
        binding.landDetailBadge.text = badge
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
        binding.landDetailCommission.isVisible = LandDetailRole.showCommission(hasSession, comm)
        if (comm > 0) {
            binding.landDetailCommission.text = "پورسانت ${PartnerFormat.toman(comm)} تومان"
        }
        val place = LandDetailRole.place(obj.optString("city"), obj.optString("location"))
        binding.landDetailPlace.text = place
        binding.landDetailPlace.isVisible = place.isNotBlank()
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
        binding.landDetailMeta.isVisible = LandDetailRole.showMeta(hasSession, place, meta)
        val rows = obj.optJSONArray("rows") ?: JSONArray()
        bindGallery(obj)
        bindFacts(rows)
        bindBody(obj.optString("description").ifBlank { obj.optString("body") })
        bindRows(rows)
        bindSticky(code, obj, audience, isDivar, published)
        bindPublish(code, obj, hasSession)
    }

    private fun bindSticky(
        code: String,
        obj: JSONObject,
        audience: LandDetailRole.Audience,
        isDivar: Boolean,
        published: Boolean,
    ) {
        val divarUrl = obj.optString("divar_url").trim()
        val action = LandDetailRole.sticky(
            audience,
            isDivar,
            obj.optBoolean("show_divar"),
            divarUrl.isNotBlank(),
        )
        binding.landDetailSticky.isVisible = true
        binding.landDetailStickyPrice.text = binding.landDetailPrice.text
        binding.landDetailStickyPrice.isVisible = binding.landDetailPrice.text.isNotBlank()
        val hint = LandDetailRole.stickyHint(audience, published)
        binding.landDetailStickyHint.isVisible = hint.isNotBlank()
        binding.landDetailStickyHint.text = hint
        if (hint.isNotBlank()) {
            binding.landDetailStickyHint.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (published) R.color.vinor_accent_green else R.color.vinor_text_muted,
                ),
            )
        }
        binding.landDetailDivar.text = action.label
        binding.landDetailDivar.setOnClickListener {
            when (action.kind) {
                LandDetailRole.StickyKind.LOGIN -> (activity as? MainActivity)?.requireChatLogin()
                LandDetailRole.StickyKind.CHAT -> (activity as? MainActivity)?.openVinorChat()
                LandDetailRole.StickyKind.DIVAR -> openDivar(code, divarUrl)
                LandDetailRole.StickyKind.MANAGE -> (activity as? MainActivity)?.openMyPropertyManage(obj)
            }
        }
        val showVisit = LandVisit.showBesideChat(
            audience,
            isDivar,
            obj.optBoolean("show_divar"),
            divarUrl.isNotBlank(),
        )
        binding.landDetailVisit.isVisible = showVisit
        binding.landDetailVisit.setOnClickListener {
            if (!PartnerApi.hasSession()) {
                (activity as? MainActivity)?.requireChatLogin()
            } else {
                openVisit(code)
            }
        }
    }

    private fun openVisit(code: String) {
        val current = ListingRepository.currentCode(code).ifBlank { code.trim() }
        if (visitBusy || current.isBlank()) return
        val ctx = context ?: return
        visitBusy = true
        binding.landDetailVisit.isEnabled = false
        scope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    PartnerApi.get(LandVisit.statusPath(Uri.encode(current)), ctx)
                }
                if (_binding == null) return@launch
                when (val outcome = LandVisit.decide(current, status)) {
                    is LandVisit.Outcome.Ready -> showVisitSheet(outcome.schedule)
                    LandVisit.Outcome.Login -> {
                        (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    }
                    LandVisit.Outcome.Offline -> {
                        Toast.makeText(ctx, VinorPageLoad.OFFLINE, Toast.LENGTH_LONG).show()
                    }
                    is LandVisit.Outcome.Blocked -> {
                        Toast.makeText(ctx, outcome.message, Toast.LENGTH_LONG).show()
                    }
                    LandVisit.Outcome.Unavailable -> {
                        Toast.makeText(ctx, LandVisit.UNAVAILABLE, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding != null) {
                    Toast.makeText(ctx, VinorPageLoad.OFFLINE, Toast.LENGTH_LONG).show()
                }
            } finally {
                visitBusy = false
                if (_binding != null) binding.landDetailVisit.isEnabled = true
            }
        }
    }

    private fun showVisitSheet(schedule: LandVisit.Schedule) {
        val sheet = SheetLandVisitBinding.inflate(layoutInflater)
        val today = LandVisit.todayIso()
        val hour = LandVisit.tehranHour()
        val (minIso, maxIso) = LandVisit.bounds(schedule.minDate, today)
        val start = LandVisit.jalaliOf(minIso)
        var year = start[0]
        var month = start[1]
        var selected = ""
        fun paint() {
            val maxJ = LandVisit.jalaliOf(maxIso)
            val minJ = LandVisit.jalaliOf(minIso)
            fun pack(y: Int, m: Int) = y * 12 + m
            sheet.landVisitPrev.isEnabled = pack(year, month) > pack(minJ[0], minJ[1])
            sheet.landVisitNext.isEnabled = pack(year, month) < pack(maxJ[0], maxJ[1])
            sheet.landVisitPrev.alpha = if (sheet.landVisitPrev.isEnabled) 1f else 0.35f
            sheet.landVisitNext.alpha = if (sheet.landVisitNext.isEnabled) 1f else 0.35f
            sheet.landVisitMonth.text = LandVisit.monthTitle(year, month)
            sheet.landVisitSelected.text = if (selected.isBlank()) {
                "تاریخ انتخاب نشده"
            } else {
                "بازدید: ${LandVisit.display(selected)}"
            }
            sheet.landVisitSelected.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selected.isBlank()) R.color.vinor_text_muted else R.color.vinor_accent_green,
                ),
            )
            val ready = selected.isNotBlank() &&
                LandVisit.customerError(
                    sheet.landVisitFirst.text?.toString().orEmpty(),
                    sheet.landVisitLast.text?.toString().orEmpty(),
                    sheet.landVisitPhone.text?.toString().orEmpty(),
                ).isEmpty()
            sheet.landVisitSubmit.alpha = if (ready) 1f else 0.45f
            val cells = LandVisit.monthCells(
                year,
                month,
                selected,
                minIso,
                maxIso,
                schedule.weekdays,
                schedule.hours,
                today,
                hour,
            )
            sheet.landVisitGrid.removeAllViews()
            val face = ResourcesCompat.getFont(requireContext(), R.font.vazirmatn)
            for (row in cells.chunked(7)) {
                val line = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                val padded = row + List(7 - row.size) { null }
                for (cell in padded) {
                    val holder = FrameLayout(requireContext())
                    val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
                    if (cell != null) {
                        val day = TextView(requireContext()).apply {
                            gravity = Gravity.CENTER
                            text = cell.label
                            textSize = 14f
                            typeface = face
                            setTypeface(typeface, Typeface.BOLD)
                        }
                        when {
                            cell.selected -> {
                                day.setBackgroundResource(R.drawable.bg_hub_start)
                                day.setTextColor(Color.parseColor("#052E16"))
                            }
                            !cell.enabled -> day.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_text_muted))
                            cell.today -> day.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_accent_green))
                            else -> day.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_text))
                        }
                        if (cell.enabled) {
                            day.setOnClickListener {
                                selected = cell.iso
                                sheet.landVisitError.isVisible = false
                                paint()
                            }
                        }
                        holder.addView(
                            day,
                            FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER),
                        )
                    }
                    line.addView(holder, lp)
                }
                sheet.landVisitGrid.addView(line)
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                sheet.landVisitError.isVisible = false
                paint()
            }
        }
        sheet.landVisitFirst.addTextChangedListener(watcher)
        sheet.landVisitLast.addTextChangedListener(watcher)
        sheet.landVisitPhone.addTextChangedListener(watcher)
        sheet.landVisitPrev.setOnClickListener {
            val next = LandVisit.shiftMonth(year, month, -1)
            year = next.first
            month = next.second
            paint()
        }
        sheet.landVisitNext.setOnClickListener {
            val next = LandVisit.shiftMonth(year, month, 1)
            year = next.first
            month = next.second
            paint()
        }
        sheet.landVisitSubmit.setOnClickListener {
            if (selected.isBlank()) {
                sheet.landVisitError.isVisible = true
                sheet.landVisitError.text = LandVisit.NEED_DATE
                return@setOnClickListener
            }
            val error = LandVisit.customerError(
                sheet.landVisitFirst.text?.toString().orEmpty(),
                sheet.landVisitLast.text?.toString().orEmpty(),
                sheet.landVisitPhone.text?.toString().orEmpty(),
            )
            if (error.isNotEmpty()) {
                sheet.landVisitError.isVisible = true
                sheet.landVisitError.text = error
                return@setOnClickListener
            }
            submitVisit(
                schedule,
                selected,
                LandVisit.customer(
                    sheet.landVisitFirst.text?.toString().orEmpty(),
                    sheet.landVisitLast.text?.toString().orEmpty(),
                    sheet.landVisitPhone.text?.toString().orEmpty(),
                ),
                sheet,
            )
        }
        paint()
        val dialog = BottomSheetDialog(requireContext())
        SystemUi.installSheet(sheet.root)
        dialog.setContentView(sheet.root)
        dialog.setOnShowListener {
            val panel = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            panel.setBackgroundColor(Color.TRANSPARENT)
            BottomSheetBehavior.from(panel).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        visitSheet?.dismiss()
        visitSheet = dialog
        dialog.show()
    }

    private fun submitVisit(
        schedule: LandVisit.Schedule,
        dateIso: String,
        customer: LandVisit.Customer,
        sheet: SheetLandVisitBinding,
    ) {
        val ctx = context ?: return
        if (visitBusy) return
        visitBusy = true
        sheet.landVisitSubmit.isEnabled = false
        sheet.landVisitSubmit.alpha = 0.45f
        scope.launch {
            try {
                val current = ListingRepository.currentCode(listingCode).ifBlank { listingCode.trim() }
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postForm(
                        LandVisit.submitPath(listingCode, schedule.requestUrl),
                        LandVisit.formFields(dateIso, customer),
                        schedule.csrf,
                        LandVisit.pagePath(Uri.encode(current)),
                        ctx,
                    )
                }
                if (_binding == null) return@launch
                if (FollowRequests.isUnauthorized(resp)) {
                    visitSheet?.dismiss()
                    (activity as? MainActivity)?.openLogin()
                    return@launch
                }
                if (!LandVisit.isOk(resp)) {
                    sheet.landVisitError.isVisible = true
                    sheet.landVisitError.text = LandVisit.failureMessage(resp)
                    return@launch
                }
                PartnerApi.forget(FollowRequests.DATA_PATH, ctx)
                NativePartnerFragment.forgetPage(NativePartnerFragment.PAGE_REQUESTS)
                Toast.makeText(ctx, LandVisit.successMessage(resp, dateIso), Toast.LENGTH_LONG).show()
                visitSheet?.dismiss()
                val redirect = resp.optString("redirect_url").trim()
                if (LandVisit.shouldFollowRedirect(redirect)) {
                    (activity as? MainActivity)?.openPath(redirect)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding != null && visitSheet?.isShowing == true) {
                    sheet.landVisitError.isVisible = true
                    sheet.landVisitError.text = VinorPageLoad.OFFLINE
                }
            } finally {
                visitBusy = false
                if (visitSheet?.isShowing == true) {
                    sheet.landVisitSubmit.isEnabled = true
                    sheet.landVisitSubmit.alpha = 1f
                }
            }
        }
    }

    private fun openDivar(code: String, divarUrl: String) {
        val url = divarUrl.ifBlank { "https://divar.ir/v/${code.substringAfter(':')}" }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun shareListing() {
        val title = listing.optString("title").trim()
        val price = _binding?.landDetailPrice?.text?.toString().orEmpty().trim()
        val link = publicLink()
        val text = listOf(title, price, link).filter { it.isNotBlank() }.joinToString("\n")
        if (text.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "اشتراک‌گذاری"))
    }

    private fun publicLink(): String {
        val code = listingCode.trim()
        if (code.startsWith("divar:", ignoreCase = true)) {
            val given = listing.optString("divar_url").trim()
            if (given.isNotBlank()) return given
            return "https://divar.ir/v/${code.substringAfter(':')}"
        }
        if (code.isBlank()) return ""
        return "https://vinor.ir/express/partner/lands/$code"
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
        if (urls.isEmpty()) {
            binding.landDetailGallery.isVisible = false
            binding.landDetailGallery.adapter = null
            binding.landDetailImage.isVisible = true
            binding.landDetailCounter.isVisible = false
            return
        }
        binding.landDetailImage.isVisible = false
        binding.landDetailGallery.isVisible = true
        binding.landDetailGallery.adapter = GalleryAdapter(urls)
        binding.landDetailGallery.setCurrentItem(0, false)
        paintCounter(0)
    }

    private fun paintCounter(position: Int) {
        val b = _binding ?: return
        if (galleryCount <= 1) {
            b.landDetailCounter.isVisible = false
            return
        }
        val idx = position.coerceIn(0, galleryCount - 1) + 1
        b.landDetailCounter.isVisible = true
        b.landDetailCounter.text = "${PartnerFormat.digits(idx)} از ${PartnerFormat.digits(galleryCount)}"
    }

    private fun bindFacts(rows: JSONArray) {
        val host = binding.landDetailFacts
        host.removeAllViews()
        val items = LandDetailRole.highlights(rows)
        binding.landDetailFactsScroll.isVisible = items.isNotEmpty()
        val gap = dp(8)
        items.forEachIndexed { index, text ->
            val chip = TextView(requireContext()).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(context, R.color.vinor_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.vazirmatn)
                background = ContextCompat.getDrawable(context, R.drawable.bg_ld_fact)
                gravity = android.view.Gravity.CENTER
                minHeight = dp(32)
                val pad = dp(12)
                setPadding(pad, 0, pad, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(32),
                ).apply {
                    if (index != items.lastIndex) marginEnd = gap
                }
            }
            host.addView(chip)
        }
    }

    private fun bindBody(raw: String) {
        val text = raw.trim()
        binding.landDetailDescLabel.isVisible = text.isNotBlank()
        binding.landDetailBody.isVisible = text.isNotBlank()
        binding.landDetailBody.text = text
    }

    private fun bindPublish(
        code: String,
        obj: JSONObject,
        hasSession: Boolean,
    ) {
        val canPublish = obj.optBoolean("can_publish")
        binding.landDetailPublish.isVisible = LandDetailRole.showPublish(hasSession, canPublish)
        binding.landDetailPayRow.isVisible = canPublish
        binding.landDetailCommissionRow.isVisible = canPublish
        binding.landDetailSendAll.isVisible = canPublish
        if (!canPublish) return
        binding.landDetailPublishTitle.text = "انتشار برای فروش"
        binding.landDetailPublishHint.text = "بعد از انتشار، این ملک در آگهی‌های وینور دیده می‌شود."
        binding.landDetailPublishCta.isVisible = true
        paintPublishCta()
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
        val body = MyPropertyManage.publishBody(code, selectedPayment, pct, binding.landDetailSendAll.isChecked)
        publishing = true
        binding.landDetailPublishCta.alpha = 0.6f
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    PartnerApi.postJson(MyPropertyManage.PUBLISH_PATH, body, ctx)
                }
                val land = if (fresh.optBoolean("ok")) {
                    withContext(Dispatchers.IO) {
                        val path = apiPath(code)
                        ListingMemory.invalidateLand(code, ctx, path)
                        PartnerApi.get(path, ctx)
                    }
                } else {
                    null
                }
                if (_binding == null) return@launch
                if (MyPropertyManage.publishAccepted(fresh, land)) {
                    Toast.makeText(ctx, fresh.optString("message").ifBlank { "ملک برای فروش منتشر شد." }, Toast.LENGTH_SHORT).show()
                    load(code)
                } else {
                    Toast.makeText(
                        ctx,
                        MyPropertyManage.unpublishFailure(fresh, "انتشار ناموفق بود."),
                        Toast.LENGTH_SHORT,
                    ).show()
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
            binding.landDetailSpecs.isVisible = false
            return
        }
        binding.landDetailRows.isVisible = true
        binding.landDetailSpecs.isVisible = true
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
        val any = binding.landDetailRows.childCount > 0
        binding.landDetailRows.isVisible = any
        binding.landDetailSpecs.isVisible = any
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun paintPublishCta() {
        val cta = binding.landDetailPublishCta
        cta.setBackgroundResource(R.drawable.bg_hub_start)
        cta.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_customer_bg))
    }

    private inner class GalleryAdapter(
        private val urls: List<String>,
    ) : RecyclerView.Adapter<GalleryAdapter.Holder>() {
        inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ContextCompat.getColor(context, R.color.vinor_raised_2))
            }
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            PartnerImages.bind(scope, holder.image, urls[position])
        }

        override fun getItemCount(): Int = urls.size
    }

    companion object {
        private val remembered = HashMap<String, JSONObject>()
        private var sessionStamp = 0

        fun missingMessage(payload: JSONObject): String {
            val error = payload.optString("error").trim()
            if (error.isNotEmpty() && !error.equals("null", true)) return error
            return "آگهی پیدا نشد."
        }

        fun clearRemembered() {
            remembered.clear()
            sessionStamp++
        }

        internal fun remember(code: String, obj: JSONObject) {
            val clean = code.trim()
            if (clean.isEmpty() || !VinorPageLoad.canRemember(obj)) return
            val copy = VinorPageLoad.rememberedCopy(obj)
            ListingRepository.ingest(copy)
            remembered[clean] = copy
        }

        internal fun peekRemembered(code: String): JSONObject? = remembered[code.trim()]

        internal fun dropRemembered(code: String) {
            remembered.remove(code.trim())
            sessionStamp++
        }
    }
}
