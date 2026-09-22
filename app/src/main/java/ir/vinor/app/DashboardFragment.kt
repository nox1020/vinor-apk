package ir.vinor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import ir.vinor.app.databinding.FragmentDashboardBinding
import ir.vinor.app.databinding.ItemAdsAllRowBinding
import ir.vinor.app.databinding.ItemChatBubbleBinding
import ir.vinor.app.databinding.ItemHomeAdBinding
import ir.vinor.app.databinding.ItemLandCardBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** خانه نیتیو — همان چیدمان وب خالی: آگهی، خبر، کارت همکاری. چت و کامپوزر در تب چت. */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var threadId: String? = null
    private val landsByCode = linkedMapOf<String, JSONObject>()
    private val newsItems = mutableListOf<String>()
    private var newsIndex = 0
    private var allAds = JSONArray()
    private var lastAdsKey = ""
    private var isApproved = false
    private var pendingAttachment: JSONObject? = null
    private var attachUploading = false
    private var cameraOutput: Uri? = null
    private var cachedHistory: JSONObject? = null
    private var chatLayerOpen = false
    private var wantChatLayer = false
    private var chatBindGen = 0
    private var boundMessageCount = 0
    private val newsHandler = Handler(Looper.getMainLooper())
    private val adsScrollListener = ViewTreeObserver.OnScrollChangedListener { bindVisibleCarouselImages() }
    private val adsAllScrollListener = ViewTreeObserver.OnScrollChangedListener { bindVisibleAdsAllImages() }
    private val pickGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startUpload(it, "image") }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startUpload(it, "video") }
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startUpload(it, "file") }
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraOutput
        if (ok && uri != null) startUpload(uri, "image")
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), "برای عکس اجازهٔ دوربین لازم است.", Toast.LENGTH_SHORT).show()
    }
    private val newsTick = object : Runnable {
        override fun run() {
            val b = _binding ?: return
            if (newsItems.isEmpty() || !b.dashboardEmpty.isVisible) return
            newsIndex = (newsIndex + 1) % newsItems.size
            val next = newsItems[newsIndex]
            b.dashboardNewsText.animate().cancel()
            b.dashboardNewsText.animate().alpha(0f).setDuration(180).withEndAction {
                val live = _binding ?: return@withEndAction
                live.dashboardNewsText.text = next
                live.dashboardNewsText.animate().alpha(1f).setDuration(220).start()
            }.start()
            newsHandler.postDelayed(this, 4000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.dashboardMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        binding.dashboardBrand.setOnClickListener {
            (activity as? MainActivity)?.closeDrawer()
            showHomeEntry()
        }
        binding.dashboardChatSend.setOnClickListener { sendChat() }
        binding.dashboardChatPlus.setOnClickListener { toggleAttach() }
        binding.dashboardAttachScrim.setOnClickListener { closeAttach() }
        binding.dashboardAttachGallery.setOnClickListener { onAttachKind("gallery") }
        binding.dashboardAttachCamera.setOnClickListener { onAttachKind("camera") }
        binding.dashboardAttachVideo.setOnClickListener { onAttachKind("video") }
        binding.dashboardAttachFile.setOnClickListener { onAttachKind("file") }
        binding.dashboardAttachRemove.setOnClickListener { clearPendingAttachment() }
        binding.dashboardHero.setOnClickListener { onHero() }
        binding.dashboardTileBuy.setOnClickListener { openAdsAll() }
        binding.dashboardTileLevels.setOnClickListener {
            (activity as? MainActivity)?.openPath("/express/partner/levels")
        }
        binding.dashboardAdsAllBackdrop.setOnClickListener { closeAdsAll() }
        binding.dashboardAdsAllClose.setOnClickListener { closeAdsAll() }
        binding.dashboardCustomerBackdrop.setOnClickListener { closeCustomer() }
        binding.dashboardCustomerClose.setOnClickListener { closeCustomer() }
        binding.dashboardCustomerSubmit.setOnClickListener { submitCustomer() }
        binding.dashboardNewsDot.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.news_dot_pulse))
        val headerPad = (56 * resources.displayMetrics.density).toInt()
        binding.dashboardRefresh.setColorSchemeColors(Color.parseColor("#10B981"))
        binding.dashboardRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1C1C1E"))
        binding.dashboardRefresh.setProgressViewOffset(true, headerPad / 2, headerPad + (36 * resources.displayMetrics.density).toInt())
        binding.dashboardRefresh.setOnRefreshListener { onPullRefresh() }
        binding.dashboardChatInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { syncSendEnabled() }
        })
        binding.dashboardChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChat()
                true
            } else false
        }
        val adsOrigin = FloatArray(2)
        binding.dashboardAdsScroll.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    adsOrigin[0] = event.x
                    adsOrigin[1] = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - adsOrigin[0])
                    val dy = kotlin.math.abs(event.y - adsOrigin[1])
                    v.parent.requestDisallowInterceptTouchEvent(dx > dy + 8f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        binding.dashboardAdsScroll.viewTreeObserver.addOnScrollChangedListener(adsScrollListener)
        (binding.dashboardAdsAllList.parent as? View)?.viewTreeObserver?.addOnScrollChangedListener(adsAllScrollListener)
        syncSendEnabled()
        syncComposerVisible()
        loadAll()
        if ((activity as? MainActivity)?.consumePendingVinorChat() == true) {
            openVinorChat()
        }
    }

    override fun onDestroyView() {
        runCatching { binding.dashboardAdsScroll.viewTreeObserver.removeOnScrollChangedListener(adsScrollListener) }
        runCatching {
            (binding.dashboardAdsAllList.parent as? View)?.viewTreeObserver
                ?.removeOnScrollChangedListener(adsAllScrollListener)
        }
        newsHandler.removeCallbacks(newsTick)
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    fun reload() {
        if (_binding == null) return
        showHomeEntry()
        loadAll()
    }

    private fun onPullRefresh() {
        val b = _binding ?: return
        if (b.dashboardAdsAll.isVisible || b.dashboardCustomer.isVisible || b.dashboardAttachSheet.isVisible) {
            b.dashboardRefresh.isRefreshing = false
            return
        }
        if (chatLayerOpen || wantChatLayer) {
            fetchHistory(bindIfWanted = true)
            return
        }
        loadAll(fromPull = true)
    }

    private fun finishRefreshUi() {
        val b = _binding ?: return
        b.dashboardProgress.isVisible = false
        b.dashboardRefresh.isRefreshing = false
    }

    fun isChatOpen(): Boolean = chatLayerOpen && wantChatLayer

    /** تب آگهی‌ها: لایهٔ چت را ببند، آگهی‌ها را نشان بده. */
    fun showHomeEntry() {
        wantChatLayer = false
        chatBindGen += 1
        showHomeEmpty()
        (activity as? MainActivity)?.syncDivarTab()
    }

    private fun syncComposerVisible() {
        val b = _binding ?: return
        val inChat = chatLayerOpen && wantChatLayer
        b.dashboardComposer.isVisible = inChat
        b.dashboardChatList.isVisible = inChat
        if (!inChat) closeAttach()
        syncChatListInset()
    }

    private fun syncChatListInset() {
        val b = _binding ?: return
        val topDp = if (chatLayerOpen) 56f else 8f
        val top = (topDp * resources.displayMetrics.density).toInt()
        b.dashboardChatList.setPadding(
            b.dashboardChatList.paddingLeft,
            top,
            b.dashboardChatList.paddingRight,
            b.dashboardChatList.paddingBottom,
        )
    }

    private fun hideKeyboard() {
        val b = _binding ?: return
        b.dashboardChatInput.clearFocus()
        val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        val token = view?.windowToken ?: b.root.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
    }

    private fun hideHomeDashboardWidgets() {
        val b = _binding ?: return
        b.dashboardEmpty.isVisible = false
        b.dashboardAdsScroll.isVisible = false
        newsHandler.removeCallbacks(newsTick)
    }

    fun scrollHomeToTop() {
        val b = _binding ?: return
        b.dashboardScroll.post { b.dashboardScroll.scrollTo(0, 0) }
    }

    /** کلیک تب چت / «وینور»: اول پیام‌ها، فایل‌ها بعداً. */
    fun openVinorChat() {
        if (_binding == null) return
        wantChatLayer = true
        chatLayerOpen = true
        hideHomeDashboardWidgets()
        syncComposerVisible()
        (activity as? MainActivity)?.syncDivarTab()
        if (binding.dashboardChatList.childCount > 0) {
            binding.dashboardScroll.post { binding.dashboardScroll.fullScroll(View.FOCUS_DOWN) }
            finishRefreshUi()
            fetchHistory(bindIfWanted = true)
            return
        }
        val hist = cachedHistory
        if (hist != null) {
            bindChat(hist)
            fetchHistory(bindIfWanted = true)
            return
        }
        chatBindGen += 1
        binding.dashboardProgress.isVisible = true
        fetchHistory(bindIfWanted = true)
    }

    fun setHeaderVisible(visible: Boolean) {
        val b = _binding ?: return
        b.dashboardHeader.animate().cancel()
        b.dashboardHeader.animate().alpha(if (visible) 1f else 0f).setDuration(160).start()
        b.dashboardHeader.isClickable = visible
    }

    fun closeOverlays(): Boolean {
        if (closeAttach()) return true
        if (closeCustomer()) return true
        if (closeAdsAll()) return true
        if (chatLayerOpen) {
            showHomeEntry()
            return true
        }
        return false
    }

    fun closeAdsAll(): Boolean {
        val b = _binding ?: return false
        if (!b.dashboardAdsAll.isVisible) return false
        b.dashboardAdsAll.isVisible = false
        return true
    }

    private fun closeCustomer(): Boolean {
        val b = _binding ?: return false
        if (!b.dashboardCustomer.isVisible) return false
        b.dashboardCustomer.isVisible = false
        return true
    }

    private fun closeAttach(): Boolean {
        val b = _binding ?: return false
        if (!b.dashboardAttachSheet.isVisible && !b.dashboardAttachScrim.isVisible) return false
        b.dashboardAttachSheet.isVisible = false
        b.dashboardAttachScrim.isVisible = false
        b.dashboardChatPlus.animate().rotation(0f).setDuration(200).start()
        val out = android.util.TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            out,
            true,
        )
        b.dashboardChatPlus.setBackgroundResource(out.resourceId)
        return true
    }

    private fun toggleAttach() {
        if (binding.dashboardAttachSheet.isVisible) closeAttach() else openAttach()
    }

    private fun openAttach() {
        binding.dashboardAttachSheet.isVisible = true
        binding.dashboardAttachScrim.isVisible = true
        binding.dashboardChatPlus.animate().rotation(45f).setDuration(200).start()
        binding.dashboardChatPlus.setBackgroundResource(R.drawable.bg_plus_open)
    }

    private fun onAttachKind(kind: String) {
        closeAttach()
        if (!PartnerApi.hasSession()) {
            Toast.makeText(requireContext(), "برای ارسال فایل وارد شوید.", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.openDrawer()
            return
        }
        when (kind) {
            "gallery" -> pickGallery.launch("image/*")
            "camera" -> {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    launchCamera()
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }
            "video" -> pickVideo.launch("video/*")
            else -> pickFile.launch("*/*")
        }
    }

    private fun launchCamera() {
        val ctx = context ?: return
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("capture_", ".jpg", dir)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        cameraOutput = uri
        takePicture.launch(uri)
    }

    private fun startUpload(uri: Uri, kind: String) {
        if (_binding == null) return
        if (!PartnerApi.hasSession()) {
            Toast.makeText(requireContext(), "برای ارسال فایل وارد شوید.", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.openDrawer()
            return
        }
        val ctx = requireContext()
        val name = uriName(uri)
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        val size = uriSize(uri)
        pendingAttachment = null
        attachUploading = true
        showAttachPreview(kind, name, size, uploading = true)
        syncSendEnabled()
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postUpload(
                        "/express/partner/ai/upload",
                        uri,
                        kind,
                        name,
                        mime,
                        ctx,
                    )
                }
                if (_binding == null) return@launch
                attachUploading = false
                val http = resp.optInt("http_status")
                if (http == 401 || http == 403 || resp.optBoolean("need_login")) {
                    clearPendingAttachment()
                    Toast.makeText(ctx, resp.optString("error").ifBlank { "برای ارسال فایل وارد شوید." }, Toast.LENGTH_SHORT).show()
                    (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                if (resp.optBoolean("ok") && resp.optString("url").isNotBlank()) {
                    pendingAttachment = JSONObject()
                        .put("kind", resp.optString("kind").ifBlank { kind })
                        .put("url", resp.optString("url"))
                        .put("name", resp.optString("name").ifBlank { name })
                        .put("mime", resp.optString("mime").ifBlank { mime })
                        .put("size", resp.optLong("size").let { if (it > 0) it else size })
                    showAttachPreview(
                        pendingAttachment!!.optString("kind"),
                        pendingAttachment!!.optString("name"),
                        pendingAttachment!!.optLong("size"),
                        uploading = false,
                    )
                } else {
                    clearPendingAttachment()
                    Toast.makeText(ctx, resp.optString("error").ifBlank { "آپلود ناموفق بود." }, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                attachUploading = false
                clearPendingAttachment()
                Toast.makeText(requireContext(), e.message ?: "خطا در آپلود فایل.", Toast.LENGTH_SHORT).show()
            } finally {
                if (_binding != null) syncSendEnabled()
            }
        }
    }

    private fun showAttachPreview(kind: String, name: String, size: Long, uploading: Boolean) {
        binding.dashboardAttachPreview.isVisible = true
        binding.dashboardAttachName.text = name.ifBlank { "پیوست" }
        binding.dashboardAttachSub.text = when {
            uploading -> "در حال آپلود…"
            size > 0L -> formatSize(size)
            kind == "image" -> "تصویر"
            kind == "video" -> "ویدیو"
            else -> "فایل"
        }
        binding.dashboardAttachThumb.setImageResource(
            when (kind) {
                "image" -> R.drawable.ic_attach_gallery
                "video" -> R.drawable.ic_attach_video
                else -> R.drawable.ic_attach_file
            },
        )
        binding.dashboardAttachPreview.alpha = if (uploading) 0.75f else 1f
    }

    private fun clearPendingAttachment() {
        pendingAttachment = null
        attachUploading = false
        val b = _binding ?: return
        b.dashboardAttachPreview.isVisible = false
        b.dashboardAttachPreview.alpha = 1f
        syncSendEnabled()
    }

    private fun uriName(uri: Uri): String {
        val cr = requireContext().contentResolver
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0).orEmpty().ifBlank { "file" }
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun uriSize(uri: Uri): Long {
        val cr = requireContext().contentResolver
        cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0L
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        return if (kb < 1024) "${kb.toInt()} کیلوبایت" else "${"%.1f".format(kb / 1024.0)} مگابایت"
    }

    private fun onHero() {
        if (PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openPath("/express/partner/apply")
        } else {
            (activity as? MainActivity)?.openDrawer()
        }
    }

    private fun openCustomer() {
        if (!PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openDrawer()
            return
        }
        closeAttach()
        binding.dashboardCustomer.isVisible = true
    }

    private fun submitCustomer() {
        if (!PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openDrawer()
            return
        }
        val body = JSONObject()
            .put("customer_first_name", binding.dashboardCustomerFirst.text?.toString()?.trim().orEmpty())
            .put("customer_last_name", binding.dashboardCustomerLast.text?.toString()?.trim().orEmpty())
            .put("customer_phone", binding.dashboardCustomerPhone.text?.toString()?.trim().orEmpty())
            .put("notes", binding.dashboardCustomerNotes.text?.toString()?.trim().orEmpty())
        binding.dashboardCustomerSubmit.isEnabled = false
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/home/register-customer", body, requireContext())
                }
                if (_binding == null) return@launch
                val http = resp.optInt("http_status")
                if (http == 401 || http == 403) {
                    Toast.makeText(
                        requireContext(),
                        resp.optString("error").ifBlank { "برای ثبت درخواست وارد شوید." },
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (http == 401) (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                if (resp.optBoolean("ok")) {
                    closeCustomer()
                    binding.dashboardCustomerFirst.setText("")
                    binding.dashboardCustomerLast.setText("")
                    binding.dashboardCustomerPhone.setText("")
                    binding.dashboardCustomerNotes.setText("")
                    Toast.makeText(
                        requireContext(),
                        resp.optString("message").ifBlank { "درخواست بازدید ثبت شد." },
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        resp.optString("error").ifBlank { "ثبت ناموفق بود." },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
            } finally {
                if (_binding != null) binding.dashboardCustomerSubmit.isEnabled = true
            }
        }
    }

    private fun syncSendEnabled() {
        val on = !binding.dashboardChatInput.text.isNullOrBlank() || pendingAttachment != null
        binding.dashboardChatSend.isEnabled = true
        binding.dashboardChatSend.alpha = 1f
        binding.dashboardChatSend.setBackgroundResource(
            if (on) R.drawable.bg_send_circle else R.drawable.bg_send_idle
        )
        ImageViewCompat.setImageTintList(
            binding.dashboardChatSend,
            android.content.res.ColorStateList.valueOf(Color.parseColor(if (on) "#0D0D0D" else "#111111")),
        )
    }

    private fun loadAll(fromPull: Boolean = false) {
        val ctx = context ?: return
        if (PartnerApi.hasSession()) {
            fetchHistory(bindIfWanted = wantChatLayer)
        } else {
            cachedHistory = null
            threadId = null
            if (wantChatLayer) bindChat(JSONObject())
        }
        scope.launch {
            var shownCache = false
            if (!fromPull) {
                val cached = withContext(Dispatchers.IO) {
                    PartnerApi.peekJson("/express/partner/home/data", ctx)
                }
                if (_binding == null) return@launch
                if (cached != null) {
                    bindHome(cached)
                    if (!wantChatLayer && !chatLayerOpen) showHomeEmpty()
                    binding.dashboardProgress.isVisible = false
                    shownCache = true
                    if (withContext(Dispatchers.IO) {
                            PartnerApi.isFresh("/express/partner/home/data", ctx)
                        }
                    ) {
                        if (!wantChatLayer) finishRefreshUi()
                        return@launch
                    }
                } else if (!wantChatLayer) {
                    binding.dashboardProgress.isVisible = true
                }
            } else {
                shownCache = binding.dashboardAds.childCount > 0 || binding.dashboardEmpty.isVisible
                if (!wantChatLayer) binding.dashboardProgress.isVisible = false
            }
            var home = JSONObject()
            var loadError: String? = null
            try {
                home = withContext(Dispatchers.IO) {
                    PartnerApi.get("/express/partner/home/data", ctx)
                }
            } catch (e: Exception) {
                loadError = e.message ?: "خطا در بارگذاری"
                if (shownCache) {
                    home = PartnerApi.peekJson("/express/partner/home/data", ctx) ?: JSONObject()
                }
            }
            if ((home.optJSONArray("divar_pro_ads")?.length() ?: 0) == 0) {
                try {
                    val html = withContext(Dispatchers.IO) {
                        PartnerApi.getHtml("/express/partner/home", ctx)
                    }
                    val ads = HomeAdsHtml.parse(html)
                    if (ads.length() > 0) {
                        home.put("divar_pro_ads", ads)
                        if (home.optInt("divar_pro_ads_count") <= 0) {
                            home.put("divar_pro_ads_count", ads.length())
                        }
                        loadError = null
                    }
                    if ((home.optJSONArray("home_news_items")?.length() ?: 0) == 0) {
                        val newsHtml = HomeAdsHtml.parseNews(html)
                        if (newsHtml.length() > 0) home.put("home_news_items", newsHtml)
                    }
                    if (ads.length() > 0) {
                        home.remove("from_cache")
                        PartnerApi.remember("/express/partner/home/data", home, ctx)
                    }
                } catch (e: Exception) {
                    if (loadError == null && !shownCache) loadError = e.message
                }
            }
            if (_binding == null) {
                if (!wantChatLayer) finishRefreshUi()
                return@launch
            }
            if (!home.optBoolean("from_cache") || !shownCache) {
                bindHome(home)
            }
            if (!wantChatLayer && !chatLayerOpen) showHomeEmpty()
            if (loadError != null && (home.optJSONArray("divar_pro_ads")?.length() ?: 0) == 0 && !shownCache) {
                Toast.makeText(requireContext(), loadError, Toast.LENGTH_SHORT).show()
            }
            if (!wantChatLayer) finishRefreshUi()
        }
    }

    private fun showHomeEmpty() {
        val b = _binding ?: return
        chatLayerOpen = false
        b.dashboardEmpty.isVisible = true
        b.dashboardAdsScroll.isVisible = b.dashboardAds.childCount > 0
        newsHandler.removeCallbacks(newsTick)
        if (newsItems.size > 1) newsHandler.postDelayed(newsTick, 4000)
        hideKeyboard()
        syncComposerVisible()
        (activity as? MainActivity)?.syncDivarTab()
    }

    private fun fetchHistory(bindIfWanted: Boolean) {
        val ctx = context ?: return
        scope.launch {
            try {
                val peeked = withContext(Dispatchers.IO) {
                    PartnerApi.peekJson("/express/partner/ai/history", ctx)
                }
                if (_binding == null) return@launch
                if (peeked != null) {
                    rememberHistory(peeked)
                    if (bindIfWanted && wantChatLayer) bindChatIfNeeded(peeked)
                }
                val hist = withContext(Dispatchers.IO) {
                    PartnerApi.get("/express/partner/ai/history", ctx)
                }
                if (_binding == null) return@launch
                val status = hist.optInt("http_status")
                if (status == 401 || status == 403) {
                    cachedHistory = null
                    threadId = null
                    if (bindIfWanted && wantChatLayer) bindChat(JSONObject())
                    return@launch
                }
                rememberHistory(hist)
                if (bindIfWanted && wantChatLayer) bindChatIfNeeded(hist)
            } catch (_: Exception) {
                if (_binding != null && bindIfWanted && wantChatLayer &&
                    binding.dashboardChatList.childCount == 0
                ) {
                    bindChat(JSONObject())
                }
            } finally {
                if (wantChatLayer || _binding?.dashboardProgress?.isVisible == true) {
                    finishRefreshUi()
                }
            }
        }
    }

    private fun bindChatIfNeeded(obj: JSONObject) {
        val incoming = obj.optJSONArray("messages")?.length() ?: 0
        if (boundMessageCount > 0 &&
            incoming == boundMessageCount &&
            (_binding?.dashboardChatList?.childCount ?: 0) > 0
        ) {
            return
        }
        bindChat(obj)
    }

    private fun rememberHistory(obj: JSONObject) {
        cachedHistory = obj
        var tid = obj.optString("thread_id").takeIf { it.isNotBlank() }
        if (tid.isNullOrBlank()) {
            val threads = obj.optJSONArray("threads") ?: JSONArray()
            for (i in 0 until threads.length()) {
                val th = threads.optJSONObject(i) ?: continue
                val kind = th.optString("kind")
                if (kind == "postchi" || kind == "lawyer") continue
                val id = th.optString("id").takeIf { it.isNotBlank() } ?: continue
                if (th.optBoolean("is_active") || tid.isNullOrBlank()) tid = id
                if (th.optBoolean("is_active")) break
            }
        }
        if (!tid.isNullOrBlank()) threadId = tid
    }

    private fun bindHome(obj: JSONObject) {
        isApproved = obj.optBoolean("is_approved")
        newsItems.clear()
        val news = obj.optJSONArray("home_news_items") ?: JSONArray()
        for (i in 0 until news.length()) {
            val line = news.optString(i)
            if (line.isNotBlank()) newsItems.add(line)
        }
        if (newsItems.isEmpty()) {
            newsItems.add("زمین جابان فروش رفت و پورسانت رسید")
        }
        binding.dashboardNewsText.alpha = 1f
        binding.dashboardNewsText.text = newsItems.first()
        newsHandler.removeCallbacks(newsTick)
        val inChat = chatLayerOpen && wantChatLayer
        if (!inChat && newsItems.size > 1) newsHandler.postDelayed(newsTick, 4000)

        val ads = obj.optJSONArray("divar_pro_ads") ?: JSONArray()
        allAds = ads
        val adsKey = adsFingerprint(ads)
        val sameCards = adsKey.isNotEmpty() &&
            adsKey == lastAdsKey &&
            binding.dashboardAds.childCount == ads.length()
        val showCarousel = ads.length() > 0
        if (!sameCards) {
            lastAdsKey = adsKey
            binding.dashboardAds.removeAllViews()
            for (i in 0 until ads.length()) {
                val ad = ads.optJSONObject(i) ?: continue
                binding.dashboardAds.addView(inflateAdCard(ad, binding.dashboardAds).root)
            }
        }
        if (inChat) {
            binding.dashboardAdsScroll.isVisible = false
            binding.dashboardEmpty.isVisible = false
        } else {
            binding.dashboardAdsScroll.isVisible = showCarousel
            if (showCarousel) {
                binding.dashboardAds.post {
                    val b = _binding ?: return@post
                    b.dashboardAdsScroll.fullScroll(View.FOCUS_RIGHT)
                    bindVisibleCarouselImages()
                }
            }
        }

        val emptyPad = if (showCarousel) 6 else 56
        binding.dashboardEmpty.setPadding(
            binding.dashboardEmpty.paddingLeft,
            (emptyPad * resources.displayMetrics.density).toInt(),
            binding.dashboardEmpty.paddingRight,
            binding.dashboardEmpty.paddingBottom,
        )

        val adsCount = obj.optInt("divar_pro_ads_count").let { if (it > 0) it else ads.length() }
        val reqCount = obj.optInt("home_requests_pipeline_count")
        bindPrompts(obj, adsCount, reqCount)
        val imageUrls = mutableListOf<String>()
        for (i in 0 until ads.length()) {
            val url = ads.optJSONObject(i)?.optString("image_url").orEmpty()
            if (url.isNotBlank()) imageUrls.add(url)
        }
        val ctx = context
        if (ctx != null && imageUrls.isNotEmpty()) {
            PartnerImages.prefetch(scope, ctx, imageUrls)
        }
    }

    private fun bindPrompts(obj: JSONObject, adsCount: Int, reqCount: Int) {
        var prompts = obj.optJSONArray("ai_quick_prompts") ?: JSONArray()
        if (prompts.length() == 0) prompts = defaultPrompts(obj)
        var hero: JSONObject? = null
        val tiles = mutableListOf<JSONObject>()
        for (i in 0 until prompts.length()) {
            val p = prompts.optJSONObject(i) ?: continue
            if (p.optString("variant") == "hero") {
                if (hero == null) hero = p
            } else {
                tiles.add(p)
            }
        }
        bindHeroPrompt(hero)
        bindTilePrompt(
            tile = binding.dashboardTileBuy,
            icon = binding.dashboardTileBuyIcon,
            title = binding.dashboardTileBuyTitle,
            sub = binding.dashboardTileBuySub,
            count = binding.dashboardTileBuyCount,
            prompt = tiles.getOrNull(0),
            fallbackTitle = "خرید ملک",
            fallbackSub = "جستجوی فایل و ملک",
            fallbackIcon = R.drawable.ic_search_stroke,
            fallbackAction = { openAdsAll() },
            adsCount = adsCount,
            reqCount = reqCount,
        )
        bindTilePrompt(
            tile = binding.dashboardTileLevels,
            icon = binding.dashboardTileLevelsIcon,
            title = binding.dashboardTileLevelsTitle,
            sub = binding.dashboardTileLevelsSub,
            count = binding.dashboardTileLevelsCount,
            prompt = tiles.getOrNull(1),
            fallbackTitle = "سطوح همکاری",
            fallbackSub = "شرایط و پورسانت",
            fallbackIcon = R.drawable.ic_users_stroke,
            fallbackAction = {
                (activity as? MainActivity)?.openPath("/express/partner/levels")
            },
            adsCount = adsCount,
            reqCount = reqCount,
        )
    }

    private fun defaultPrompts(obj: JSONObject): JSONArray {
        val out = JSONArray()
        val approved = obj.optBoolean("is_approved")
        val pending = obj.optBoolean("has_pending_app")
        val loggedIn = PartnerApi.hasSession() ||
            obj.optBoolean("is_logged_in") ||
            obj.optString("me_phone").isNotBlank()
        if (approved) {
            out.put(
                JSONObject()
                    .put("label", "مشتری دارم")
                    .put("subtitle", "برای مشتری وقت بازدید بگیرید")
                    .put("variant", "hero")
                    .put("icon", "user_plus")
                    .put("ui_action", "open_customer_modal"),
            )
            out.put(
                JSONObject()
                    .put("label", "لیست ملک‌ها")
                    .put("subtitle", "جستجوی فایل و ملک")
                    .put("variant", "tile")
                    .put("icon", "search")
                    .put("ui_action", "open_divar_ads_all"),
            )
            out.put(
                JSONObject()
                    .put("label", "پیگیری")
                    .put("subtitle", "درخواست، بازدید و معامله")
                    .put("variant", "tile")
                    .put("icon", "clipboard")
                    .put("prompt", "قدم بعدی فروش من چیست؟ از همین چت جلو ببر.")
                    .put("intent", "next_action"),
            )
            return out
        }
        if (pending) {
            out.put(
                JSONObject()
                    .put("label", "وضعیت درخواست")
                    .put("subtitle", "پیگیری تأیید همکاری")
                    .put("variant", "hero")
                    .put("icon", "clipboard")
                    .put("href", "/express/partner/thanks"),
            )
        } else {
            out.put(
                JSONObject()
                    .put("label", "می‌خواهم بازاریاب وینور شوم")
                    .put("subtitle", "شروع همکاری فروش و دریافت پورسانت")
                    .put("variant", "hero")
                    .put("icon", "handshake")
                    .put(
                        "href",
                        if (loggedIn) "/express/partner/apply"
                        else "/express/partner/home?menu=1&login_next=/express/partner/apply",
                    ),
            )
        }
        out.put(
            JSONObject()
                .put("label", "خرید ملک")
                .put("subtitle", "جستجوی فایل و ملک")
                .put("variant", "tile")
                .put("icon", "search")
                .put("ui_action", "open_divar_ads_all"),
        )
        out.put(
            JSONObject()
                .put("label", "سطوح همکاری")
                .put("subtitle", "شرایط و پورسانت")
                .put("variant", "tile")
                .put("icon", "users")
                .put("href", "/express/partner/levels"),
        )
        return out
    }

    private fun bindHeroPrompt(prompt: JSONObject?) {
        val label = prompt?.optString("label").orEmpty().ifBlank { "می‌خواهم بازاریاب وینور شوم" }
        val sub = prompt?.optString("subtitle").orEmpty().ifBlank { "شروع همکاری فروش و دریافت پورسانت" }
        binding.dashboardHeroTitle.text = label
        binding.dashboardHeroSub.text = sub
        binding.dashboardHeroIcon.setImageResource(heroIcon(prompt?.optString("icon")))
        val href = prompt?.optString("href").orEmpty()
        val action = prompt?.optString("ui_action").orEmpty()
        binding.dashboardHero.setOnClickListener {
            when {
                action == "open_customer_modal" -> openCustomer()
                action == "open_divar_ads_all" -> openAdsAll()
                !PartnerApi.hasSession() || href.contains("login") || href.contains("menu=1") ->
                    (activity as? MainActivity)?.openDrawer()
                href.isNotBlank() -> openVinorOrExternal(href)
                else -> runPromptOr(prompt) { onHero() }
            }
        }
    }

    private fun bindTilePrompt(
        tile: View,
        icon: ImageView,
        title: android.widget.TextView,
        sub: android.widget.TextView,
        count: android.widget.TextView,
        prompt: JSONObject?,
        fallbackTitle: String,
        fallbackSub: String,
        fallbackIcon: Int,
        fallbackAction: () -> Unit,
        adsCount: Int,
        reqCount: Int,
    ) {
        title.text = prompt?.optString("label").orEmpty().ifBlank { fallbackTitle }
        sub.text = prompt?.optString("subtitle").orEmpty().ifBlank { fallbackSub }
        icon.setImageResource(tileIcon(prompt?.optString("icon"), fallbackIcon))
        val href = prompt?.optString("href").orEmpty()
        val action = prompt?.optString("ui_action").orEmpty()
        val showAds = action == "open_divar_ads_all"
        val intent = prompt?.optString("intent").orEmpty()
        val showReq = intent == "followup" || intent == "next_action" || href.contains("/requests")
        val n = when {
            showAds -> adsCount
            showReq -> reqCount
            else -> 0
        }
        count.isVisible = n > 0
        if (n > 0) count.text = PartnerFormat.toman(n)
        tile.setOnClickListener {
            when {
                action == "open_divar_ads_all" -> openAdsAll()
                action == "open_customer_modal" -> openCustomer()
                href.isNotBlank() -> openVinorOrExternal(href)
                else -> runPromptOr(prompt, fallbackAction)
            }
        }
    }

    private fun heroIcon(name: String?): Int = when (name) {
        "clipboard" -> R.drawable.ic_clipboard
        "user_plus" -> R.drawable.ic_user_plus_stroke
        "search" -> R.drawable.ic_search_stroke
        "users" -> R.drawable.ic_users_stroke
        else -> R.drawable.ic_handshake
    }

    private fun tileIcon(name: String?, fallback: Int): Int = when (name) {
        "search" -> R.drawable.ic_search_stroke
        "users" -> R.drawable.ic_users_stroke
        "clipboard" -> R.drawable.ic_clipboard
        "user_plus" -> R.drawable.ic_user_plus_stroke
        else -> fallback
    }

    private fun inflateAdCard(ad: JSONObject, parent: ViewGroup): ItemHomeAdBinding {
        val card = ItemHomeAdBinding.inflate(layoutInflater, parent, false)
        card.homeAdTitle.text = ad.optString("title")
        card.homeAdPrice.text = ad.optString("price")
        val loc = ad.optString("location")
        card.homeAdLoc.text = when {
            loc.isBlank() -> ""
            loc.startsWith("در ") -> loc
            else -> "در $loc"
        }
        val shots = ad.optInt("image_count")
        card.homeAdShots.isVisible = shots > 1
        if (shots > 1) card.homeAdShotsText.text = PartnerFormat.toman(shots)
        val comm = ad.optInt("commission_amount")
        card.homeAdComm.isVisible = isApproved && comm > 0
        if (isApproved && comm > 0) {
            card.homeAdComm.text = "پورسانت ${PartnerFormat.toman(comm)} تومان"
        }
        PartnerImages.roundClip(card.root, 15f)
        card.homeAdImage.tag = ad.optString("image_url")
        card.root.setOnClickListener { openAd(ad) }
        return card
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

    private fun openAd(ad: JSONObject) {
        val token = ad.optString("token")
        val source = ad.optString("source")
        val url = ad.optString("url")
        when {
            source == "vinor" && token.isNotBlank() -> (activity as? MainActivity)?.openLand(token)
            source == "divar" && token.isNotBlank() ->
                (activity as? MainActivity)?.openLand("divar:$token")
            url.isNotBlank() -> openVinorOrExternal(url)
        }
    }

    private fun openAdsAll() {
        binding.dashboardAdsAllList.removeAllViews()
        for (i in 0 until allAds.length()) {
            val ad = allAds.optJSONObject(i) ?: continue
            val row = ItemAdsAllRowBinding.inflate(layoutInflater, binding.dashboardAdsAllList, false)
            row.adsAllTitle.text = ad.optString("title")
            row.adsAllPrice.text = ad.optString("price")
            val loc = ad.optString("location")
            row.adsAllLoc.text = when {
                loc.isBlank() -> ""
                loc.startsWith("در ") -> loc
                else -> "در $loc"
            }
            PartnerImages.roundClip(row.adsAllThumb, 8f)
            row.adsAllThumb.tag = ad.optString("image_url")
            row.root.setOnClickListener {
                closeAdsAll()
                openAd(ad)
            }
            binding.dashboardAdsAllList.addView(row.root)
        }
        binding.dashboardAdsAll.isVisible = true
        binding.dashboardAdsAllList.post { bindVisibleAdsAllImages() }
    }

    private fun bindVisibleCarouselImages() {
        val b = _binding ?: return
        bindVisibleRowImages(b.dashboardAdsScroll, b.dashboardAds) { it.findViewById(R.id.homeAdImage) }
    }

    private fun bindVisibleAdsAllImages() {
        val b = _binding ?: return
        if (!b.dashboardAdsAll.isVisible) return
        val host = b.dashboardAdsAllList.parent as? View ?: return
        bindVisibleRowImages(host, b.dashboardAdsAllList) { it.findViewById(R.id.adsAllThumb) }
    }

    private fun bindVisibleRowImages(
        host: View,
        row: ViewGroup,
        imageOf: (View) -> ImageView?,
    ) {
        val extra = (80 * resources.displayMetrics.density).toInt()
        val vis = Rect()
        if (!host.getGlobalVisibleRect(vis)) return
        vis.inset(-extra, -extra)
        val childBox = Rect()
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            val img = imageOf(child) ?: continue
            if (img.getTag(R.id.homeAdImage) == true) continue
            if (!child.getGlobalVisibleRect(childBox)) continue
            if (!Rect.intersects(vis, childBox)) continue
            img.setTag(R.id.homeAdImage, true)
            PartnerImages.bind(scope, img, img.tag as? String)
        }
    }

    private fun bindChat(obj: JSONObject) {
        if (!wantChatLayer) return
        threadId = obj.optString("thread_id").takeIf { it.isNotBlank() }
        val messages = obj.optJSONArray("messages") ?: JSONArray()
        boundMessageCount = messages.length()
        val gen = chatBindGen
        val codes = landCodesIn(messages)
        for (code in codes) {
            if (landsByCode.containsKey(code)) continue
            VinorLandCardCache.get(code)?.let { landsByCode[code] = it }
        }
        renderMessages(messages)
        finishRefreshUi()
        scope.launch {
            val missing = codes.filter { !landsByCode.containsKey(it) }
            if (missing.isEmpty()) return@launch
            coroutineScope {
                missing.map { code -> async { prefetchLand(code) } }.awaitAll()
            }
            if (_binding == null) return@launch
            if (gen != chatBindGen || !wantChatLayer) return@launch
            renderMessages(messages)
        }
    }

    private fun landCodesIn(messages: JSONArray): List<String> {
        val codes = linkedSetOf<String>()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val arr = msg.optJSONArray("land_codes") ?: continue
            for (j in 0 until arr.length()) {
                val code = arr.optString(j)
                if (code.isNotBlank()) codes.add(code)
            }
        }
        return codes.toList()
    }

    private suspend fun prefetchLand(code: String) {
        if (code.isBlank()) return
        if (landsByCode.containsKey(code)) return
        VinorLandCardCache.get(code)?.let {
            landsByCode[code] = it
            return
        }
        val path = "/api/express/$code"
        val ctx = context ?: return
        try {
            val cached = withContext(Dispatchers.IO) { PartnerApi.peekJson(path, ctx) }
            val fromCache = cached?.optJSONObject("land")
            if (fromCache != null) {
                if (!fromCache.has("code")) fromCache.put("code", code)
                landsByCode[code] = fromCache
                VinorLandCardCache.put(code, fromCache)
                if (withContext(Dispatchers.IO) { PartnerApi.isFresh(path, ctx) }) return
            }
            val obj = withContext(Dispatchers.IO) { PartnerApi.get(path, ctx) }
            val land = obj.optJSONObject("land") ?: return
            if (!land.has("code")) land.put("code", code)
            landsByCode[code] = land
            VinorLandCardCache.put(code, land)
        } catch (_: Exception) {
        }
    }

    private fun adsFingerprint(ads: JSONArray): String {
        val b = StringBuilder(ads.length() * 24)
        for (i in 0 until ads.length()) {
            val ad = ads.optJSONObject(i) ?: continue
            b.append(ad.optString("token")).append('\u0001')
                .append(ad.optString("image_url")).append('\u0001')
                .append(ad.optString("price")).append('\u0002')
        }
        return b.toString()
    }

    private fun renderMessages(messages: JSONArray) {
        if (!wantChatLayer || !chatLayerOpen) {
            showHomeEmpty()
            return
        }
        binding.dashboardChatList.removeAllViews()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val att = msg.optJSONObject("attachment")
                ?: msg.optJSONObject("meta")?.optJSONObject("attachment")
            addBubble(msg.optString("role"), msg.optString("content"), msg.optJSONArray("land_codes"), att)
        }
        if (chatLayerOpen && wantChatLayer) {
            hideHomeDashboardWidgets()
            syncComposerVisible()
            binding.dashboardScroll.post { binding.dashboardScroll.fullScroll(View.FOCUS_DOWN) }
            return
        }
        showHomeEmpty()
    }

    private fun addBubble(role: String, content: String, codes: JSONArray?, attachment: JSONObject? = null) {
        if (content.isBlank() && (codes == null || codes.length() == 0) && attachment == null) return
        val bubble = ItemChatBubbleBinding.inflate(layoutInflater, binding.dashboardChatList, false)
        val isUser = role == "user"
        bubble.chatBubbleAvatar.isVisible = !isUser
        bubble.chatBubbleRow.gravity = if (isUser) Gravity.END else Gravity.START
        val hasAtt = attachment != null && attachment.optString("url").isNotBlank()
        bubble.chatBubbleAttach.isVisible = hasAtt
        if (hasAtt) {
            val kind = attachment!!.optString("kind")
            bubble.chatBubbleAttachName.text = attachment.optString("name").ifBlank { "پیوست" }
            bubble.chatBubbleAttachSub.text = when (kind) {
                "image" -> "تصویر"
                "video" -> "ویدیو"
                else -> "فایل"
            }
            bubble.chatBubbleAttachThumb.setImageResource(
                when (kind) {
                    "image" -> R.drawable.ic_attach_gallery
                    "video" -> R.drawable.ic_attach_video
                    else -> R.drawable.ic_attach_file
                },
            )
            val url = PartnerImages.resolve(attachment.optString("url"))
            bubble.chatBubbleAttach.setOnClickListener {
                openVinorOrExternal(url)
            }
        }
        if (content.isNotBlank()) {
            bubble.chatBubbleText.isVisible = true
            bubble.chatBubbleText.text = content
            if (isUser) {
                bubble.chatBubbleText.setBackgroundResource(R.drawable.bg_chat_user)
                val pad = (12 * resources.displayMetrics.density).toInt()
                bubble.chatBubbleText.setPadding(pad, pad, pad, pad)
            } else {
                bubble.chatBubbleText.background = null
                bubble.chatBubbleText.setPadding(0, 0, 0, 0)
            }
        } else {
            bubble.chatBubbleText.isVisible = false
        }
        val host = bubble.chatLandHost
        host.removeAllViews()
        var attached = 0
        if (codes != null) {
            for (i in 0 until codes.length()) {
                val code = codes.optString(i)
                val land = landsByCode[code] ?: JSONObject().put("code", code).put("title", code)
                host.addView(inflateLandCard(land, host).root)
                attached++
            }
        }
        host.isVisible = attached > 0
        binding.dashboardChatList.addView(bubble.root)
    }

    private fun inflateLandCard(land: JSONObject, parent: ViewGroup): ItemLandCardBinding {
        val card = ItemLandCardBinding.inflate(layoutInflater, parent, false)
        val code = land.optString("code")
        card.landCardTitle.text = land.optString("title", "—")
        card.landCardPrice.text = PartnerFormat.toman(land.optInt("price_total")) + " تومان"
        val city = land.optString("city").ifBlank { land.optString("location") }
        card.landCardMeta.text = if (city.isNotBlank()) "در $city" else ""
        PartnerImages.roundClip(card.landCardImage)
        PartnerImages.bind(scope, card.landCardImage, PartnerImages.coverUrl(land), card.landCardImagePlaceholder)
        card.root.setOnClickListener { (activity as? MainActivity)?.openLand(code) }
        return card
    }

    private fun runPromptOr(prompt: JSONObject?, fallback: () -> Unit) {
        val text = prompt?.optString("prompt").orEmpty().ifBlank {
            prompt?.optString("label").orEmpty()
        }
        val intent = prompt?.optString("intent").orEmpty()
        if (text.isNotBlank() && intent.isNotBlank()) {
            sendChat(prefill = text, chipIntent = intent)
        } else {
            fallback()
        }
    }

    private fun sendChat(prefill: String? = null, chipIntent: String = "") {
        val text = prefill?.trim().orEmpty().ifBlank {
            binding.dashboardChatInput.text?.toString()?.trim().orEmpty()
        }
        val attach = pendingAttachment
        if (attachUploading) {
            Toast.makeText(requireContext(), "صبر کنید تا آپلود تمام شود.", Toast.LENGTH_SHORT).show()
            return
        }
        if (text.isEmpty() && attach == null) return
        closeAttach()
        binding.dashboardChatInput.setText("")
        val toSend = attach
        pendingAttachment = null
        binding.dashboardAttachPreview.isVisible = false
        syncSendEnabled()
        wantChatLayer = true
        chatLayerOpen = true
        chatBindGen += 1
        hideHomeDashboardWidgets()
        syncComposerVisible()
        (activity as? MainActivity)?.syncDivarTab()
        addBubble("user", text, null, toSend)
        scope.launch {
            try {
                val body = JSONObject().put("message", text)
                threadId?.let { body.put("thread_id", it) }
                if (chipIntent.isNotBlank()) body.put("intent", chipIntent)
                if (toSend != null) body.put("attachment", toSend)
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/ai/chat", body, requireContext())
                }
                if (_binding == null) return@launch
                if (!wantChatLayer || !chatLayerOpen) return@launch
                val http = resp.optInt("http_status")
                if (http == 401 || http == 403) {
                    if (toSend != null) {
                        pendingAttachment = toSend
                        showAttachPreview(
                            toSend.optString("kind"),
                            toSend.optString("name"),
                            toSend.optLong("size"),
                            uploading = false,
                        )
                    }
                    (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                if (resp.optBoolean("ok") || resp.optString("reply").isNotBlank()) {
                    threadId = resp.optString("thread_id").takeIf { it.isNotBlank() } ?: threadId
                    val codes = resp.optJSONArray("land_codes")
                    if (codes != null) {
                        for (i in 0 until codes.length()) prefetchLand(codes.optString(i))
                    }
                    addBubble("assistant", resp.optString("reply"), codes)
                    binding.dashboardScroll.post { binding.dashboardScroll.fullScroll(View.FOCUS_DOWN) }
                } else {
                    val err = resp.optString("error").ifBlank { "ارسال ناموفق بود." }
                    if (err.contains("ورود") || err.contains("لاگین")) {
                        (activity as? MainActivity)?.openDrawer()
                    } else {
                        Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
            } finally {
                if (_binding != null) syncSendEnabled()
            }
        }
    }
}
