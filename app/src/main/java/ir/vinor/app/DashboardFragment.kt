package ir.vinor.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import ir.vinor.app.databinding.FragmentDashboardBinding
import ir.vinor.app.databinding.ItemChatBubbleBinding
import ir.vinor.app.databinding.ItemLandCardBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** خانه نیتیو — همان چیدمان وب: چت GPT + کارت دیوار. */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var threadId: String? = null
    private val landsByCode = linkedMapOf<String, JSONObject>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.dashboardLoginChip.setOnClickListener { (activity as? MainActivity)?.openLogin() }
        binding.dashboardMenu.setOnClickListener { (activity as? MainActivity)?.openProfile() }
        binding.dashboardBrand.setOnClickListener { binding.dashboardScroll.fullScroll(View.FOCUS_UP) }
        binding.dashboardChatSend.setOnClickListener { sendChat() }
        binding.dashboardChipCustomer.setOnClickListener { fillAndSend("مشتری دارم، مشخصات را می‌فرستم.") }
        binding.dashboardChipFile.setOnClickListener { fillAndSend("فایل مناسب برای بازاریابی می‌خواهم.") }
        binding.dashboardChatInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                syncSendEnabled()
            }
        })
        syncSendEnabled()
        loadAll()
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun syncSendEnabled() {
        val on = !binding.dashboardChatInput.text.isNullOrBlank()
        binding.dashboardChatSend.isEnabled = on
        binding.dashboardChatSend.alpha = if (on) 1f else 0.35f
    }

    private fun loadAll() {
        val loggedIn = PartnerApi.hasSession()
        binding.dashboardLoginChip.isVisible = !loggedIn
        binding.dashboardProgress.isVisible = true
        scope.launch {
            try {
                val dash = withContext(Dispatchers.IO) {
                    PartnerApi.get("/express/partner/dashboard/data", requireContext())
                }
                if (_binding == null) return@launch
                cacheLands(dash)
                val hist = withContext(Dispatchers.IO) {
                    PartnerApi.get("/express/partner/ai/history", requireContext())
                }
                if (_binding == null) return@launch
                bindChat(hist)
            } catch (e: Exception) {
                if (_binding != null) {
                    showEmpty(true, e.message ?: "خطا در بارگذاری")
                    Toast.makeText(requireContext(), e.message ?: "خطا در بارگذاری", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (_binding != null) binding.dashboardProgress.isVisible = false
            }
        }
    }

    private fun cacheLands(obj: JSONObject) {
        landsByCode.clear()
        val assigned = obj.optJSONArray("assigned_lands") ?: JSONArray()
        val publicLands = obj.optJSONArray("public_lands") ?: JSONArray()
        val lands = if (assigned.length() > 0) assigned else publicLands
        for (i in 0 until lands.length()) {
            val land = lands.optJSONObject(i) ?: continue
            val code = land.optString("code")
            if (code.isNotBlank()) landsByCode[code] = land
        }
    }

    private fun bindChat(obj: JSONObject) {
        threadId = obj.optString("thread_id").takeIf { it.isNotBlank() }
        val messages = obj.optJSONArray("messages") ?: JSONArray()
        binding.dashboardChatList.removeAllViews()
        val hasMsgs = messages.length() > 0
        showEmpty(!hasMsgs, null)
        binding.dashboardChips.isVisible = !hasMsgs
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            addBubble(msg.optString("role"), msg.optString("content"), msg.optJSONArray("land_codes"))
        }
        binding.dashboardScroll.post {
            binding.dashboardScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showEmpty(show: Boolean, override: String?) {
        binding.dashboardEmpty.isVisible = show
        if (!override.isNullOrBlank()) binding.dashboardEmptyText.text = override
        else binding.dashboardEmptyText.text =
            "مشتری داری مشخصاتش را بفرست — یا بگو چه فایلی برای بازاریابی می‌خواهی تا معرفی کنم"
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
        val status = land.optString("assignment_status").lowercase()
        val comm = land.optInt("commission_amount")
        val city = land.optString("city").ifBlank { land.optString("location") }
        when {
            status == "sold" -> {
                card.landCardCommission.isVisible = true
                card.landCardCommission.setTextColor(0xFF4ADE80.toInt())
                card.landCardCommission.text = "فروخته شد"
            }
            status == "in_transaction" || land.optBoolean("_in_transaction") -> {
                card.landCardCommission.isVisible = true
                card.landCardCommission.setTextColor(0xFFFB923C.toInt())
                card.landCardCommission.text = "در حال معامله"
            }
            land.optBoolean("is_expired") -> {
                card.landCardCommission.isVisible = true
                card.landCardCommission.setTextColor(0xFFF87171.toInt())
                card.landCardCommission.text = "منقضی"
            }
            comm > 0 -> {
                card.landCardCommission.isVisible = true
                card.landCardCommission.setTextColor(0xFF4ADE80.toInt())
                card.landCardCommission.text = "پورسانت ${PartnerFormat.toman(comm)} تومان"
            }
            else -> card.landCardCommission.isVisible = false
        }
        card.landCardMeta.text = if (city.isNotBlank()) "در $city" else ""
        PartnerImages.roundClip(card.landCardImage)
        PartnerImages.bind(scope, card.landCardImage, PartnerImages.coverUrl(land), card.landCardImagePlaceholder)
        card.root.setOnClickListener { (activity as? MainActivity)?.openLand(code) }
        return card
    }

    private fun fillAndSend(text: String) {
        binding.dashboardChatInput.setText(text)
        sendChat()
    }

    private fun sendChat() {
        val text = binding.dashboardChatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.dashboardChatInput.setText("")
        syncSendEnabled()
        showEmpty(false, null)
        binding.dashboardChips.isVisible = false
        addBubble("user", text, null)
        binding.dashboardChatSend.isEnabled = false
        binding.dashboardChatSend.alpha = 0.35f
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
                    (activity as? MainActivity)?.openLogin()
                    return@launch
                }
                if (resp.optBoolean("ok") || resp.optString("reply").isNotBlank()) {
                    threadId = resp.optString("thread_id").takeIf { it.isNotBlank() } ?: threadId
                    addBubble("assistant", resp.optString("reply"), resp.optJSONArray("land_codes"))
                    binding.dashboardScroll.post { binding.dashboardScroll.fullScroll(View.FOCUS_DOWN) }
                } else {
                    val err = resp.optString("error").ifBlank { "ارسال ناموفق بود." }
                    if (err.contains("ورود") || err.contains("لاگین")) {
                        (activity as? MainActivity)?.openLogin()
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
