package ir.vinor.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import ir.vinor.app.databinding.FragmentSearchBinding
import ir.vinor.app.databinding.ItemAdsAllRowBinding
import ir.vinor.app.databinding.SheetSearchFilterBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** تب جستجو — پیش‌فرض همه استان‌ها، آگهی‌های منتشرشده از جدید به قدیم. */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var rawAds: List<SearchAd> = emptyList()
    private var query = SearchQuery()
    private var ui = SearchStateReducer.loading()
    private var approved = false
    private var hasSnapshot = false
    private var suppressQueryWatch = false
    private var searchDebounce: Runnable? = null
    private var shimmer: ValueAnimator? = null
    private var sheetDialog: BottomSheetDialog? = null
    private var loadGen = 0
    private var skipNextResume = true

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        if (_binding == null || !isAdded) return@OnScrollChangedListener
        bindVisibleImages()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        skipNextResume = true
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.searchRefresh.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.vinor_accent_green))
        binding.searchRefresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(requireContext(), R.color.vinor_raised))
        binding.searchRefresh.setOnRefreshListener { load(fromPull = true) }
        binding.searchClear.setOnClickListener {
            binding.searchInput.setText("")
            binding.searchInput.requestFocus()
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.searchClear.isVisible = !s.isNullOrEmpty()
                if (suppressQueryWatch) return
                scheduleQuery(s?.toString().orEmpty())
            }
        })
        binding.searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            searchDebounce?.let { v.removeCallbacks(it) }
            query = query.copy(q = v.text?.toString().orEmpty())
            publish()
            true
        }
        binding.searchChipClear.setOnClickListener { clearFilters() }
        binding.searchEmptyClear.setOnClickListener { clearFilters() }
        binding.searchRetry.setOnClickListener { load() }
        binding.searchChipSort.setOnClickListener { showSortSheet() }
        binding.searchChipDistrict.setOnClickListener { showDistrictSheet() }
        binding.searchChipPrice.setOnClickListener { showPriceSheet() }
        binding.searchChipArea.setOnClickListener { showAreaSheet() }
        binding.searchCity.setOnClickListener { showCitySheet() }
        binding.searchScroll.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        if (rememberedAds != null) paintRemembered()
        load(forceSkeleton = rememberedAds == null)
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResume) {
            skipNextResume = false
            return
        }
        if (_binding != null) load(forceSkeleton = !hasSnapshot)
    }

    override fun onDestroyView() {
        _binding?.searchInput?.let { input -> searchDebounce?.let { input.removeCallbacks(it) } }
        stopShimmer()
        sheetDialog?.dismiss()
        sheetDialog = null
        runCatching { binding.searchScroll.viewTreeObserver.removeOnScrollChangedListener(scrollListener) }
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun load(fromPull: Boolean = false, forceSkeleton: Boolean = false) {
        val kept = ui.items.takeIf {
            it.isNotEmpty() && (ui.phase == SearchPhase.CONTENT || ui.phase == SearchPhase.ERROR)
        }.orEmpty()
        if (kept.isEmpty() && (forceSkeleton || !hasSnapshot) && !fromPull) {
            ui = SearchStateReducer.loading()
            render()
        }
        val ctx = context ?: return
        val gen = ++loadGen
        val stamp = sessionStamp
        scope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { fetchAds(ctx) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != loadGen || stamp != sessionStamp || _binding == null) return@launch
            binding.searchRefresh.isRefreshing = false
            if (loaded == null || loaded.failed) {
                if (loaded?.sessionExpired == true && kept.isEmpty() && rememberedAds.isNullOrEmpty()) {
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                val previous = if (kept.isNotEmpty()) {
                    SearchUiState(SearchPhase.CONTENT, kept, query.hasClearableFilters())
                } else {
                    SearchStateReducer.loading()
                }
                ui = SearchStateReducer.onNetworkError(previous)
                render()
                if (ui.items.isNotEmpty()) showOfflineBar()
                return@launch
            }
            approved = loaded.approved
            rawAds = loaded.ads
            hasSnapshot = true
            rememberedAds = loaded.ads
            rememberedApproved = loaded.approved
            ui = SearchStateReducer.onLoaded(SearchAds.apply(rawAds, query), query.hasClearableFilters())
            render()
        }
    }

    private fun paintRemembered() {
        val snap = rememberedAds ?: return
        rawAds = snap
        approved = rememberedApproved
        hasSnapshot = true
        ui = SearchStateReducer.onLoaded(SearchAds.apply(rawAds, query), query.hasClearableFilters())
        render()
    }

    private fun fetchAds(ctx: Context): SearchLoad {
        val searchHtml = runCatching {
            PartnerApi.getHtml("/express/partner/search?sort=newest", ctx)
        }.getOrDefault("")
        val searchRows = SearchPageHtml.parse(searchHtml)
        val home = runCatching { PartnerApi.get("/express/partner/home/data", ctx) }.getOrNull()
        val homeAds = if (home == null) {
            emptyList()
        } else {
            SearchAds.fromObjects(home.optJSONArray("divar_pro_ads") ?: JSONArray()) +
                SearchAds.fromObjects(home.optJSONArray("suggested_files") ?: JSONArray())
        }
        val homeHtmlAds = if (searchRows.isEmpty() && homeAds.isEmpty()) {
            val html = runCatching { PartnerApi.getHtml("/express/partner/home", ctx) }.getOrDefault("")
            SearchAds.fromObjects(HomeAdsHtml.parse(html))
        } else {
            emptyList()
        }
        val primary = SearchAds.catalog(searchRows, homeAds, homeHtmlAds)
        val ads = if (primary.isNotEmpty()) primary else publicPublished(ctx)
        val failed = ads.isEmpty() && (home == null || isFailedHome(home))
        val sessionExpired = home?.optBoolean("session_expired") == true || home?.optInt("http_status") == 401
        return SearchLoad(ads, home?.optBoolean("is_approved") == true, failed, sessionExpired)
    }

    /** مهمان ردیف SSR ندارد؛ sitemap نشانی ملک‌های منتشرشدهٔ عمومی است. */
    private fun publicPublished(ctx: Context): List<SearchAd> {
        val xml = runCatching { PartnerApi.getHtml(PublicSearchCatalog.SITEMAP_PATH, ctx) }.getOrDefault("")
        val refs = PublicSearchCatalog.landRefs(xml).take(PublicSearchCatalog.MAX)
        val out = ArrayList<SearchAd>(refs.size)
        for (ref in refs) {
            val obj = runCatching {
                PartnerApi.get("/express/partner/api/lands/${Uri.encode(ref.code)}/data", ctx)
            }.getOrNull() ?: continue
            if (obj.has("http_status") && obj.optInt("http_status") !in 200..299) continue
            val ad = PublicSearchCatalog.fromDetail(obj, ref.lastmod, out.size) ?: continue
            out += ad
        }
        return SearchAds.dedupe(out)
    }

    private fun publish() {
        if (!hasSnapshot) {
            bindChips()
            return
        }
        ui = SearchStateReducer.onLoaded(SearchAds.apply(rawAds, query), query.hasClearableFilters())
        render()
        _binding?.searchScroll?.scrollTo(0, 0)
    }

    private fun clearFilters() {
        query = query.clearAll()
        val b = _binding ?: return
        suppressQueryWatch = true
        b.searchInput.setText("")
        suppressQueryWatch = false
        b.searchClear.isVisible = false
        publish()
    }

    private fun scheduleQuery(text: String) {
        val b = _binding ?: return
        searchDebounce?.let { b.searchInput.removeCallbacks(it) }
        val task = Runnable {
            query = query.copy(q = text)
            publish()
        }
        searchDebounce = task
        b.searchInput.postDelayed(task, 400)
    }

    private fun render() {
        val b = _binding ?: return
        val showList = ui.phase == SearchPhase.CONTENT || (ui.phase == SearchPhase.ERROR && ui.items.isNotEmpty())
        val showSkeleton = ui.phase == SearchPhase.LOADING && ui.items.isEmpty()
        val showEmpty = ui.phase == SearchPhase.EMPTY
        val showError = ui.phase == SearchPhase.ERROR && ui.items.isEmpty()
        b.searchSkeleton.isVisible = showSkeleton
        b.searchEmptyState.isVisible = showEmpty
        b.searchErrorState.isVisible = showError
        b.searchList.isVisible = showList
        if (showSkeleton) startShimmer() else stopShimmer()
        if (showEmpty) {
            val filters = query.hasClearableFilters()
            b.searchEmptyTitle.text = if (filters) "آگهی‌ای با این فیلترها پیدا نشد" else "فعلاً آگهی‌ای برای نمایش نیست"
            b.searchEmptySub.isVisible = filters
            b.searchEmptyClear.isVisible = filters
        }
        if (showList) {
            bindRows(ui.items)
            b.searchList.post { bindVisibleImages() }
        } else {
            b.searchList.removeAllViews()
        }
        val count = if (showList) ui.items.size else 0
        b.searchCount.isVisible = count > 0
        if (count > 0) b.searchCount.text = "${PartnerFormat.digits(count)} آگهی"
        bindChips()
    }

    private fun bindChips() {
        val b = _binding ?: return
        b.searchChipClear.isVisible = query.hasClearableFilters()
        val sortActive = query.sort != SearchSort.NEWEST
        b.searchChipSort.text = "${query.sortChipLabel()} ▾"
        b.searchChipSort.isSelected = sortActive
        b.searchChipSort.contentDescription = if (sortActive) {
            "مرتب‌سازی، انتخاب‌شده: ${query.sortChipLabel()}"
        } else {
            "مرتب‌سازی، جدیدترین"
        }
        val district = query.districtChipLabel()
        b.searchChipDistrict.text = "${district ?: "محله"} ▾"
        b.searchChipDistrict.isSelected = district != null
        b.searchChipDistrict.contentDescription = if (district == null) {
            "فیلتر محله"
        } else {
            "فیلتر محله، انتخاب‌شده: $district"
        }
        val price = query.priceChipLabel()
        b.searchChipPrice.text = "${price ?: "قیمت"} ▾"
        b.searchChipPrice.isSelected = price != null
        b.searchChipPrice.contentDescription = if (price == null) {
            "فیلتر قیمت"
        } else {
            "فیلتر قیمت، انتخاب‌شده: ${price.replace("\u2013", " تا ")}"
        }
        val area = query.areaChipLabel()
        b.searchChipArea.text = "${area ?: "متراژ"} ▾"
        b.searchChipArea.isSelected = area != null
        b.searchChipArea.contentDescription = if (area == null) {
            "فیلتر متراژ"
        } else {
            "فیلتر متراژ، انتخاب‌شده: ${area.replace("\u2013", " تا ")}"
        }
        val city = query.city.trim()
        b.searchCity.text = if (city.isEmpty()) "${SearchQuery.ALL_PROVINCES} ▾" else "$city ▾"
        b.searchCity.isSelected = city.isNotEmpty()
        b.searchCity.contentDescription = if (city.isEmpty()) {
            SearchQuery.ALL_PROVINCES
        } else {
            "شهر، انتخاب‌شده: $city"
        }
    }

    private fun bindRows(items: List<SearchAd>) {
        val b = _binding ?: return
        b.searchList.removeAllViews()
        items.forEachIndexed { index, ad ->
            val row = ItemAdsAllRowBinding.inflate(layoutInflater, b.searchList, false)
            row.adsAllTitle.text = ad.title
            val price = SearchAds.cardPrice(ad.priceRaw)
            row.adsAllPrice.text = price
            val area = SearchAds.cardArea(ad.areaRaw)
            row.adsAllArea.isVisible = area.isNotBlank()
            row.adsAllArea.text = area
            val loc = SearchAds.cardPlace(ad)
            row.adsAllLoc.text = loc
            row.adsAllLoc.isVisible = loc.isNotBlank()
            if (approved && ad.commissionAmount > 0) {
                row.adsAllComm.isVisible = true
                row.adsAllComm.text = "پورسانت ${PartnerFormat.toman(ad.commissionAmount)} تومان"
            } else {
                row.adsAllComm.isVisible = false
            }
            row.adsAllShots.isVisible = ad.imageCount > 1
            if (ad.imageCount > 1) {
                row.adsAllShots.text = PartnerFormat.digits(ad.imageCount)
                val camera = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_camera_small)?.mutate()
                camera?.setTint(ContextCompat.getColor(requireContext(), R.color.vinor_text))
                row.adsAllShots.setCompoundDrawablesRelativeWithIntrinsicBounds(camera, null, null, null)
            }
            row.adsAllDivider.isVisible = index != items.lastIndex
            PartnerImages.roundClip(row.adsAllThumbWrap, 12f)
            row.adsAllThumb.tag = ad.imageUrl
            row.root.contentDescription = listOf(ad.title, price, area, loc).filter { it.isNotBlank() }.joinToString("، ")
            row.root.setOnClickListener { openAd(ad) }
            b.searchList.addView(row.root)
        }
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
            val placeholder = child.findViewById<View>(R.id.adsAllPlaceholder)
            PartnerImages.bind(scope, img, img.tag as? String, placeholder)
        }
    }

    private fun showPriceSheet() {
        showRangeSheet(
            title = "قیمت",
            minHint = "حداقل (تومان)",
            maxHint = "حداکثر (تومان)",
            initialMin = query.priceMin,
            initialMax = query.priceMax,
            presets = pricePresets,
        ) { min, max ->
            query = query.copy(priceMin = min, priceMax = max).normalized()
            publish()
        }
    }

    private fun showAreaSheet() {
        showRangeSheet(
            title = "متراژ",
            minHint = "حداقل (متر)",
            maxHint = "حداکثر (متر)",
            initialMin = query.areaMin,
            initialMax = query.areaMax,
            presets = areaPresets,
        ) { min, max ->
            query = query.copy(areaMin = min, areaMax = max).normalized()
            publish()
        }
    }

    private fun showRangeSheet(
        title: String,
        minHint: String,
        maxHint: String,
        initialMin: Long?,
        initialMax: Long?,
        presets: List<RangePreset>,
        onApply: (Long?, Long?) -> Unit,
    ) {
        val session = openSheet(title) ?: return
        var min = initialMin
        var max = initialMax
        var writing = false
        val minField = amountField(minHint)
        val maxField = amountField(maxHint)
        val presetViews = presets.map { preset -> preset to optionRow(preset.label, false) {} }
        fun paint() {
            writing = true
            setAmount(minField, min)
            setAmount(maxField, max)
            writing = false
            presetViews.forEach { (preset, view) ->
                styleOption(view, preset.min == min && preset.max == max)
            }
        }
        fun read(field: EditText, assign: (Long?) -> Unit) {
            if (writing) return
            val parsed = SearchQuery.parseAmount(field.text?.toString())
            assign(parsed)
            val formatted = parsed?.let { PartnerFormat.toman(it) }.orEmpty()
            if (parsed != null && formatted != field.text?.toString()) {
                writing = true
                field.setText(formatted)
                field.setSelection(formatted.length)
                writing = false
            }
            presetViews.forEach { (preset, view) ->
                styleOption(view, preset.min == min && preset.max == max)
            }
        }
        minField.addTextChangedListener(simpleWatcher { read(minField) { min = it } })
        maxField.addTextChangedListener(simpleWatcher { read(maxField) { max = it } })
        session.sheet.sheetBody.addView(minField)
        session.sheet.sheetBody.addView(maxField)
        presetViews.forEach { (preset, view) ->
            view.setOnClickListener {
                min = preset.min
                max = preset.max
                paint()
            }
            session.sheet.sheetBody.addView(view)
        }
        paint()
        session.sheet.sheetApply.setOnClickListener {
            onApply(SearchQuery.parseAmount(minField.text?.toString()), SearchQuery.parseAmount(maxField.text?.toString()))
            session.dialog.dismiss()
        }
        session.sheet.sheetClear.setOnClickListener {
            min = null
            max = null
            paint()
        }
        session.dialog.show()
    }

    private fun showSortSheet() {
        val session = openSheet("مرتب‌سازی") ?: return
        var selected = query.sort
        val rows = SearchSort.values().map { sort ->
            sort to optionRow(sort.label, sort == selected) {}
        }
        fun paint() {
            rows.forEach { (sort, view) -> styleOption(view, sort == selected) }
        }
        rows.forEach { (sort, view) ->
            view.setOnClickListener {
                selected = sort
                paint()
            }
            session.sheet.sheetBody.addView(view)
        }
        paint()
        session.sheet.sheetApply.setOnClickListener {
            query = query.copy(sort = selected)
            publish()
            session.dialog.dismiss()
        }
        session.sheet.sheetClear.setOnClickListener {
            selected = SearchSort.NEWEST
            paint()
        }
        session.dialog.show()
    }

    private fun showDistrictSheet() {
        val session = openSheet("محله") ?: return
        val all = knownDistricts()
        val selected = query.districts.toMutableSet()
        val filter = textField("جستجوی محله")
        val custom = textField("افزودن محله")
        val list = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun paint() {
            list.removeAllViews()
            val needle = SearchAds.norm(filter.text?.toString().orEmpty())
            val shown = all.filter { needle.isEmpty() || SearchAds.norm(it).contains(needle) }
            if (shown.isEmpty()) list.addView(noteText("محله‌ای در آگهی‌های فعلی پیدا نشد"))
            shown.forEach { name ->
                list.addView(optionRow(name, name in selected) {
                    if (!selected.add(name)) selected.remove(name)
                    paint()
                })
            }
        }
        filter.addTextChangedListener(simpleWatcher { paint() })
        session.sheet.sheetBody.addView(filter)
        session.sheet.sheetBody.addView(list)
        session.sheet.sheetBody.addView(custom)
        paint()
        session.sheet.sheetApply.setOnClickListener {
            val extra = custom.text?.toString()?.trim().orEmpty()
            if (extra.isNotEmpty()) selected.add(extra)
            query = query.copy(districts = selected.toList()).normalized()
            publish()
            session.dialog.dismiss()
        }
        session.sheet.sheetClear.setOnClickListener {
            selected.clear()
            custom.setText("")
            paint()
        }
        session.dialog.show()
    }

    private fun showCitySheet() {
        val session = openSheet("شهر") ?: return
        var selected = query.city.trim()
        val choices = listOf("" to SearchQuery.ALL_PROVINCES) + knownCities().map { it to it }
        val rows = choices.map { (value, label) ->
            Triple(value, label, optionRow(label, value == selected) {})
        }
        fun paint() {
            rows.forEach { (value, _, view) -> styleOption(view, value == selected) }
        }
        rows.forEach { (value, _, view) ->
            view.setOnClickListener {
                selected = if (value.isEmpty()) "" else if (selected == value) "" else value
                paint()
            }
            session.sheet.sheetBody.addView(view)
        }
        paint()
        session.sheet.sheetApply.setOnClickListener {
            query = query.copy(city = selected).normalized()
            publish()
            session.dialog.dismiss()
        }
        session.sheet.sheetClear.setOnClickListener {
            selected = ""
            paint()
        }
        session.dialog.show()
    }

    private fun openSheet(title: String): SheetSession? {
        if (!isAdded) return null
        sheetDialog?.dismiss()
        val dialog = BottomSheetDialog(requireContext())
        val sheet = SheetSearchFilterBinding.inflate(layoutInflater)
        sheet.sheetTitle.text = title
        dialog.setContentView(sheet.root)
        dialog.setOnShowListener {
            val panel = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            panel.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            BottomSheetBehavior.from(panel).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        sheet.sheetClose.setOnClickListener { dialog.dismiss() }
        sheetDialog = dialog
        return SheetSession(dialog, sheet)
    }

    private fun startShimmer() {
        if (shimmer != null || _binding == null) return
        val blocks = skeletonBlocks()
        val base = ContextCompat.getColor(requireContext(), R.color.vinor_raised)
        val hi = ContextCompat.getColor(requireContext(), R.color.vinor_skeleton_bg)
        if (!motionEnabled()) {
            blocks.forEach { it.setBackgroundColor(base) }
            return
        }
        shimmer = ValueAnimator.ofArgb(base, hi).apply {
            duration = 1200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val color = anim.animatedValue as Int
                blocks.forEach { it.setBackgroundColor(color) }
            }
            start()
        }
    }

    private fun stopShimmer() {
        shimmer?.cancel()
        shimmer = null
    }

    private fun skeletonBlocks(): List<View> {
        val root = _binding?.searchSkeleton ?: return emptyList()
        val out = ArrayList<View>()
        fun walk(view: View) {
            if (view.tag == "sk") out.add(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun motionEnabled(): Boolean {
        return try {
            Settings.Global.getFloat(requireContext().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (_: Exception) {
            true
        }
    }

    private fun showOfflineBar() {
        val b = _binding ?: return
        val bar = Snackbar.make(b.root, "اتصال برقرار نشد", Snackbar.LENGTH_LONG)
        bar.setAction("تلاش دوباره") { load() }
        bar.setActionTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_accent_green))
        bar.view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.vinor_raised))
        bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.let { text ->
            text.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_text))
            text.typeface = ResourcesCompat.getFont(requireContext(), R.font.vazirmatn)
        }
        bar.show()
    }

    private fun knownDistricts(): List<String> {
        val set = linkedSetOf<String>()
        query.districts.forEach { if (it.isNotBlank()) set.add(it.trim()) }
        rawAds.forEach { ad ->
            val name = ad.district.ifBlank { SearchAds.districtFromLocation(ad.location) }
            if (name.isNotBlank()) set.add(name.trim())
        }
        return set.toList()
    }

    private fun knownCities(): List<String> {
        val set = linkedSetOf<String>()
        if (query.city.isNotBlank()) set.add(query.city.trim())
        rawAds.forEach { if (it.city.isNotBlank()) set.add(it.city.trim()) }
        if ("تهران" !in set) return listOf("تهران") + set
        return set.toList()
    }

    private fun openAd(ad: SearchAd) {
        val code = SearchAds.openCode(ad)
        if (code.isNotBlank()) {
            (activity as? MainActivity)?.openLand(code)
            return
        }
        if (ad.url.isNotBlank()) openVinorOrExternal(ad.url)
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

    private fun isFailedHome(obj: JSONObject): Boolean {
        val ads = obj.optJSONArray("divar_pro_ads")
        if (ads != null && ads.length() > 0) return false
        if (obj.optBoolean("offline")) return true
        if (obj.has("http_status")) {
            val code = obj.optInt("http_status")
            if (code == 0 || code !in 200..299) return true
        }
        return obj.has("ok") && !obj.optBoolean("ok") && ads == null
    }

    private fun amountField(hint: String): EditText = textField(hint).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun textField(hint: String): EditText {
        return EditText(requireContext()).apply {
            this.hint = hint
            setHintTextColor(ContextCompat.getColor(context, R.color.vinor_text_muted))
            setTextColor(ContextCompat.getColor(context, R.color.vinor_text))
            textSize = 15f
            typeface = ResourcesCompat.getFont(context, R.font.vazirmatn)
            background = ContextCompat.getDrawable(context, R.drawable.bg_search_field)
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            textDirection = View.TEXT_DIRECTION_RTL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            val pad = dp(12)
            setPadding(pad, 0, pad, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun optionRow(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(requireContext()).apply {
            text = label
            textSize = 15f
            typeface = ResourcesCompat.getFont(context, R.font.vazirmatn)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            val pad = dp(16)
            setPadding(pad, 0, pad, 0)
            styleOption(this, selected)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { onClick() }
        }
    }

    private fun styleOption(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_search_option_on else R.drawable.bg_search_option)
        view.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (selected) R.color.vinor_on_primary else R.color.vinor_text,
            ),
        )
    }

    private fun noteText(message: String): TextView {
        return TextView(requireContext()).apply {
            text = message
            setTextColor(ContextCompat.getColor(context, R.color.vinor_text_muted))
            textSize = 12f
            typeface = ResourcesCompat.getFont(context, R.font.vazirmatn)
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun setAmount(field: EditText, value: Long?) {
        val text = value?.let { PartnerFormat.toman(it) }.orEmpty()
        if (field.text?.toString() == text) return
        field.setText(text)
        field.setSelection(field.text?.length ?: 0)
    }

    private fun simpleWatcher(after: () -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                after()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class RangePreset(val label: String, val min: Long?, val max: Long?)

    private data class SearchLoad(
        val ads: List<SearchAd>,
        val approved: Boolean,
        val failed: Boolean,
        val sessionExpired: Boolean = false,
    )

    private data class SheetSession(val dialog: BottomSheetDialog, val sheet: SheetSearchFilterBinding)

    companion object {
        private var rememberedAds: List<SearchAd>? = null
        private var rememberedApproved = false
        private var sessionStamp = 0

        fun clearRemembered() {
            rememberedAds = null
            rememberedApproved = false
            sessionStamp++
        }

        private val pricePresets = listOf(
            RangePreset("تا ۱ میلیارد", null, 1_000_000_000),
            RangePreset("۱–۳ میلیارد", 1_000_000_000, 3_000_000_000),
            RangePreset("۳–۵ میلیارد", 3_000_000_000, 5_000_000_000),
            RangePreset("۵–۱۰ میلیارد", 5_000_000_000, 10_000_000_000),
            RangePreset("بالای ۱۰ میلیارد", 10_000_000_000, null),
        )
        private val areaPresets = listOf(
            RangePreset("تا ۷۵", null, 75),
            RangePreset("۷۵–۱۰۰", 75, 100),
            RangePreset("۱۰۰–۱۵۰", 100, 150),
            RangePreset("۱۵۰–۲۰۰", 150, 200),
            RangePreset("بالای ۲۰۰", 200, null),
        )
    }
}
