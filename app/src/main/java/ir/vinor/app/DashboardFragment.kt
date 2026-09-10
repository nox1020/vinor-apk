package ir.vinor.app

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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

/** خانه نیتیو: چت بازاریاب + کارت فایل‌ها. */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var threadId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.dashboardLoginChip.setOnClickListener { (activity as? MainActivity)?.openLogin() }
        binding.dashboardChatSend.setOnClickListener { sendChat() }
        loadAll()
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun loadAll() {
        val loggedIn = PartnerApi.hasSession()
        binding.dashboardLoginChip.isVisible = !loggedIn
        binding.dashboardComposer.isVisible = loggedIn
        binding.dashboardProgress.isVisible = true
        scope.launch {
            try {
                val dash = withContext(Dispatchers.IO) {
                    PartnerApi.get("/express/partner/dashboard/data", requireContext())
                }
                if (_binding == null) return@launch
                bindDashboard(dash, loggedIn)
                if (loggedIn) {
                    val hist = withContext(Dispatchers.IO) {
                        PartnerApi.get("/express/partner/ai/history", requireContext())
                    }
                    if (_binding == null) return@launch
                    bindChat(hist)
                } else {
                    binding.dashboardChatList.removeAllViews()
                    binding.dashboardEmpty.isVisible = true
                    binding.dashboardEmptyText.text = "برای گفتگو با وینور وارد شوید."
                }
            } catch (e: Exception) {
                if (_binding != null) {
                    Toast.makeText(requireContext(), e.message ?: "خطا در بارگذاری", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (_binding != null) binding.dashboardProgress.isVisible = false
            }
        }
    }

    private fun bindDashboard(obj: JSONObject, loggedIn: Boolean) {
        val assigned = obj.optJSONArray("assigned_lands") ?: JSONArray()
        val publicLands = obj.optJSONArray("public_lands") ?: JSONArray()
        val lands = if (assigned.length() > 0) assigned else publicLands
        val total = obj.optInt("total_commission")
        val pending = obj.optInt("pending_commission")
        val sold = obj.optInt("sold_count")
        binding.dashboardStats.isVisible = loggedIn
        binding.dashboardTotalCommission.text = "درآمد: ${PartnerFormat.toman(total)}"
        binding.dashboardPendingCommission.text = "در انتظار: ${PartnerFormat.toman(pending)}"
        binding.dashboardSoldCount.text = "فروش: ${PartnerFormat.digits(sold)}"
        binding.dashboardLandList.removeAllViews()
        binding.dashboardLandsTitle.isVisible = lands.length() > 0
        for (i in 0 until lands.length()) {
            val land = lands.optJSONObject(i) ?: continue
            val card = ItemLandCardBinding.inflate(layoutInflater, binding.dashboardLandList, false)
            val code = land.optString("code")
            card.landCardTitle.text = land.optString("title", "—")
            card.landCardPrice.text = PartnerFormat.toman(land.optInt("price_total")) + " تومان"
            val comm = land.optInt("commission_amount")
            card.landCardCommission.isVisible = comm > 0
            if (comm > 0) card.landCardCommission.text = "پورسانت ${PartnerFormat.toman(comm)}"
            val meta = listOf(
                land.optString("city"),
                land.optString("location"),
                land.optString("size"),
            ).filter { it.isNotBlank() }.joinToString(" · ")
            card.landCardMeta.text = meta
            card.landCardDate.text = land.optString("created_at")
            card.root.setOnClickListener { (activity as? MainActivity)?.openLand(code) }
            binding.dashboardLandList.addView(card.root)
        }
    }

    private fun bindChat(obj: JSONObject) {
        threadId = obj.optString("thread_id").takeIf { it.isNotBlank() }
        val messages = obj.optJSONArray("messages") ?: JSONArray()
        binding.dashboardChatList.removeAllViews()
        binding.dashboardEmpty.isVisible = messages.length() == 0
        if (messages.length() == 0) {
            binding.dashboardEmptyText.text = "با وینور حرف بزنید — فایل، بازدید، معامله."
        }
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            addBubble(msg.optString("role"), msg.optString("content"), msg.optJSONArray("land_codes"))
        }
        binding.dashboardScroll.post {
            binding.dashboardScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun addBubble(role: String, content: String, codes: JSONArray?) {
        if (content.isBlank()) return
        val bubble = ItemChatBubbleBinding.inflate(layoutInflater, binding.dashboardChatList, false)
        bubble.chatBubbleText.text = content
        val lp = bubble.chatBubbleText.layoutParams as FrameLayout.LayoutParams
        if (role == "user") {
            lp.gravity = Gravity.END
            bubble.chatBubbleText.setBackgroundResource(R.drawable.bg_button_primary)
        } else {
            lp.gravity = Gravity.START
        }
        bubble.chatBubbleText.layoutParams = lp
        val code = codes?.optString(0).orEmpty()
        if (code.isNotBlank()) {
            bubble.root.setOnClickListener { (activity as? MainActivity)?.openLand(code) }
        }
        binding.dashboardChatList.addView(bubble.root)
    }

    private fun sendChat() {
        if (!PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openLogin()
            return
        }
        val text = binding.dashboardChatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.dashboardChatInput.setText("")
        addBubble("user", text, null)
        binding.dashboardChatSend.isEnabled = false
        scope.launch {
            try {
                val body = JSONObject().put("message", text)
                threadId?.let { body.put("thread_id", it) }
                val resp = withContext(Dispatchers.IO) {
                    PartnerApi.postJson("/express/partner/ai/chat", body, requireContext())
                }
                if (_binding == null) return@launch
                if (resp.optBoolean("ok") || resp.optString("reply").isNotBlank()) {
                    threadId = resp.optString("thread_id").takeIf { it.isNotBlank() } ?: threadId
                    addBubble("assistant", resp.optString("reply"), resp.optJSONArray("land_codes"))
                    binding.dashboardScroll.post { binding.dashboardScroll.fullScroll(View.FOCUS_DOWN) }
                } else {
                    val err = resp.optString("error").ifBlank { "ارسال ناموفق بود." }
                    Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "خطای شبکه", Toast.LENGTH_SHORT).show()
            } finally {
                if (_binding != null) binding.dashboardChatSend.isEnabled = true
            }
        }
    }
}
