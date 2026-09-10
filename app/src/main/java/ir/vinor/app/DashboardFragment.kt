package ir.vinor.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.Toast
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** خانه نیتیو — همان چیدمان وب خالی: آگهی، خبر، کارت همکاری، کامپوزر. */
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
    private var isApproved = false
    private val newsHandler = Handler(Looper.getMainLooper())
    private val newsTick = object : Runnable {
        override fun run() {
            val b = _binding ?: return
            if (newsItems.isEmpty() || !b.dashboardEmpty.isVisible) return
            newsIndex = (newsIndex + 1) % newsItems.size
            b.dashboardNewsText.text = newsItems[newsIndex]
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
        binding.dashboardBrand.setOnClickListener { (activity as? MainActivity)?.closeDrawer() }
        binding.dashboardChatSend.setOnClickListener { sendChat() }
        binding.dashboardHero.setOnClickListener { onHero() }
        binding.dashboardTileBuy.setOnClickListener { openAdsAll() }
        binding.dashboardTileLevels.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${PartnerApi.BASE}/express/partner/levels")))
        }
        binding.dashboardAdsAllBackdrop.setOnClickListener { closeAdsAll() }
        binding.dashboardAdsAllClose.setOnClickListener { closeAdsAll() }
        binding.dashboardNewsDot.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.news_dot_pulse))
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
        binding.dashboardAdsScroll.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        syncSendEnabled()
        loadAll()
    }

    override fun onDestroyView() {
        newsHandler.removeCallbacks(newsTick)
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    fun reload() {
        if (_binding == null) return
        loadAll()
    }

    fun setHeaderVisible(visible: Boolean) {
        val b = _binding ?: return
        b.dashboardHeader.animate().cancel()
        b.dashboardHeader.animate().alpha(if (visible) 1f else 0f).setDuration(160).start()
        b.dashboardHeader.isClickable = visible
    }

    fun closeAdsAll(): Boolean {
        val b = _binding ?: return false
        if (!b.dashboardAdsAll.isVisible) return false
        b.dashboardAdsAll.isVisible = false
        return true
    }

    private fun onHero() {
        if (PartnerApi.hasSession()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${PartnerApi.BASE}/express/partner/apply")))
        } else {
            (activity as? MainActivity)?.openDrawer()
        }
    }

    private fun syncSendEnabled() {
        val on = !binding.dashboardChatInput.text.isNullOrBlank()
        binding.dashboardChatSend.isEnabled = on
        binding.dashboardChatSend.setBackgroundResource(
            if (on) R.drawable.bg_send_circle else R.drawable.bg_send_idle
        )
        ImageViewCompat.setImageTintList(
            binding.dashboardChatSend,
            android.content.res.ColorStateList.valueOf(Color.parseColor(if (on) "#0D0D0D" else "#111111")),
        )
    }

    private fun loadAll() {
        binding.dashboardProgress.isVisible = true
        scope.launch {
            try {
                val home = withContext(Dispatchers.IO) {
                    PartnerApi.get("/express/partner/home/data", requireContext())
                }
                if (_binding == null) return@launch
                bindHome(home)
            } catch (e: Exception) {
                if (_binding != null) {
                    bindHome(JSONObject())
                    Toast.makeText(requireContext(), e.message ?: "خطا در بارگذاری", Toast.LENGTH_SHORT).show()
                }
            }
            try {
                if (PartnerApi.hasSession()) {
                    val hist = withContext(Dispatchers.IO) {
                        PartnerApi.get("/express/partner/ai/history", requireContext())
                    }
                    if (_binding == null) return@launch
                    val status = hist.optInt("http_status")
                    if (status != 401 && status != 403) bindChat(hist)
                } else if (_binding != null) {
                    bindChat(JSONObject())
                }
            } catch (_: Exception) {
                if (_binding != null) bindChat(JSONObject())
            } finally {
                if (_binding != null) binding.dashboardProgress.isVisible = false
            }
        }
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
            newsItems.add("باغ گیلان فروخته شد؛ پورسانت قابل برداشت شد")
        }
        binding.dashboardNewsText.text = newsItems.first()
        newsHandler.removeCallbacks(newsTick)
        if (newsItems.size > 1) newsHandler.postDelayed(newsTick, 4000)

        val ads = obj.optJSONArray("divar_pro_ads") ?: JSONArray()
        allAds = ads
        binding.dashboardAds.removeAllViews()
        val showCarousel = ads.length() > 0
        binding.dashboardAdsScroll.isVisible = showCarousel
        for (i in 0 until ads.length()) {
            val ad = ads.optJSONObject(i) ?: continue
            binding.dashboardAds.addView(inflateAdCard(ad, binding.dashboardAds, loadImage = i < 10).root)
        }

        val emptyPad = if (showCarousel) 6 else 56
        binding.dashboardEmpty.setPadding(
            binding.dashboardEmpty.paddingLeft,
            (emptyPad * resources.displayMetrics.density).toInt(),
            binding.dashboardEmpty.paddingRight,
            binding.dashboardEmpty.paddingBottom,
        )

        val prompts = obj.optJSONArray("ai_quick_prompts") ?: JSONArray()
        for (i in 0 until prompts.length()) {
            val p = prompts.optJSONObject(i) ?: continue
            when (p.optString("variant")) {
                "hero" -> {
                    binding.dashboardHeroTitle.text = p.optString("label").ifBlank { "می‌خواهم بازاریاب وینور شوم" }
                    binding.dashboardHeroSub.text = p.optString("subtitle")
                    val href = p.optString("href")
                    binding.dashboardHero.setOnClickListener {
                        if (!PartnerApi.hasSession() || href.contains("login") || href.contains("menu=1")) {
                            (activity as? MainActivity)?.openDrawer()
                        } else if (href.isNotBlank()) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PartnerImages.resolve(href))))
                        } else {
                            onHero()
                        }
                    }
                }
                "tile" -> {
                    if (p.optString("ui_action") == "open_divar_ads_all") {
                        binding.dashboardTileBuy.setOnClickListener { openAdsAll() }
                    }
                }
            }
        }

        val adsCount = obj.optInt("divar_pro_ads_count").let { if (it > 0) it else ads.length() }
        binding.dashboardTileBuyCount.isVisible = adsCount > 0
        if (adsCount > 0) binding.dashboardTileBuyCount.text = PartnerFormat.toman(adsCount)
    }

    private fun inflateAdCard(ad: JSONObject, parent: ViewGroup, loadImage: Boolean = true): ItemHomeAdBinding {
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
        if (loadImage) {
            PartnerImages.bind(scope, card.homeAdImage, ad.optString("image_url"))
        } else {
            card.homeAdImage.post {
                if (_binding != null) PartnerImages.bind(scope, card.homeAdImage, ad.optString("image_url"))
            }
        }
        card.root.setOnClickListener { openAd(ad) }
        return card
    }

    private fun openAd(ad: JSONObject) {
        val token = ad.optString("token")
        val source = ad.optString("source")
        val url = ad.optString("url")
        when {
            source == "vinor" && token.isNotBlank() -> (activity as? MainActivity)?.openLand(token)
            source == "divar" && token.isNotBlank() ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://divar.ir/v/$token")))
            url.isNotBlank() -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PartnerImages.resolve(url))))
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
            PartnerImages.bind(scope, row.adsAllThumb, ad.optString("image_url"))
            row.root.setOnClickListener {
                closeAdsAll()
                openAd(ad)
            }
            binding.dashboardAdsAllList.addView(row.root)
        }
        binding.dashboardAdsAll.isVisible = true
    }

    private fun bindChat(obj: JSONObject) {
        threadId = obj.optString("thread_id").takeIf { it.isNotBlank() }
        val messages = obj.optJSONArray("messages") ?: JSONArray()
        scope.launch {
            prefetchLandCodes(messages)
            if (_binding == null) return@launch
            renderMessages(messages)
        }
    }

    private suspend fun prefetchLandCodes(messages: JSONArray) {
        val codes = linkedSetOf<String>()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val arr = msg.optJSONArray("land_codes") ?: continue
            for (j in 0 until arr.length()) {
                val code = arr.optString(j)
                if (code.isNotBlank()) codes.add(code)
            }
        }
        codes.forEach { prefetchLand(it) }
    }

    private suspend fun prefetchLand(code: String) {
        if (code.isBlank() || landsByCode.containsKey(code)) return
        try {
            val obj = withContext(Dispatchers.IO) {
                PartnerApi.get("/api/express/$code", requireContext())
            }
            val land = obj.optJSONObject("land") ?: return
            if (!land.has("code")) land.put("code", code)
            landsByCode[code] = land
        } catch (_: Exception) {
        }
    }

    private fun renderMessages(messages: JSONArray) {
        binding.dashboardChatList.removeAllViews()
        var hasUser = false
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role") == "user") hasUser = true
            addBubble(msg.optString("role"), msg.optString("content"), msg.optJSONArray("land_codes"))
        }
        val empty = !hasUser
        binding.dashboardEmpty.isVisible = empty
        binding.dashboardAdsScroll.isVisible = empty && binding.dashboardAds.childCount > 0
        if (!empty) newsHandler.removeCallbacks(newsTick)
        if (hasUser) {
            binding.dashboardScroll.post { binding.dashboardScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun addBubble(role: String, content: String, codes: JSONArray?) {
        if (content.isBlank() && (codes == null || codes.length() == 0)) return
        val bubble = ItemChatBubbleBinding.inflate(layoutInflater, binding.dashboardChatList, false)
        val isUser = role == "user"
        bubble.chatBubbleAvatar.isVisible = !isUser
        bubble.chatBubbleRow.gravity = if (isUser) Gravity.END else Gravity.START
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

    private fun sendChat() {
        val text = binding.dashboardChatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.dashboardChatInput.setText("")
        syncSendEnabled()
        binding.dashboardEmpty.isVisible = false
        binding.dashboardAdsScroll.isVisible = false
        addBubble("user", text, null)
        scope.launch {
            try {
                val body = JSONObject().put("message", text)
                threadId?.let { body.put("thread_id", it) }
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/ai/chat", body, requireContext())
                }
                if (_binding == null) return@launch
                val http = resp.optInt("http_status")
                if (http == 401 || http == 403) {
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
