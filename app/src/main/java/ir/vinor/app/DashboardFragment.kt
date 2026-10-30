package ir.vinor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import ir.vinor.app.databinding.FragmentDashboardBinding
import ir.vinor.app.databinding.ItemChatBubbleBinding
import ir.vinor.app.databinding.ItemHomeSuggestBinding
import ir.vinor.app.databinding.ItemLandCardBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** خانه نیتیو — میانبرها، کارت مشتری و فایل‌های پیشنهادی در یک صفحه. چت فقط از تب فوتر. */
class DashboardFragment : Fragment() {

    companion object {
        const val HOME_DATA = "/express/partner/home/data"
        private const val HOME_HTML = "/express/partner/home"
        private var rememberedHome: JSONObject? = null
        private var sessionStamp = 0

        fun clearRememberedHome() {
            rememberedHome = null
            sessionStamp++
        }

        internal fun peekRememberedHome(): JSONObject? = rememberedHome

        internal fun rememberHome(obj: JSONObject) {
            val copy = JSONObject(obj.toString())
            ListingRepository.ingestTree(copy)
            rememberedHome = copy
        }
    }

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)
    private var threadId: String? = null
    private val landsByCode = linkedMapOf<String, JSONObject>()
    private var isApproved = false
    private var pendingAttachment: JSONObject? = null
    private var attachUploading = false
    private var cameraOutput: Uri? = null
    private var cachedHistory: JSONObject? = null
    private var chatAccount: String? = null
    private var chatLayerOpen = false
    private var wantChatLayer = false
    private var chatBindGen = 0
    private var customerCsrf: String = ""
    private var customerBusy = false
    private var boundMessageCount = 0
    private var boundFingerprint = ""
    private var lastSyncAt = ""
    private var chatPollJob: Job? = null
    private var historyInFlight = false
    private var historyQueued = false
    private var syncInFlight = false
    private var chatSending = false
    private var chatWriteGen = 0
    private var emptyPollStreak = 0
    private var pollPaused = false
    private var suggestKey = ""
    private var loadGen = 0
    private var homeRendered = false
    private var skipNextResume = true
    private var homeAvatarWanted: String? = null
    private var homeAvatarLoading: String? = null
    private var homeAvatarLookup = false
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
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        skipNextResume = true
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        installHomeSearch()
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
        binding.dashboardHero.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (_binding == null) return@addOnLayoutChangeListener
            placeHeroLabel()
        }
        binding.dashboardBell.setOnClickListener { (activity as? MainActivity)?.openNotifications() }
        binding.dashboardHomeAvatar.setOnClickListener { (activity as? MainActivity)?.openProfile() }
        PartnerImages.roundClip(binding.dashboardHomeAvatar, 20f)
        binding.dashboardWallet.setOnClickListener {
            (activity as? MainActivity)?.openPath("/express/partner/commissions")
        }
        binding.dashboardShortcutTrain.setOnClickListener {
            (activity as? MainActivity)?.openPath("/express/partner/training")
        }
        binding.dashboardTileLevels.setOnClickListener {
            (activity as? MainActivity)?.openPath("/express/partner/levels")
        }
        binding.dashboardCustomerBackdrop.setOnClickListener { closeCustomer() }
        binding.dashboardCustomerSheetHost.setOnClickListener { closeCustomer() }
        binding.dashboardCustomerClose.setOnClickListener { closeCustomer() }
        binding.dashboardCustomerSubmit.setOnClickListener { submitCustomer() }
        binding.dashboardCustomerNotes.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitCustomer()
                true
            } else {
                false
            }
        }
        val clearCustomerError = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { showCustomerError(null) }
        }
        binding.dashboardCustomerFirst.addTextChangedListener(clearCustomerError)
        binding.dashboardCustomerLast.addTextChangedListener(clearCustomerError)
        binding.dashboardCustomerPhone.addTextChangedListener(clearCustomerError)
        binding.dashboardCustomerNotes.addTextChangedListener(clearCustomerError)
        val headerPad = (56 * resources.displayMetrics.density).toInt()
        binding.dashboardRefresh.setColorSchemeColors(Color.parseColor("#10B981"))
        binding.dashboardRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1C1C1E"))
        binding.dashboardRefresh.setProgressViewOffset(true, headerPad / 2, headerPad + (36 * resources.displayMetrics.density).toInt())
        binding.dashboardRefresh.setOnRefreshListener { onPullRefresh() }
        // Swipe-to-refresh is allowed only at the top of the single home ScrollView.
        binding.dashboardRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.dashboardScroll.scrollY > 0 || binding.dashboardScroll.canScrollVertically(-1)
        }
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
        syncSendEnabled()
        syncComposerVisible()
        val snap = rememberedHome
        if (snap != null) {
            paintRememberedHome()
            homeRendered = true
        } else {
            showHomeLoading()
        }
        loadAll(forceSkeleton = !homeRendered)
    }

    /** ابزار کامل جستجو داخل اسکرول خانه است، نه یک مقصد مستقل در ناوبری. */
    fun searchHeaderHost(): LinearLayout? = _binding?.dashboardSearchHeaderHost

    private fun installHomeSearch() {
        var search = childFragmentManager.findFragmentById(R.id.dashboardSearchContainer) as? SearchFragment
        if (search == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.dashboardSearchContainer, SearchFragment())
                .commitNow()
            search = childFragmentManager.findFragmentById(R.id.dashboardSearchContainer) as? SearchFragment
        }
        // The filter bar is reparented, but its callbacks and state remain in SearchFragment.
        search?.attachToHomeHeader(binding.dashboardSearchHeaderHost)
    }

    /** مقصد لینک‌های جستجوی قدیمی، همین بخش از خانه است. */
    fun revealHomeSearch() {
        showHomeEntry()
        val b = _binding ?: return
        b.dashboardScroll.post {
            if (_binding == null) return@post
            b.dashboardScroll.smoothScrollTo(0, b.dashboardSearchContainer.top)
            (childFragmentManager.findFragmentById(R.id.dashboardSearchContainer) as? SearchFragment)
                ?.focusInput()
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        val snap = rememberedHome ?: return
        if (_binding == null) return
        paintRememberedHome()
        bindHomeAvatar(snap)
        homeRendered = true
    }

    override fun onResume() {
        super.onResume()
        rememberedHome?.let { bindHomeAvatar(it) }
        if ((activity as? MainActivity)?.consumePendingVinorChat() == true) {
            openVinorChat()
        } else if ((activity as? MainActivity)?.consumePendingCustomerModal() == true) {
            showHomeEntry()
            openCustomerModal()
        } else if (isChatOpen()) {
            resumeChatLive()
        }
        if (skipNextResume) {
            skipNextResume = false
            return
        }
        val sheetOpen = _binding?.dashboardCustomer?.isVisible == true
        if (_binding != null && !isChatOpen() && !sheetOpen) {
            loadAll(forceSkeleton = !homeRendered)
        }
    }

    override fun onDestroyView() {
        stopChatPoll()
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    fun reload() {
        if (_binding == null) return
        showHomeEntry()
        loadAll(forceSkeleton = !homeRendered)
    }

    private fun onPullRefresh() {
        val b = _binding ?: return
        if (b.dashboardCustomer.isVisible || b.dashboardAttachSheet.isVisible) {
            b.dashboardRefresh.isRefreshing = false
            return
        }
        if (chatLayerOpen || wantChatLayer) {
            fetchHistory(bindIfWanted = true, force = true)
            return
        }
        refreshHomeAds()
    }

    /** The explicit button and swipe use the same property-feed refresh path. */
    private fun refreshHomeAds() {
        val b = _binding ?: return
        if (b.dashboardCustomer.isVisible || b.dashboardAttachSheet.isVisible) {
            b.dashboardRefresh.isRefreshing = false
            return
        }
        b.dashboardRefresh.isRefreshing = true
        loadAll(fromPull = true)
        val search = childFragmentManager.findFragmentById(R.id.dashboardSearchContainer) as? SearchFragment
        if (search == null) {
            finishRefreshUi()
            return
        }
        search.refreshAds {
            if (_binding === b) b.dashboardRefresh.isRefreshing = false
        }
    }

    private fun finishRefreshUi() {
        val b = _binding ?: return
        b.dashboardProgress.isVisible = false
        b.dashboardRefresh.isRefreshing = false
    }

    fun isChatOpen(): Boolean = chatLayerOpen && wantChatLayer

    /** چتِ باز فقط وقتی مال همین نشست است که دوباره از شبکه پر نشود. */
    fun isCurrentAccountChatOpen(): Boolean {
        val account = currentAccountKey()
        return isChatOpen() && account != null && chatAccount == account
    }

    /** تب خانه: لایهٔ چت را ببند و خانه را نشان بده. */
    fun showHomeEntry() {
        wantChatLayer = false
        chatBindGen += 1
        stopChatPoll()
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
        b.dashboardHomeHeader.isVisible = false
        b.dashboardSearchHeaderHost.isVisible = false
        b.dashboardEmpty.isVisible = false
        b.dashboardHeader.isVisible = true
    }

    fun scrollHomeToTop() {
        val b = _binding ?: return
        b.dashboardScroll.post { b.dashboardScroll.scrollTo(0, 0) }
    }

    /** کلیک تب چت / «وینور»: فقط نشست همین حساب. */
    fun openVinorChat() {
        if (_binding == null) return
        val account = currentAccountKey()
        if (account == null) {
            releaseChatAccount()
            (activity as? MainActivity)?.requireChatLogin()
            return
        }
        if (ChatSessionGate.shouldDropMemory(chatAccount, account)) discardAccountChat()
        chatAccount = account
        wantChatLayer = true
        chatLayerOpen = true
        hideHomeDashboardWidgets()
        syncComposerVisible()
        (activity as? MainActivity)?.syncDivarTab()
        if (binding.dashboardChatList.childCount > 0) {
            binding.dashboardScroll.post { binding.dashboardScroll.fullScroll(View.FOCUS_DOWN) }
            finishRefreshUi()
            armChatLive()
            return
        }
        val hist = cachedHistory
        if (hist != null) {
            bindChat(hist)
            armChatLive()
            return
        }
        binding.dashboardProgress.isVisible = true
        armChatLive()
    }

    /** لایه باز است: کش را همان لحظه نشان بده، شبکه را عقب نینداز، و poll را روشن کن. */
    private fun armChatLive() {
        pollPaused = false
        emptyPollStreak = 0
        startChatPoll()
        fetchHistory(bindIfWanted = true, force = true)
    }

    /** برگشت به چتِ باز: همگام‌سازی فوری، بدون انتظار برای دور بعدی. */
    private fun resumeChatLive() {
        pollPaused = false
        emptyPollStreak = 0
        startChatPoll()
        if (threadId.isNullOrBlank() || boundFingerprint.isEmpty()) {
            fetchHistory(bindIfWanted = true, force = true)
        } else {
            pollSync()
        }
    }

    private fun startChatPoll() {
        if (chatPollJob?.isActive == true) return
        chatPollJob = scope.launch {
            while (isActive) {
                delay(ChatLive.POLL_MS)
                pollSync()
            }
        }
    }

    private fun stopChatPoll() {
        chatPollJob?.cancel()
        chatPollJob = null
        pollPaused = true
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
        if (chatLayerOpen) {
            showHomeEntry()
            return true
        }
        return false
    }

    private fun closeCustomer(): Boolean {
        val b = _binding ?: return false
        if (!b.dashboardCustomer.isVisible) return false
        b.dashboardCustomerFirst.clearFocus()
        b.dashboardCustomerLast.clearFocus()
        b.dashboardCustomerPhone.clearFocus()
        b.dashboardCustomerNotes.clearFocus()
        hideKeyboard()
        b.dashboardCustomer.isVisible = false
        showCustomerError(null)
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
        if (currentAccountKey() == null) {
            Toast.makeText(requireContext(), "برای ارسال فایل وارد شوید.", Toast.LENGTH_SHORT).show()
            releaseChatAccount()
            (activity as? MainActivity)?.requireChatLogin()
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
        if (currentAccountKey() == null) {
            Toast.makeText(requireContext(), "برای ارسال فایل وارد شوید.", Toast.LENGTH_SHORT).show()
            releaseChatAccount()
            (activity as? MainActivity)?.requireChatLogin()
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
                if (http == 401 || resp.optBoolean("session_expired")) {
                    clearPendingAttachment()
                    (activity as? MainActivity)?.openLogin(sessionExpired = resp.optBoolean("session_expired"))
                    return@launch
                }
                if (http == 403 || resp.optBoolean("need_login")) {
                    clearPendingAttachment()
                    Toast.makeText(ctx, resp.optString("error").ifBlank { "برای ارسال فایل وارد شوید." }, Toast.LENGTH_SHORT).show()
                    releaseChatAccount()
                    (activity as? MainActivity)?.requireChatLogin()
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

    fun openCustomerModal() {
        if (!PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openLogin()
            return
        }
        closeAttach()
        showCustomerError(null)
        binding.dashboardCustomer.isVisible = true
        binding.dashboardCustomerFirst.requestFocus()
    }

    private fun openCustomer() = openCustomerModal()

    private fun submitCustomer() {
        if (customerBusy) return
        if (!PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openLogin()
            return
        }
        val first = binding.dashboardCustomerFirst.text?.toString().orEmpty()
        val last = binding.dashboardCustomerLast.text?.toString().orEmpty()
        val phone = binding.dashboardCustomerPhone.text?.toString().orEmpty()
        val notes = binding.dashboardCustomerNotes.text?.toString().orEmpty()
        val problem = CustomerRegister.error(first, last, phone, notes)
        if (problem != null) {
            showCustomerError(problem)
            return
        }
        showCustomerError(null)
        val body = CustomerRegister.body(first, last, phone, notes)
        setCustomerBusy(true)
        val ctx = requireContext()
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { ensureCustomerCsrf(ctx) }
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postPage(CustomerRegister.PATH, body, token, CustomerRegister.REFERER, ctx)
                }
                if (_binding == null) return@launch
                val fresh = resp.optString("csrf_token").trim()
                if (fresh.isNotBlank()) customerCsrf = fresh
                val http = resp.optInt("http_status")
                if (http == 401 || resp.optBoolean("session_expired")) {
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (PartnerCustomers.isUnauthorized(resp)) {
                    customerCsrf = ""
                    Toast.makeText(ctx, "برای ثبت مشتری وارد شوید.", Toast.LENGTH_SHORT).show()
                    (activity as? MainActivity)?.openLogin()
                    return@launch
                }
                if (http == 403) {
                    Toast.makeText(
                        ctx,
                        resp.optString("error").ifBlank { "برای ثبت درخواست وارد شوید." },
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                if (PartnerCustomers.isOk(resp)) {
                    clearCustomerForm()
                    closeCustomer()
                    Toast.makeText(
                        ctx,
                        PartnerCustomers.userMessage(resp, "مشتری ثبت شد."),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    showCustomerError(PartnerCustomers.userMessage(resp, "ثبت ناموفق بود."))
                }
            } catch (e: Exception) {
                if (_binding != null) showCustomerError(e.message ?: "خطای شبکه")
            } finally {
                if (_binding != null) setCustomerBusy(false)
            }
        }
    }

    private fun ensureCustomerCsrf(ctx: android.content.Context): String {
        if (customerCsrf.isNotBlank()) return customerCsrf
        val profile = try {
            PartnerApi.get("/express/partner/profile/data", ctx)
        } catch (_: Exception) {
            JSONObject()
        }
        var token = profile.optString("csrf_token").trim()
        if (token.isBlank()) {
            val html = try {
                PartnerApi.getHtml("/express/partner/home", ctx)
            } catch (_: Exception) {
                ""
            }
            token = PartnerCustomers.csrfFromHtml(html)
        }
        customerCsrf = token
        return token
    }

    private fun showCustomerError(text: String?) {
        val b = _binding ?: return
        val message = text?.trim().orEmpty()
        b.dashboardCustomerError.text = message
        b.dashboardCustomerError.isVisible = message.isNotEmpty()
    }

    private fun setCustomerBusy(busy: Boolean) {
        customerBusy = busy
        val b = _binding ?: return
        b.dashboardCustomerProgress.isVisible = busy
        b.dashboardCustomerSubmit.isEnabled = !busy
        b.dashboardCustomerSubmit.alpha = if (busy) 0.55f else 1f
    }

    private fun clearCustomerForm() {
        val b = _binding ?: return
        b.dashboardCustomerFirst.setText("")
        b.dashboardCustomerLast.setText("")
        b.dashboardCustomerPhone.setText("")
        b.dashboardCustomerNotes.setText("")
        showCustomerError(null)
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

    private fun loadAll(fromPull: Boolean = false, forceSkeleton: Boolean = false) {
        val ctx = context ?: return
        if (_binding == null) return
        if (currentAccountKey() != null) {
            fetchHistory(bindIfWanted = wantChatLayer)
        } else {
            releaseChatAccount()
        }
        val gen = ++loadGen
        val stamp = sessionStamp
        if ((forceSkeleton || !homeRendered) && !fromPull) showHomeLoading()
        scope.launch {
            val wasFresh = withContext(Dispatchers.IO) { PartnerApi.isFresh(HOME_DATA, ctx) }
            try {
                val fetched = PartnerApi.loadDisplay(HOME_DATA, ctx) { }
                if (gen != loadGen || stamp != sessionStamp || _binding == null) return@launch
                if (PartnerApi.isSessionExpired(fetched) || fetched.optInt("http_status") == 401) {
                    clearRememberedHome()
                    cachedHistory = null
                    threadId = null
                    homeRendered = false
                    (activity as? MainActivity)?.openLogin(
                        sessionExpired = fetched.optBoolean("session_expired"),
                    )
                    return@launch
                }
                if (fetched.optInt("http_status") == 403) {
                    if (!homeRendered && !wantChatLayer && !chatLayerOpen) showHomeEmpty()
                    if (!homeRendered) showOfflineSnackbar()
                    return@launch
                }
                val networkFailed = !wasFresh && (
                    fetched.optBoolean("from_cache") ||
                        fetched.optBoolean("offline") ||
                        (fetched.has("http_status") && fetched.optInt("http_status") !in 200..299)
                    )
                val home = withAds(fetched, ctx)
                if (gen != loadGen || stamp != sessionStamp || _binding == null) return@launch
                if (homePayloadOk(home)) {
                    bindHome(home)
                    homeRendered = true
                    if (!wantChatLayer && !chatLayerOpen) showHomeEmpty()
                    if (networkFailed) showOfflineSnackbar()
                } else if (!wantChatLayer && !chatLayerOpen && !homeRendered) {
                    showHomeEmpty()
                    showOfflineSnackbar()
                } else {
                    showOfflineSnackbar()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (gen != loadGen || stamp != sessionStamp || _binding == null) return@launch
                if (!homeRendered && !wantChatLayer && !chatLayerOpen) showHomeEmpty()
                showOfflineSnackbar()
            } finally {
                if (gen == loadGen && _binding != null && !wantChatLayer && !fromPull) finishRefreshUi()
            }
        }
    }

    /** آگهی‌ها قبل از اولین رسم می‌آیند تا کارت‌ها بعد از بقیهٔ خانه ظاهر نشوند. */
    private suspend fun withAds(home: JSONObject, ctx: android.content.Context): JSONObject {
        if (!homePayloadOk(home)) return home
        if ((home.optJSONArray("divar_pro_ads")?.length() ?: 0) > 0) return home
        if (HomeSuggest.files(home).length() > 0) return home
        return try {
            val html = withContext(Dispatchers.IO) { PartnerApi.getHtml(HOME_HTML, ctx) }
            val ads = HomeAdsHtml.parse(html)
            if (ads.length() > 0) {
                home.put("divar_pro_ads", ads)
                if (home.optInt("divar_pro_ads_count") <= 0) {
                    home.put("divar_pro_ads_count", ads.length())
                }
                home.remove("from_cache")
                PartnerApi.remember(HOME_DATA, home, ctx)
            }
            home
        } catch (_: Exception) {
            home
        }
    }

    private fun homePayloadOk(obj: JSONObject): Boolean {
        if (obj.optBoolean("offline") || obj.optBoolean("session_expired")) return false
        val code = if (obj.has("http_status")) obj.optInt("http_status") else 200
        if (code !in 200..299) return false
        return obj.optBoolean("success") || obj.has("is_logged_in") || obj.has("is_approved")
    }

    private fun showHomeLoading() {
        val b = _binding ?: return
        if (wantChatLayer || chatLayerOpen) return
        // Search has its own loading state: never block the property feed on account data.
        b.dashboardProgress.isVisible = false
        b.dashboardEmpty.isVisible = true
        b.dashboardHomeHeader.isVisible = false
        b.dashboardSearchHeaderHost.isVisible = true
    }

    private fun showOfflineSnackbar() {
        val b = _binding ?: return
        val bar = Snackbar.make(b.root, "اتصال برقرار نشد", Snackbar.LENGTH_LONG)
        bar.setAction("تلاش دوباره") { loadAll(forceSkeleton = !homeRendered) }
        bar.setActionTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_on_primary))
        bar.view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.vinor_raised))
        bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.let { text ->
            text.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_text))
            text.typeface = ResourcesCompat.getFont(requireContext(), R.font.vazirmatn)
        }
        bar.show()
    }

    /** آخرین خانهٔ دیده‌شده — ورود بعدی به تب خانه منتظر شبکه نمی‌ماند. */
    private fun paintRememberedHome() {
        val snap = rememberedHome ?: return
        bindHome(snap)
        if (!wantChatLayer) showHomeEmpty()
    }

    private fun showHomeEmpty() {
        val b = _binding ?: return
        chatLayerOpen = false
        b.dashboardHeader.isVisible = false
        b.dashboardHomeHeader.isVisible = false
        b.dashboardSearchHeaderHost.isVisible = true
        b.dashboardEmpty.isVisible = true
        hideKeyboard()
        syncComposerVisible()
        (activity as? MainActivity)?.syncDivarTab()
    }

    private fun fetchHistory(bindIfWanted: Boolean, force: Boolean = false) {
        val ctx = context ?: return
        val account = currentAccountKey()
        if (account == null) {
            releaseChatAccount()
            if (bindIfWanted) (activity as? MainActivity)?.requireChatLogin()
            finishRefreshUi()
            return
        }
        if (ChatSessionGate.shouldDropMemory(chatAccount, account)) discardAccountChat()
        chatAccount = account
        if (historyInFlight) {
            if (force) historyQueued = true
            return
        }
        val gen = chatBindGen
        val write = chatWriteGen
        historyInFlight = true
        scope.launch {
            var peeked: JSONObject? = null
            try {
                val offline = VinorNetworkStatus.useCacheFirst(ctx)
                val wasFresh = withContext(Dispatchers.IO) {
                    PartnerApi.isFresh(ChatLive.HISTORY, ctx)
                }
                peeked = withContext(Dispatchers.IO) {
                    PartnerApi.peekJson(ChatLive.HISTORY, ctx)
                }
                if (!chatStill(account, gen)) return@launch
                if (offline && peeked != null && wantChatLayer &&
                    (_binding?.dashboardChatList?.childCount ?: 0) == 0
                ) {
                    bindChatIfNeeded(peeked)
                }
                val urgent = force || wantChatLayer
                if (!urgent && peeked != null && wasFresh) {
                    rememberHistory(peeked)
                    return@launch
                }
                val path = ChatLive.historyPath(threadId)
                val hist = withContext(Dispatchers.IO) {
                    PartnerApi.get(path, ctx)
                }
                if (write != chatWriteGen) {
                    PartnerApi.forget(ChatLive.HISTORY, ctx)
                    if (path != ChatLive.HISTORY) PartnerApi.forget(path, ctx)
                    cachedHistory?.let { snap ->
                        if (!snap.optBoolean("from_cache")) PartnerApi.remember(ChatLive.HISTORY, snap, ctx)
                    }
                }
                if (!chatStill(account, gen)) return@launch
                val status = hist.optInt("http_status")
                if (status == 401 || status == 403 || hist.optBoolean("session_expired")) {
                    releaseChatAccount()
                    if (wantChatLayer) {
                        (activity as? MainActivity)?.requireChatLogin(
                            sessionExpired = status == 401 || hist.optBoolean("session_expired"),
                        )
                    }
                    return@launch
                }
                if (path != ChatLive.HISTORY && chatCacheable(hist) &&
                    ChatLive.shouldReplaceTranscript(ChatLive.messagesOf(cachedHistory), ChatLive.messagesOf(hist))
                ) {
                    PartnerApi.remember(ChatLive.HISTORY, hist, ctx)
                }
                val networkFailed = !wasFresh && (
                    hist.optBoolean("from_cache") ||
                        hist.optBoolean("offline") ||
                        (hist.has("http_status") && hist.optInt("http_status") !in 200..299)
                    )
                if (wantChatLayer) {
                    val usable = chatCacheable(hist) || hist.optBoolean("from_cache")
                    if (usable) bindChatIfNeeded(hist)
                    if (networkFailed) showOfflineSnackbar()
                } else if (
                    ChatLive.shouldReplaceTranscript(ChatLive.messagesOf(cachedHistory), ChatLive.messagesOf(hist))
                ) {
                    rememberHistory(hist)
                }
            } catch (_: Exception) {
                if (!chatStill(account, gen) || !wantChatLayer) return@launch
                val fallback = peeked
                if (fallback != null && (_binding?.dashboardChatList?.childCount ?: 0) == 0) {
                    bindChatIfNeeded(fallback)
                }
                showOfflineSnackbar()
            } finally {
                historyInFlight = false
                val again = historyQueued
                historyQueued = false
                if (wantChatLayer) finishRefreshUi()
                if (again && _binding != null && currentAccountKey() == account) {
                    fetchHistory(bindIfWanted = true, force = true)
                }
            }
        }
    }

    private fun pollSync() {
        val ctx = context ?: return
        val account = currentAccountKey() ?: return
        val tid = threadId ?: return
        if (!ChatLive.shouldPoll(
                wantChatLayer && chatLayerOpen,
                tid,
                chatSending,
                historyInFlight || syncInFlight,
                pollPaused,
            )
        ) {
            return
        }
        val gen = chatBindGen
        val messages = ChatLive.messagesOf(cachedHistory)
        val path = ChatLive.syncPath(
            tid,
            ChatLive.syncCount(messages),
            lastSyncAt,
            ChatLive.escalationIds(messages),
        )
        syncInFlight = true
        scope.launch {
            try {
                val obj = withContext(Dispatchers.IO) { PartnerApi.get(path, ctx) }
                if (!chatStill(account, gen) || !wantChatLayer) return@launch
                when (ChatLive.syncOutcome(obj)) {
                    ChatLive.SyncOutcome.AUTH -> {
                        releaseChatAccount()
                        (activity as? MainActivity)?.requireChatLogin()
                    }
                    ChatLive.SyncOutcome.EMPTY -> {
                        emptyPollStreak = ChatLive.nextEmptyStreak(emptyPollStreak, false)
                        if (ChatLive.shouldPause(emptyPollStreak)) pollPaused = true
                    }
                    ChatLive.SyncOutcome.MESSAGES -> {
                        emptyPollStreak = 0
                        pollPaused = false
                        absorbLiveMessages(ChatLive.messagesOf(obj))
                    }
                }
            } catch (_: Exception) {
            } finally {
                syncInFlight = false
            }
        }
    }

    private fun absorbLiveMessages(incoming: JSONArray) {
        if (!wantChatLayer || !chatLayerOpen || _binding == null) return
        val base = cachedHistory ?: JSONObject()
        val existing = ChatLive.messagesOf(base)
        lastSyncAt = ChatLive.laterSyncAt(lastSyncAt, ChatLive.lastSyncAt(incoming))
        val next = ChatLive.reconcile(existing, incoming) ?: return
        base.put("messages", next)
        base.remove("from_cache")
        threadId?.let { if (base.optString("thread_id").isBlank()) base.put("thread_id", it) }
        cachedHistory = base
        boundMessageCount = next.length()
        boundFingerprint = ChatLive.fingerprint(next)
        lastSyncAt = ChatLive.laterSyncAt(lastSyncAt, ChatLive.lastSyncAt(next))
        context?.let { PartnerApi.remember(ChatLive.HISTORY, base, it) }
        if (ChatLive.sameVisible(existing, next)) return
        if (next.length() > existing.length() && !transcriptEdited(existing, next)) {
            for (i in existing.length() until next.length()) {
                val msg = next.optJSONObject(i) ?: continue
                val att = msg.optJSONObject("attachment")
                    ?: msg.optJSONObject("meta")?.optJSONObject("attachment")
                addBubble(msg.optString("role"), msg.optString("content"), msg.optJSONArray("land_codes"), att)
            }
            stickToLatestIfFollowing()
        } else {
            renderMessages(next, stick = followingLatest() || next.length() > existing.length())
        }
        val codes = landCodesIn(next).filter { !landsByCode.containsKey(it) }
        if (codes.isEmpty()) return
        val gen = chatBindGen
        val fp = boundFingerprint
        scope.launch {
            coroutineScope { codes.map { code -> async { prefetchLand(code) } }.awaitAll() }
            if (_binding == null || gen != chatBindGen || !wantChatLayer) return@launch
            if (fp == boundFingerprint) renderMessages(next, stick = followingLatest())
        }
    }

    private fun transcriptEdited(before: JSONArray, after: JSONArray): Boolean {
        val n = minOf(before.length(), after.length())
        for (i in 0 until n) {
            val a = before.optJSONObject(i)
            val b = after.optJSONObject(i)
            if (ChatLive.fingerprint(JSONArray().put(a)) != ChatLive.fingerprint(JSONArray().put(b))) return true
        }
        return false
    }

    private fun chatCacheable(obj: JSONObject): Boolean {
        if (obj.optBoolean("from_cache") || obj.optBoolean("offline")) return false
        val status = obj.optInt("http_status", 200)
        return status in 200..299
    }

    private fun currentAccountKey(): String? {
        if (!PartnerApi.hasSession()) return null
        val ctx = context ?: return ChatSessionGate.accountKey(true, VinorCacheSession.ANON)
        val fp = runCatching { VinorCacheSession.fingerprint(ctx) }.getOrDefault(VinorCacheSession.ANON)
        return ChatSessionGate.accountKey(true, fp)
    }

    private fun chatStill(account: String, gen: Int): Boolean {
        if (_binding == null || gen != chatBindGen) return false
        return currentAccountKey() == account
    }

    /** خروج یا عوض شدن حساب: ترد، حباب‌ها و پیوست همین صفحه پاک می‌شوند. */
    fun releaseChatAccount() {
        val wasOpen = chatLayerOpen || wantChatLayer
        discardAccountChat()
        wantChatLayer = false
        if (wasOpen && _binding != null) showHomeEntry() else chatLayerOpen = false
    }

    private fun discardAccountChat() {
        chatBindGen += 1
        threadId = null
        cachedHistory = null
        boundMessageCount = 0
        boundFingerprint = ""
        lastSyncAt = ""
        emptyPollStreak = 0
        chatSending = false
        historyQueued = false
        chatAccount = null
        landsByCode.clear()
        pendingAttachment = null
        attachUploading = false
        val b = _binding ?: return
        b.dashboardChatList.removeAllViews()
        b.dashboardChatInput.setText("")
        b.dashboardAttachPreview.isVisible = false
        b.dashboardAttachPreview.alpha = 1f
        if (b.dashboardAttachSheet.isVisible || b.dashboardAttachScrim.isVisible) closeAttach()
        syncSendEnabled()
    }

    private fun bindChatIfNeeded(obj: JSONObject) {
        val messages = ChatLive.messagesOf(obj)
        val current = ChatLive.messagesOf(cachedHistory)
        threadFrom(obj)?.let { tid ->
            if (threadId.isNullOrBlank() || ChatLive.shouldReplaceTranscript(current, messages)) threadId = tid
        }
        if (!ChatLive.shouldReplaceTranscript(current, messages)) return
        rememberHistory(obj)
        lastSyncAt = ChatLive.laterSyncAt(lastSyncAt, ChatLive.lastSyncAt(messages))
        if (ChatLive.fingerprint(messages) == boundFingerprint &&
            (_binding?.dashboardChatList?.childCount ?: 0) > 0
        ) {
            return
        }
        bindChat(obj)
    }

    private fun rememberHistory(obj: JSONObject) {
        cachedHistory = obj
        threadFrom(obj)?.let { threadId = it }
    }

    private fun threadFrom(obj: JSONObject): String? {
        var tid = obj.optString("thread_id").takeIf { it.isNotBlank() }
            ?: obj.optJSONObject("data")?.optString("thread_id")?.takeIf { it.isNotBlank() }
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
        return tid
    }

    private fun bindHomeAvatar(home: JSONObject) {
        if (_binding == null) return
        val loggedIn = home.optBoolean("is_logged_in") || home.optString("me_name").isNotBlank()
        if (!loggedIn) {
            homeAvatarLookup = false
            showHomeAvatarFallback()
            return
        }
        val ctx = context
        val profile = if (ctx != null) PartnerApi.peekJson("/express/partner/profile/data", ctx) else null
        val url = VinorAvatarCache.urlFrom(home, profile)
        val memUrl = VinorAvatarCache.currentUrl()
        val memNewer = memUrl.isNotEmpty() &&
            (url.isEmpty() || VinorAvatarCache.version(memUrl) > VinorAvatarCache.version(url) ||
                (VinorAvatarCache.version(memUrl) == VinorAvatarCache.version(url) &&
                    VinorAvatarCache.base(memUrl) == VinorAvatarCache.base(url)))
        if (memNewer) {
            VinorAvatarCache.currentBitmap()?.let {
                applyHomePhoto(it)
                if (url.isEmpty() || VinorAvatarCache.samePhoto(memUrl, url)) return
            }
        }
        if (url.isNotEmpty()) {
            showHomeAvatar(url)
            return
        }
        if (profile != null || homeAvatarLookup || ctx == null) {
            if (profile != null) showHomeAvatarFallback()
            return
        }
        homeAvatarLookup = true
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { PartnerApi.get("/express/partner/profile/data", ctx) }.getOrNull()
            }
            if (_binding == null) return@launch
            val found = VinorAvatarCache.urlFrom(fresh)
            if (found.isEmpty()) showHomeAvatarFallback() else showHomeAvatar(found)
        }
    }

    private fun showHomeAvatar(url: String) {
        val resolved = VinorAvatarCache.resolve(url)
        if (resolved.isEmpty()) {
            showHomeAvatarFallback()
            return
        }
        homeAvatarWanted = resolved
        VinorAvatarCache.peek(resolved)?.let {
            applyHomePhoto(it)
            return
        }
        if (homeAvatarLoading == resolved) return
        homeAvatarLoading = resolved
        val ctx = context ?: return
        val cookie = android.webkit.CookieManager.getInstance().getCookie(PartnerApi.BASE).orEmpty()
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    VinorAvatarCache.read(ctx, resolved)
                        ?: VinorAvatarCache.fetch(resolved, cookie)?.also { fresh ->
                            VinorAvatarCache.store(ctx, resolved, fresh)
                        }
                }
                if (_binding == null || homeAvatarWanted != resolved) return@launch
                if (bytes == null) {
                    showHomeAvatarFallback()
                    return@launch
                }
                val bmp = withContext(Dispatchers.IO) { VinorAvatarCache.decode(bytes) } ?: return@launch
                VinorAvatarCache.rememberBitmap(resolved, bmp)
                if (_binding == null || homeAvatarWanted != resolved) return@launch
                applyHomePhoto(bmp)
            } finally {
                if (homeAvatarLoading == resolved) homeAvatarLoading = null
            }
        }
    }

    private fun applyHomePhoto(bmp: Bitmap) {
        val view = binding.dashboardHomeAvatar
        view.setPadding(0, 0, 0, 0)
        ImageViewCompat.setImageTintList(view, null)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bmp)
    }

    private fun showHomeAvatarFallback() {
        homeAvatarWanted = null
        val view = binding.dashboardHomeAvatar
        val pad = (9 * resources.displayMetrics.density).toInt()
        view.setPadding(pad, pad, pad, pad)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        ImageViewCompat.setImageTintList(
            view,
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.vinor_text)),
        )
        view.setImageResource(R.drawable.ic_profile)
    }

    private fun bindHome(obj: JSONObject) {
        isApproved = obj.optBoolean("is_approved")
        val ads = obj.optJSONArray("divar_pro_ads") ?: JSONArray()
        rememberHome(obj)
        // Use the catalog already obtained by Home as the immediate result
        // source; the embedded search still refreshes from its own endpoint.
        (childFragmentManager.findFragmentById(R.id.dashboardSearchContainer) as? SearchFragment)
            ?.acceptHomeListings(obj)
        if (isChatOpen()) {
            binding.dashboardEmpty.isVisible = false
            binding.dashboardHeader.isVisible = true
            binding.dashboardHomeHeader.isVisible = false
            binding.dashboardSearchHeaderHost.isVisible = false
        } else {
            binding.dashboardHeader.isVisible = false
            binding.dashboardHomeHeader.isVisible = false
            binding.dashboardSearchHeaderHost.isVisible = true
            binding.dashboardEmpty.isVisible = true
        }

        val name = obj.optString("me_name")
        binding.dashboardGreetName.text = if (name.isBlank()) "سلام، همکار عزیز" else "سلام، $name"
        bindHomeAvatar(obj)
        binding.dashboardGreetSub.text = when {
            isApproved -> "آماده‌ای بفروشی؟"
            obj.optBoolean("has_pending_app") -> "درخواستت در حال بررسی است"
            else -> "آماده‌ای همکاری کنی؟"
        }
        binding.dashboardWallet.isVisible = isApproved
        binding.dashboardPerf.isVisible = isApproved
        val reqCount = HomePulse.requests(obj).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (isApproved) {
            binding.dashboardWalletAmount.text = PartnerFormat.toman(HomePulse.withdrawable(obj)) + " تومان"
            // رشد ماهانه در طرح جدید خانه نشان داده نمی‌شود؛ فقط فلش کیف پول.
            binding.dashboardWalletGrowth.isVisible = false
            binding.dashboardPerfDeals.text = PartnerFormat.toman(HomePulse.deals(obj))
            binding.dashboardPerfVisits.text = PartnerFormat.toman(HomePulse.visits(obj))
            binding.dashboardPerfCustomers.text = PartnerFormat.toman(HomePulse.customers(obj))
            binding.dashboardShortcutFollowTitle.text = "پیگیری"
            binding.dashboardShortcutFollow.contentDescription = "پیگیری"
            binding.dashboardFollowBadge.isVisible = reqCount > 0
            if (reqCount > 0) binding.dashboardFollowBadge.text = PartnerFormat.toman(reqCount)
            binding.dashboardShortcutFollow.setOnClickListener {
                (activity as? MainActivity)?.openPath("/express/partner/requests")
            }
        } else {
            binding.dashboardShortcutFollowTitle.text = "سطوح همکاری"
            binding.dashboardShortcutFollow.contentDescription = "سطوح همکاری"
            binding.dashboardFollowBadge.isVisible = false
            binding.dashboardShortcutFollow.setOnClickListener {
                (activity as? MainActivity)?.openPath("/express/partner/levels")
            }
        }

        val adsCount = obj.optInt("divar_pro_ads_count").let { if (it > 0) it else ads.length() }
        bindPrompts(obj, adsCount, reqCount)
        // All account widgets now belong to Vinor Man, while the home feed holds only listings.
        binding.dashboardAccountBlock.isVisible = false
        binding.dashboardHero.isVisible = false
        binding.dashboardSuggestBlock.isVisible = false
        binding.dashboardTiles.isVisible = false
    }

    private fun bindSuggest(obj: JSONObject) {
        val files = HomeSuggest.files(obj)
        val show = files.length() > 0
        binding.dashboardSuggestBlock.isVisible = show
        if (!show) {
            suggestKey = ""
            binding.dashboardSuggest.removeAllViews()
            return
        }
        val key = HomeSuggest.fingerprint(files)
        if (key == suggestKey && binding.dashboardSuggest.childCount > 0) return
        suggestKey = key
        binding.dashboardSuggest.removeAllViews()
        val limit = minOf(files.length(), HomeSuggest.LIMIT)
        val dividerColor = ContextCompat.getColor(requireContext(), R.color.vinor_border)
        for (i in 0 until limit) {
            val ad = files.optJSONObject(i) ?: continue
            if (i > 0) {
                val line = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    )
                    setBackgroundColor(dividerColor)
                }
                binding.dashboardSuggest.addView(line)
            }
            val card = ItemHomeSuggestBinding.inflate(layoutInflater, binding.dashboardSuggest, true)
            card.suggestTitle.text = HomeSuggest.displayTitle(ad)
            card.suggestSpec.isVisible = false
            val price = HomeSuggest.priceLine(ad)
            card.suggestPrice.isVisible = price.isNotBlank()
            card.suggestPrice.text = price
            val location = HomeSuggest.locationLine(ad)
            card.suggestLocBar.isVisible = location.isNotBlank()
            card.suggestLocation.text = location
            val age = HomeSuggest.listedAge(ad)
            card.suggestAge.isVisible = age.isNotBlank()
            card.suggestAge.text = age
            bindSuggestBadge(card, HomeSuggest.badge(ad))
            val commission = HomeSuggest.commissionLabel(ad)
            card.suggestCommissionRow.isVisible = commission.isNotBlank()
            card.suggestCommission.text = commission
            PartnerImages.roundClip(card.suggestImage, 12f)
            PartnerImages.bind(scope, card.suggestImage, HomeSuggest.imageUrl(ad))
            card.root.contentDescription = HomeSuggest.displayTitle(ad)
            val url = ad.optString("url").trim().let { if (it.isBlank() || it == "null") "" else it }
            card.root.isClickable = HomeSuggest.landCode(ad).isNotBlank() || url.isNotBlank()
            card.root.setOnClickListener { openSuggest(ad) }
        }
    }

    private fun bindSuggestBadge(card: ItemHomeSuggestBinding, badge: String) {
        card.suggestBadge.isVisible = badge.isNotBlank()
        if (badge.isBlank()) return
        card.suggestBadge.text = badge
        val tone = HomeSuggest.badgeTone(badge)
        val bg = when (tone) {
            "new" -> R.color.vinor_error
            "gold" -> R.color.vinor_badge_gold
            else -> R.color.vinor_accent_green
        }
        val fg = if (tone == "gold") R.color.vinor_bg else R.color.vinor_on_primary
        card.suggestBadge.setTextColor(ContextCompat.getColor(requireContext(), fg))
        card.suggestBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), bg),
        )
    }

    private fun openSuggest(ad: JSONObject) {
        val code = HomeSuggest.landCode(ad)
        if (code.isNotBlank()) {
            (activity as? MainActivity)?.openLand(code)
            return
        }
        val url = ad.optString("url").trim()
        if (url.isNotBlank() && url != "null") openVinorOrExternal(url)
    }

    private fun bindPrompts(obj: JSONObject, adsCount: Int, reqCount: Int) {
        var prompts = obj.optJSONArray("ai_quick_prompts") ?: JSONArray()
        if (prompts.length() == 0) prompts = defaultPrompts(obj)
        prompts = HomePrompts.withoutAdvisorBanner(prompts)
        var hero: JSONObject? = null
        val tiles = mutableListOf<JSONObject>()
        for (i in 0 until prompts.length()) {
            val p = prompts.optJSONObject(i) ?: continue
            if (p.optString("ui_action") == "open_divar_ads_all") continue
            if (p.optString("ui_action") == "open_customer_modal") continue
            if (p.optString("label") == "پیگیری") continue
            if (p.optString("variant") == "hero") {
                if (hero == null) hero = p
            } else {
                tiles.add(p)
            }
        }
        bindHeroPrompt(hero, reqCount)
        val slots = listOf(
            arrayOf(
                binding.dashboardTileBuy,
                binding.dashboardTileBuyIcon,
                binding.dashboardTileBuyTitle,
                binding.dashboardTileBuySub,
                binding.dashboardTileBuyCount,
            ),
            arrayOf(
                binding.dashboardTileLevels,
                binding.dashboardTileLevelsIcon,
                binding.dashboardTileLevelsTitle,
                binding.dashboardTileLevelsSub,
                binding.dashboardTileLevelsCount,
            ),
        )
        val icons = intArrayOf(R.drawable.ic_clipboard, R.drawable.ic_users_stroke)
        slots.forEachIndexed { index, slot ->
            val prompt = tiles.getOrNull(index)
            (slot[0] as View).isVisible = prompt != null
            if (prompt == null) return@forEachIndexed
            bindTilePrompt(
                tile = slot[0] as View,
                icon = slot[1] as ImageView,
                title = slot[2] as android.widget.TextView,
                sub = slot[3] as android.widget.TextView,
                count = slot[4] as android.widget.TextView,
                prompt = prompt,
                fallbackTitle = prompt.optString("label"),
                fallbackSub = prompt.optString("subtitle"),
                fallbackIcon = icons[index],
                fallbackAction = {},
                adsCount = adsCount,
                reqCount = reqCount,
            )
        }
        binding.dashboardTilesDivider.isVisible = tiles.size > 1
        binding.dashboardTiles.isVisible = tiles.isNotEmpty()
    }

    private fun defaultPrompts(obj: JSONObject): JSONArray {
        val out = JSONArray()
        if (obj.optBoolean("is_approved")) return out
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

    private fun bindHeroPrompt(prompt: JSONObject?, reqCount: Int = 0) {
        if (prompt == null || prompt.optString("ui_action") == "open_customer_modal") {
            binding.dashboardHero.isVisible = false
            return
        }
        binding.dashboardHero.isVisible = true
        val action = prompt.optString("ui_action").orEmpty()
        val sub = prompt.optString("subtitle").orEmpty()
        val label = prompt.optString("label").orEmpty().ifBlank { sub }
        binding.dashboardHeroKicker.isVisible = false
        binding.dashboardHeroSub.isVisible = sub.isNotBlank()
        binding.dashboardHeroSub.text = sub
        binding.dashboardHeroCount.isVisible = false
        binding.dashboardHeroStart.isVisible = true
        binding.dashboardHeroStart.text = "ادامه"
        binding.dashboardHero.contentDescription = label
        alignHeroLabel(customer = false)
        TextViewCompat.setAutoSizeTextTypeWithDefaults(
            binding.dashboardHeroTitle,
            TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE,
        )
        binding.dashboardHeroTitle.text = label
        binding.dashboardHeroTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.vinor_on_primary))
        binding.dashboardHeroTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        binding.dashboardHeroTitle.maxLines = 2
        binding.dashboardHeroIcon.setImageResource(heroIcon(prompt.optString("icon")))
        ImageViewCompat.setImageTintList(
            binding.dashboardHeroIcon,
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.vinor_customer_bg)),
        )
        val href = prompt.optString("href").orEmpty()
        binding.dashboardHero.setOnClickListener {
            when {
                action == "open_divar_ads_all" -> (activity as? MainActivity)?.openSearch()
                href.contains("/apply") || href.contains("/thanks") ->
                    (activity as? MainActivity)?.openConsultantOnboarding()
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
                action == "open_divar_ads_all" -> (activity as? MainActivity)?.openSearch()
                action == "open_customer_modal" -> openCustomer()
                href.contains("/apply") || href.contains("/thanks") ->
                    (activity as? MainActivity)?.openConsultantOnboarding()
                href.isNotBlank() -> openVinorOrExternal(href)
                else -> runPromptOr(prompt, fallbackAction)
            }
        }
    }

    private var heroLabelCentered = true

    /** «مشتری دارم» روی کل دکمه وسط می‌ماند؛ آیکون کناری مرکز متن را جابه‌جا نمی‌کند. */
    private fun alignHeroLabel(customer: Boolean) {
        heroLabelCentered = customer
        val title = binding.dashboardHeroTitle
        val body = binding.dashboardHeroBody
        val lp = body.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
        lp.gravity = if (customer) Gravity.CENTER else Gravity.CENTER_VERTICAL
        body.layoutParams = lp
        body.gravity = if (customer) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        if (customer) {
            title.gravity = Gravity.CENTER
            title.textAlignment = View.TEXT_ALIGNMENT_CENTER
            title.includeFontPadding = false
        } else {
            title.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            title.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            title.includeFontPadding = true
            val gap = (12 * resources.displayMetrics.density).toInt()
            val reserve = (binding.dashboardHeroChip.width.takeIf { it > 0 }
                ?: (96 * resources.displayMetrics.density).toInt()) + gap
            body.setPaddingRelative(reserve, 0, 0, 0)
        }
        placeHeroLabel()
    }

    /** حاشیهٔ متقارن دور متن تا وسط هندسی دکمه همان وسط کلمه بماند. */
    private fun placeHeroLabel() {
        val body = _binding?.dashboardHeroBody ?: return
        val chip = binding.dashboardHeroChip
        val density = resources.displayMetrics.density
        if (heroLabelCentered) {
            val gap = (8 * density).toInt()
            val chipWidth = chip.width.takeIf { it > 0 } ?: (50 * density).toInt()
            val side = chipWidth + gap
            if (body.paddingStart != side || body.paddingEnd != side) {
                body.setPaddingRelative(side, 0, side, 0)
            }
        } else if (chip.width > 0) {
            val reserve = chip.width + (12 * density).toInt()
            if (body.paddingStart != reserve || body.paddingEnd != 0) {
                body.setPaddingRelative(reserve, 0, 0, 0)
            }
        }
    }

    /** «مشتری» سفید و «دارم» سبز؛ ترتیب منطقی در متن راست‌به‌چپ همان طرح است. */
    private fun customerTitle(): SpannableString {
        val text = SpannableString("مشتری دارم")
        val split = text.indexOf(' ')
        val white = ContextCompat.getColor(requireContext(), R.color.vinor_on_primary)
        val green = ContextCompat.getColor(requireContext(), R.color.vinor_customer_word)
        text.setSpan(ForegroundColorSpan(white), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(green), split + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
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

    private fun monthGrowthLabel(pct: Int): String {
        val body = PartnerFormat.toman(kotlin.math.abs(pct))
        val sign = when {
            pct > 0 -> "+"
            pct < 0 -> "−"
            else -> ""
        }
        return "رشد این ماه $sign$body٪"
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

    private fun rememberHome(obj: JSONObject) {
        val ads = obj.optJSONArray("divar_pro_ads")?.length() ?: 0
        val prompts = obj.optJSONArray("ai_quick_prompts")?.length() ?: 0
        if (obj.optBoolean("offline") && ads == 0) return
        if (!obj.has("is_approved") && ads == 0 && prompts == 0 && obj.optString("me_name").isBlank()) return
        rememberedHome = JSONObject(obj.toString())
    }

    private fun bindChat(obj: JSONObject) {
        val account = currentAccountKey() ?: return
        if (!wantChatLayer || chatAccount != account) return
        rememberHistory(obj)
        val messages = ChatLive.messagesOf(obj)
        boundMessageCount = messages.length()
        boundFingerprint = ChatLive.fingerprint(messages)
        lastSyncAt = ChatLive.laterSyncAt(lastSyncAt, ChatLive.lastSyncAt(messages))
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
            if (boundFingerprint == ChatLive.fingerprint(messages)) {
                renderMessages(messages, stick = followingLatest())
            }
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
            val obj = withContext(Dispatchers.IO) { PartnerApi.get(path, ctx) }
            if (PartnerApi.isSessionExpired(obj) || obj.optInt("http_status") == 401 || obj.optInt("http_status") == 403) {
                return
            }
            val land = obj.optJSONObject("land") ?: return
            if (!land.has("code")) land.put("code", code)
            landsByCode[code] = land
            VinorLandCardCache.put(code, land)
        } catch (_: Exception) {
        }
    }

    private fun renderMessages(messages: JSONArray, stick: Boolean = true) {
        if (!wantChatLayer || !chatLayerOpen) {
            showHomeEmpty()
            return
        }
        binding.dashboardChatList.removeAllViews()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (ChatLive.isHidden(msg)) continue
            val att = msg.optJSONObject("attachment")
                ?: msg.optJSONObject("meta")?.optJSONObject("attachment")
            addBubble(msg.optString("role"), msg.optString("content"), msg.optJSONArray("land_codes"), att)
        }
        if (chatLayerOpen && wantChatLayer) {
            hideHomeDashboardWidgets()
            syncComposerVisible()
            if (stick) binding.dashboardScroll.post { binding.dashboardScroll.fullScroll(View.FOCUS_DOWN) }
            return
        }
        showHomeEmpty()
    }

    private fun followingLatest(): Boolean {
        val scroll = _binding?.dashboardScroll ?: return true
        val child = scroll.getChildAt(0) ?: return true
        val slack = (96 * resources.displayMetrics.density).toInt()
        return child.bottom <= scroll.height + scroll.scrollY + slack
    }

    private fun stickToLatestIfFollowing() {
        if (!followingLatest()) return
        val scroll = _binding?.dashboardScroll ?: return
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(role: String, content: String, codes: JSONArray?, attachment: JSONObject? = null): View? {
        if (content.isBlank() && (codes == null || codes.length() == 0) && attachment == null) return null
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
        return bubble.root
    }

    private fun inflateLandCard(land: JSONObject, parent: ViewGroup): ItemLandCardBinding {
        val card = ItemLandCardBinding.inflate(layoutInflater, parent, false)
        val code = land.optString("code")
        card.landCardTitle.text = land.optString("title", "—")
        card.landCardPrice.text = PartnerFormat.toman(WalletWithdraw.money(land, "price_total", "price")) + " تومان"
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
        val account = currentAccountKey()
        if (account == null) {
            (activity as? MainActivity)?.requireChatLogin()
            return
        }
        if (ChatSessionGate.shouldDropMemory(chatAccount, account)) discardAccountChat()
        chatAccount = account
        val text = prefill?.trim().orEmpty().ifBlank {
            binding.dashboardChatInput.text?.toString()?.trim().orEmpty()
        }
        val attach = pendingAttachment
        if (attachUploading) {
            Toast.makeText(requireContext(), "صبر کنید تا آپلود تمام شود.", Toast.LENGTH_SHORT).show()
            return
        }
        if (chatSending) return
        if (text.isEmpty() && attach == null) return
        closeAttach()
        binding.dashboardChatInput.setText("")
        val toSend = attach
        pendingAttachment = null
        binding.dashboardAttachPreview.isVisible = false
        syncSendEnabled()
        wantChatLayer = true
        chatLayerOpen = true
        chatSending = true
        chatBindGen += 1
        chatWriteGen += 1
        pollPaused = false
        emptyPollStreak = 0
        hideHomeDashboardWidgets()
        syncComposerVisible()
        (activity as? MainActivity)?.syncDivarTab()
        startChatPoll()
        context?.let { PartnerApi.forget(ChatLive.HISTORY, it) }
        val pending = addBubble("user", text, null, toSend)
        val thinking = addBubble("assistant", "…", null, null)
        scope.launch {
            var delivered = false
            try {
                val body = JSONObject().put("message", text)
                threadId?.let { body.put("thread_id", it) }
                if (chipIntent.isNotBlank()) body.put("intent", chipIntent)
                if (toSend != null) body.put("attachment", toSend)
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/ai/chat", body, requireContext())
                }
                if (_binding == null || currentAccountKey() != account) return@launch
                val http = resp.optInt("http_status")
                if (http == 401 || http == 403 || resp.optBoolean("session_expired")) {
                    if ((http == 401 || resp.optBoolean("session_expired")) && toSend != null) {
                        pendingAttachment = toSend
                        showAttachPreview(
                            toSend.optString("kind"),
                            toSend.optString("name"),
                            toSend.optLong("size"),
                            uploading = false,
                        )
                    }
                    releaseChatAccount()
                    (activity as? MainActivity)?.requireChatLogin(
                        sessionExpired = http == 401 || resp.optBoolean("session_expired"),
                    )
                    return@launch
                }
                if (resp.optBoolean("ok") || resp.optString("reply").isNotBlank()) {
                    val stored = ChatLive.applyExchange(cachedHistory, text, toSend, resp, threadId)
                    cachedHistory = stored
                    threadId = stored.optString("thread_id").takeIf { it.isNotBlank() } ?: threadId
                    val transcript = ChatLive.messagesOf(stored)
                    boundMessageCount = transcript.length()
                    boundFingerprint = ChatLive.fingerprint(transcript)
                    lastSyncAt = ChatLive.laterSyncAt(lastSyncAt, ChatLive.lastSyncAt(transcript))
                    context?.let { PartnerApi.remember(ChatLive.HISTORY, stored, it) }
                    if (wantChatLayer && chatLayerOpen) {
                        thinking?.let { _binding?.dashboardChatList?.removeView(it) }
                        renderMessages(transcript, stick = true)
                        paintMissingLands(transcript)
                    } else {
                        _binding?.dashboardChatList?.removeAllViews()
                        boundFingerprint = ""
                    }
                    delivered = true
                } else {
                    val err = resp.optString("error").ifBlank { "ارسال ناموفق بود." }
                    if (err.contains("ورود") || err.contains("لاگین")) {
                        releaseChatAccount()
                        (activity as? MainActivity)?.requireChatLogin()
                    } else {
                        restoreFailedSend(pending, thinking, text, toSend)
                        Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (_binding != null && wantChatLayer) {
                    restoreFailedSend(pending, thinking, text, toSend)
                    Toast.makeText(requireContext(), e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
                }
            } finally {
                chatSending = false
                if (!delivered) {
                    _binding?.dashboardChatList?.let { list -> thinking?.let { list.removeView(it) } }
                }
                if (_binding != null) syncSendEnabled()
                if (delivered && wantChatLayer && chatLayerOpen) pollSync()
            }
        }
    }

    private fun paintMissingLands(messages: JSONArray) {
        val missing = landCodesIn(messages).filter { !landsByCode.containsKey(it) }
        if (missing.isEmpty()) return
        val gen = chatBindGen
        val fp = boundFingerprint
        scope.launch {
            coroutineScope { missing.map { code -> async { prefetchLand(code) } }.awaitAll() }
            if (_binding == null || gen != chatBindGen || !wantChatLayer) return@launch
            if (fp == boundFingerprint) renderMessages(messages, stick = followingLatest())
        }
    }

    private fun restoreFailedSend(pending: View?, thinking: View?, text: String, attachment: JSONObject?) {
        val b = _binding ?: return
        thinking?.let { b.dashboardChatList.removeView(it) }
        pending?.let { b.dashboardChatList.removeView(it) }
        if (text.isNotBlank() && b.dashboardChatInput.text.isNullOrBlank()) {
            b.dashboardChatInput.setText(text)
        }
        if (attachment != null && pendingAttachment == null) {
            pendingAttachment = attachment
            showAttachPreview(
                attachment.optString("kind"),
                attachment.optString("name"),
                attachment.optLong("size"),
                false,
            )
        }
        syncSendEnabled()
    }
}
