package ir.vinor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import ir.vinor.app.databinding.ItemChatBubbleBinding
import ir.vinor.app.databinding.ItemHomeSuggestBinding
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

/** خانه نیتیو — میانبرها، ردیف فایل پیشنهادی و کارت مشتری در یک صفحه. چت فقط از تب فوتر. */
class DashboardFragment : Fragment() {

    companion object {
        private var rememberedHome: JSONObject? = null
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
    private var boundMessageCount = 0
    private var suggestKey = ""
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
        binding.dashboardBell.setOnClickListener { (activity as? MainActivity)?.openNotifications() }
        binding.dashboardWallet.setOnClickListener {
            (activity as? MainActivity)?.openPath("/express/partner/commissions")
        }
        binding.dashboardShortcutTrain.setOnClickListener {
            (activity as? MainActivity)?.openPath("/express/partner/training")
        }
        binding.dashboardShortcutNews.setOnClickListener {
            (activity as? MainActivity)?.openPath("/express/partner/news")
        }
        binding.dashboardTileLevels.setOnClickListener {
            (activity as? MainActivity)?.openPath("/express/partner/levels")
        }
        binding.dashboardCustomerBackdrop.setOnClickListener { closeCustomer() }
        binding.dashboardCustomerClose.setOnClickListener { closeCustomer() }
        binding.dashboardCustomerSubmit.setOnClickListener { submitCustomer() }
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
        syncSendEnabled()
        syncComposerVisible()
        paintRememberedHome()
        loadAll()
        if ((activity as? MainActivity)?.consumePendingVinorChat() == true) {
            openVinorChat()
        }
    }

    override fun onDestroyView() {
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
        if (b.dashboardCustomer.isVisible || b.dashboardAttachSheet.isVisible) {
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

    /** چتِ باز فقط وقتی مال همین نشست است که دوباره از شبکه پر نشود. */
    fun isCurrentAccountChatOpen(): Boolean {
        val account = currentAccountKey()
        return isChatOpen() && account != null && chatAccount == account
    }

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
        b.dashboardHomeHeader.isVisible = false
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
        if (chatLayerOpen) {
            showHomeEntry()
            return true
        }
        return false
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
                if (http == 401 || http == 403 || resp.optBoolean("need_login")) {
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
        if (currentAccountKey() != null) {
            fetchHistory(bindIfWanted = wantChatLayer)
        } else {
            releaseChatAccount()
        }
        scope.launch {
            var shownCache = binding.dashboardEmpty.isVisible
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
                } else if (!wantChatLayer && !shownCache) {
                    binding.dashboardProgress.isVisible = true
                }
            } else {
                shownCache = binding.dashboardEmpty.isVisible
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

    /** آخرین خانهٔ دیده‌شده — ورود بعدی به تب آگهی‌ها منتظر شبکه نمی‌ماند. */
    private fun paintRememberedHome() {
        val snap = rememberedHome ?: return
        bindHome(snap)
        if (!wantChatLayer) showHomeEmpty()
    }

    private fun showHomeEmpty() {
        val b = _binding ?: return
        chatLayerOpen = false
        b.dashboardHeader.isVisible = false
        b.dashboardHomeHeader.isVisible = true
        b.dashboardEmpty.isVisible = true
        hideKeyboard()
        syncComposerVisible()
        (activity as? MainActivity)?.syncDivarTab()
    }

    private fun fetchHistory(bindIfWanted: Boolean) {
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
        val gen = chatBindGen
        scope.launch {
            try {
                val peeked = withContext(Dispatchers.IO) {
                    PartnerApi.peekJson("/express/partner/ai/history", ctx)
                }
                if (!chatStill(account, gen)) return@launch
                if (peeked != null && bindIfWanted && wantChatLayer) bindChatIfNeeded(peeked)
                val hist = withContext(Dispatchers.IO) {
                    PartnerApi.get("/express/partner/ai/history", ctx)
                }
                if (!chatStill(account, gen)) return@launch
                val status = hist.optInt("http_status")
                if (status == 401 || status == 403) {
                    releaseChatAccount()
                    if (bindIfWanted) (activity as? MainActivity)?.requireChatLogin()
                    return@launch
                }
                rememberHistory(hist)
                if (bindIfWanted && wantChatLayer) bindChatIfNeeded(hist)
            } catch (_: Exception) {
                if (chatStill(account, gen) && bindIfWanted && wantChatLayer &&
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
        val ads = obj.optJSONArray("divar_pro_ads") ?: JSONArray()
        rememberHome(obj)
        if (isChatOpen()) {
            binding.dashboardEmpty.isVisible = false
            binding.dashboardHeader.isVisible = true
            binding.dashboardHomeHeader.isVisible = false
        } else {
            binding.dashboardHeader.isVisible = false
            binding.dashboardHomeHeader.isVisible = true
            binding.dashboardEmpty.isVisible = true
        }

        val name = obj.optString("me_name")
        binding.dashboardGreetName.text = if (name.isBlank()) "سلام، همکار عزیز" else "سلام، $name"
        binding.dashboardGreetSub.text = when {
            isApproved -> "آماده‌ای بفروشی؟"
            obj.optBoolean("has_pending_app") -> "درخواستت در حال بررسی است"
            else -> "آماده‌ای همکاری کنی؟"
        }
        binding.dashboardWallet.isVisible = isApproved
        binding.dashboardPerf.isVisible = isApproved
        val reqCount = obj.optInt("home_requests_pipeline_count")
        if (isApproved) {
            binding.dashboardWalletAmount.text = PartnerFormat.toman(obj.optLong("withdrawable_balance")) + " تومان"
            val hasGrowth = obj.has("home_month_growth_pct") && !obj.isNull("home_month_growth_pct")
            binding.dashboardWalletGrowth.isVisible = hasGrowth
            if (hasGrowth) binding.dashboardWalletGrowth.text = monthGrowthLabel(obj.optInt("home_month_growth_pct"))
            binding.dashboardPerfDeals.text = PartnerFormat.toman(obj.optInt("home_deals_done"))
            binding.dashboardPerfVisits.text = PartnerFormat.toman(obj.optInt("home_visits_done"))
            binding.dashboardPerfCustomers.text = PartnerFormat.toman(obj.optInt("home_active_customers"))
            binding.dashboardShortcutFollowTitle.text = "پیگیری مشتری‌ها"
            binding.dashboardFollowBadge.isVisible = reqCount > 0
            if (reqCount > 0) binding.dashboardFollowBadge.text = PartnerFormat.toman(reqCount)
            binding.dashboardShortcutFollow.setOnClickListener {
                (activity as? MainActivity)?.openPath("/express/partner/requests")
            }
        } else {
            binding.dashboardShortcutFollowTitle.text = "سطوح همکاری"
            binding.dashboardFollowBadge.isVisible = false
            binding.dashboardShortcutFollow.setOnClickListener {
                (activity as? MainActivity)?.openPath("/express/partner/levels")
            }
        }

        val adsCount = obj.optInt("divar_pro_ads_count").let { if (it > 0) it else ads.length() }
        bindPrompts(obj, adsCount, reqCount)
        bindSuggest(obj)
        binding.dashboardTiles.isVisible = false
    }

    private fun bindSuggest(obj: JSONObject) {
        val files = HomeSuggest.files(obj)
        val show = files.length() > 0
        binding.dashboardSuggestScroll.isVisible = show
        if (!show) {
            suggestKey = ""
            binding.dashboardSuggest.removeAllViews()
            return
        }
        val key = files.length().toString() + ":" + files.optJSONObject(0)?.optString("token").orEmpty()
        if (key == suggestKey && binding.dashboardSuggest.childCount > 0) return
        suggestKey = key
        binding.dashboardSuggest.removeAllViews()
        val limit = minOf(files.length(), HomeSuggest.LIMIT)
        for (i in 0 until limit) {
            val ad = files.optJSONObject(i) ?: continue
            val card = ItemHomeSuggestBinding.inflate(layoutInflater, binding.dashboardSuggest, true)
            card.suggestTitle.text = HomeSuggest.title(ad)
            val sub = HomeSuggest.subtitle(ad)
            card.suggestSub.isVisible = sub.isNotBlank()
            card.suggestSub.text = sub
            PartnerImages.roundClip(card.suggestImage, 10f)
            PartnerImages.bind(scope, card.suggestImage, HomeSuggest.imageUrl(ad))
            card.root.setOnClickListener { openSuggest(ad) }
        }
        if (files.length() > limit) {
            val more = android.widget.TextView(requireContext()).apply {
                text = "همه"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.vinor_accent_green))
                textSize = 14f
                setBackgroundResource(R.drawable.bg_flat_outline_card)
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                val density = resources.displayMetrics.density
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (72 * density).toInt(),
                    (84 * density).toInt(),
                )
                setOnClickListener { (activity as? MainActivity)?.openSearch() }
            }
            binding.dashboardSuggest.addView(more)
        }
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
        var hero: JSONObject? = null
        val tiles = mutableListOf<JSONObject>()
        for (i in 0 until prompts.length()) {
            val p = prompts.optJSONObject(i) ?: continue
            if (p.optString("ui_action") == "open_divar_ads_all") continue
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
        val approved = obj.optBoolean("is_approved")
        val pending = obj.optBoolean("has_pending_app")
        val loggedIn = PartnerApi.hasSession() ||
            obj.optBoolean("is_logged_in") ||
            obj.optString("me_phone").isNotBlank()
        if (approved) {
            out.put(
                JSONObject()
                    .put("label", "مشتری دارم")
                    .put("subtitle", "برای مشتری فایل مناسب پیدا کن")
                    .put("variant", "hero")
                    .put("icon", "user_plus")
                    .put("ui_action", "open_customer_modal"),
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
                .put("label", "سطوح همکاری")
                .put("subtitle", "شرایط و پورسانت")
                .put("variant", "tile")
                .put("icon", "users")
                .put("href", "/express/partner/levels"),
        )
        return out
    }

    private fun bindHeroPrompt(prompt: JSONObject?, reqCount: Int = 0) {
        val label = prompt?.optString("label").orEmpty().ifBlank { "می‌خواهم بازاریاب وینور شوم" }
        val action = prompt?.optString("ui_action").orEmpty()
        val followUps = if (action == "open_customer_modal") reqCount else 0
        val sub = if (followUps > 0) {
            "${PartnerFormat.toman(followUps)} مشتری نیاز به پیگیری"
        } else {
            prompt?.optString("subtitle").orEmpty().ifBlank { "شروع همکاری فروش و دریافت پورسانت" }
        }
        binding.dashboardHeroKicker.isVisible = action == "open_customer_modal"
        binding.dashboardHeroStart.text = if (action == "open_customer_modal") "شروع کنید" else "ادامه"
        binding.dashboardHeroTitle.text = label
        binding.dashboardHeroSub.text = sub
        binding.dashboardHeroCount.isVisible = false
        binding.dashboardHeroIcon.setImageResource(heroIcon(prompt?.optString("icon")))
        val href = prompt?.optString("href").orEmpty()
        binding.dashboardHero.setOnClickListener {
            when {
                action == "open_customer_modal" -> openCustomer()
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
                if (!wantChatLayer || !chatLayerOpen || currentAccountKey() != account) return@launch
                val http = resp.optInt("http_status")
                if (http == 401 || http == 403) {
                    releaseChatAccount()
                    (activity as? MainActivity)?.requireChatLogin()
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
                        releaseChatAccount()
                        (activity as? MainActivity)?.requireChatLogin()
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
