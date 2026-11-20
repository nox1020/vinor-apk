package ir.vinor.app

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * تب کیف پول (ورودی اصلی از منو، خانه، پروفایل و لینک‌های /commissions) — طرح v2 هم‌ارز وب:
 * کارت گرادیانی «موجودی قابل استفاده»، چیپ «بلوکه برای پورسانت» و «در انتظار برداشت»،
 * درخواست‌های برداشت، تایم‌لاین روزبه‌روز و دکمه‌های چسبان «افزایش اعتبار» + «برداشت».
 * داده از /express/partner/api/wallet؛ طرح قدیمی (کارت «کل موجودی»، لیست پورسانت و شیت برداشت) حذف شد.
 */
class CommissionsFragment : Fragment() {

    private var root: LinearLayout? = null
    private lateinit var homeView: WalletHomeView
    private lateinit var body: LinearLayout
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var actions: LinearLayout
    private var options: WalletCharge.Options = WalletCharge.parseOptions(null)
    private var csrf: String = ""
    private var loadGen = 0
    private var rendered = false
    private var skipNextResume = true
    private var walletDialog: WalletChargeDialog? = null
    private var withdrawDialog: WalletWithdrawDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        skipNextResume = true
        val ctx = requireContext()
        homeView = WalletHomeView(ctx)
        val dp = homeView::dp
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(PAGE_BG)
            tag = "wallet_v2_page"
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
        }
        head.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(android.graphics.Color.WHITE)
            background = null
            contentDescription = "بازگشت"
            tag = "wallet_back"
            setOnClickListener { goBack() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(homeView.label(WalletCharge.DEFAULT_TEXTS["wallet_title"].orEmpty().ifBlank { "کیف پول" }, 15f,
            android.graphics.Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; tag = "wallet_title" },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(View(ctx), LinearLayout.LayoutParams(dp(44), dp(44)))
        page.addView(head)
        swipe = SwipeRefreshLayout(ctx).apply {
            setColorSchemeColors(0xFF34D399.toInt())
            setProgressBackgroundColorSchemeColor(0xFF121A2E.toInt())
            setOnRefreshListener { loadData() }
        }
        val scroll = ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(24)) }
        scroll.addView(body)
        swipe.addView(scroll)
        page.addView(swipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(PAGE_BG)
        }
        actions = homeView.actionBar(onTopup = { openCharge() }, onWithdraw = { openWithdraw() })
        footer.addView(actions)
        page.addView(footer)
        root = page
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val owed = pendingShortfall
        val owedCtx = pendingChargeContext
        val returning = pendingTopupId
        if (returning > 0L) {
            pendingTopupId = 0L
            openCharge(topupId = returning)
        } else if (owed > 0L || owedCtx.isNotBlank()) {
            pendingShortfall = 0L
            pendingChargeContext = ""
            openCharge(owed, owedCtx)
        }
        val cached = remembered
        if (cached != null && WalletHome.loaded(cached)) {
            paint(WalletCharge.parseOptions(cached))
        } else {
            showLoading()
        }
        loadData()
    }

    override fun onResume() {
        super.onResume()
        walletDialog?.onHostResume()
        if (walletDialog == null && pendingTopupId > 0L && root != null) {
            val id = pendingTopupId
            pendingTopupId = 0L
            openCharge(topupId = id)
        }
        if (skipNextResume) {
            skipNextResume = false
            return
        }
        if (root != null) loadData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        walletDialog?.dismiss()
        walletDialog = null
        withdrawDialog?.dismiss()
        withdrawDialog = null
        root = null
    }

    private fun goBack() {
        if (!findNavController().navigateUp()) (activity as? MainActivity)?.openPartnerHome()
    }

    private fun showLoading() {
        body.removeAllViews()
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.addView(ProgressBar(requireContext()).apply { tag = "wallet_loading" },
            LinearLayout.LayoutParams(homeView.dp(36), homeView.dp(36)).apply { topMargin = homeView.dp(80) })
        actions.visibility = View.INVISIBLE
    }

    private fun showError(message: String) {
        val ctx = context ?: return
        body.removeAllViews()
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.addView(homeView.label(message, 14f).apply { gravity = Gravity.CENTER; setPadding(homeView.dp(24), homeView.dp(80), homeView.dp(24), homeView.dp(12)) })
        body.addView(Button(ctx).apply {
            text = VinorPageLoad.RETRY
            isAllCaps = false
            tag = "wallet_retry"
            setOnClickListener { showLoading(); loadData() }
        })
        actions.visibility = View.INVISIBLE
    }

    private fun paint(o: WalletCharge.Options) {
        if (root == null) return
        options = o
        o.csrf.takeIf { it.isNotBlank() }?.let { csrf = it }
        body.removeAllViews()
        body.gravity = Gravity.NO_GRAVITY
        homeView.render(body, o)
        homeView.bindActions(actions, o, hasWithdraw = true)
        actions.visibility = View.VISIBLE
        rendered = true
    }

    private fun loadData() {
        val ctx = context ?: return
        val gen = ++loadGen
        val stamp = sessionStamp
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val obj = withContext(Dispatchers.IO) {
                    runCatching { PartnerApi.get(WalletCharge.OPTIONS_PATH, ctx) }.getOrNull()
                }
                if (root == null || gen != loadGen || stamp != sessionStamp) return@launch
                if (obj != null && VinorPageLoad.needsLogin(obj)) {
                    clearRemembered()
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (!WalletHome.loaded(obj)) {
                    if (rendered) root?.let { VinorPageLoad.showOffline(it) { loadData() } }
                    else showError(VinorPageLoad.OFFLINE)
                    return@launch
                }
                paint(WalletCharge.parseOptions(obj))
                if (VinorPageLoad.canRemember(obj!!)) remembered = VinorPageLoad.rememberedCopy(obj)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "loadData error", e)
                if (root == null || gen != loadGen) return@launch
                if (!rendered) showError(VinorPageLoad.OFFLINE)
            } finally {
                if (root != null && gen == loadGen) swipe.isRefreshing = false
            }
        }
    }

    private fun openWithdraw() {
        if (!PartnerApi.hasSession()) {
            (activity as? MainActivity)?.openLogin()
            return
        }
        openWithdrawWizard()
    }

    /**
     * ویزارد برداشت به سبک دیوار (هم‌ارز وب). جای شیت قدیمی «مبلغ + انتخاب حساب» را گرفته؛
     * مسیرهای قدیمی bank-accounts و withdrawals/request سمت سرور دست‌نخورده می‌مانند.
     */
    private fun openWithdrawWizard() {
        if (withdrawDialog?.isShowing == true || root == null) return
        walletDialog?.dismiss()
        val flow = WalletWithdrawFlow(WalletWithdrawWizard.parseOptions(null))
        val dialog = WalletWithdrawDialog(
            requireContext(),
            flow,
            onSubmit = { body -> submitWithdraw(body) },
            onClosed = { submitted ->
                withdrawDialog = null
                if (submitted && root != null) loadData()
            },
        )
        withdrawDialog = dialog
        dialog.show()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { PartnerApi.get(WalletWithdrawWizard.PATH, requireContext()) }.getOrNull()
            }
            if (withdrawDialog !== dialog) return@launch
            val opts = WalletWithdrawWizard.parseOptions(res)
            if (!opts.loaded) {
                dialog.dismiss()
                Toast.makeText(requireContext(), "دریافت اطلاعات برداشت ناموفق بود. دوباره تلاش کنید.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            opts.csrf.takeIf { it.isNotBlank() }?.let { csrf = it }
            dialog.updateOptions(opts)
        }
    }

    private fun submitWithdraw(body: JSONObject) {
        val dialog = withdrawDialog ?: return
        // برداشت مالی است: در صف آفلاین نمی‌رود و بدون شبکه ارسال نمی‌شود.
        if (!VinorNetworkStatus.isOnline(requireContext())) {
            dialog.showOutcome(WalletWithdrawWizard.Outcome.Failed("offline", "برای ثبت برداشت اینترنت لازم است.", offline = true))
            return
        }
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                PartnerApi.postFinance(WalletWithdrawWizard.PATH, body, csrf, requireContext())
            }
            if (withdrawDialog !== dialog) return@launch
            WalletWithdraw.csrfOf(res).takeIf { it.isNotBlank() }?.let { csrf = it }
            dialog.showOutcome(WalletWithdrawWizard.parseOutcome(res, dialog.flow.options))
        }
    }

    /**
     * کیف پول / «افزایش اعتبار» تمام‌صفحه (هم‌ارز وب). درگاه در Custom Tab باز می‌شود و
     * بعد از برگشت، نتیجه از سرور خوانده می‌شود؛ اپ هیچ‌وقت خودش موجودی را زیاد نمی‌کند.
     */
    private fun openCharge(prefill: Long = 0L, ctx: String = "", startAtAmount: Boolean = true, topupId: Long = 0L) {
        if (walletDialog?.isShowing == true || root == null) return
        val base = WalletCharge.parseOptions(null, prefill, options.header.available, options.header.blocked)
        val flow = WalletChargeFlow(base, startAtAmount)
        val dialog = WalletChargeDialog(
            requireContext(),
            flow,
            onPay = { body -> startTopup(body) },
            onCheck = { id, recheck -> checkTopup(id, recheck) },
            onReturn = { target -> onChargeReturn(target) },
            onWithdraw = { openWithdraw() },
            onClosed = { charged ->
                walletDialog = null
                activeTopupId = 0L
                if (charged && root != null) loadData()
            },
        )
        walletDialog = dialog
        dialog.show()
        if (topupId > 0L) {
            flow.startedPayment(topupId)
            dialog.onHostResume()
        }
        val query = buildString {
            append(WalletCharge.OPTIONS_PATH)
            if (ctx.isNotBlank()) append("?ctx=").append(java.net.URLEncoder.encode(ctx, "UTF-8"))
            else if (prefill > 0L) append("?shortfall=").append(prefill)
        }
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { PartnerApi.get(query, requireContext()) }.getOrNull() }
            if (walletDialog !== dialog) return@launch
            val opts = WalletCharge.parseOptions(res, prefill, options.header.available, options.header.blocked)
            opts.csrf.takeIf { it.isNotBlank() }?.let { csrf = it }
            dialog.updateOptions(opts)
        }
    }

    private fun startTopup(body: JSONObject) {
        val dialog = walletDialog ?: return
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                PartnerApi.postFinance(WalletCharge.CHARGE_PATH, body, csrf, requireContext())
            }
            if (walletDialog !== dialog) return@launch
            val url = res.optString("pay_url")
            if (res.optBoolean("ok") && url.startsWith("https://")) {
                val id = res.optLong("txn_id", 0L)
                activeTopupId = id
                dialog.paymentStarted(id)
                if (!VinorExternalBrowser.open(requireContext(), url)) {
                    dialog.showError("مرورگری برای باز کردن درگاه پیدا نشد.")
                }
            } else {
                dialog.showError(res.optString("error").ifBlank { res.optString("message").ifBlank { "شروع پرداخت ممکن نشد." } })
            }
        }
    }

    private fun checkTopup(id: Long, recheck: Boolean) {
        val dialog = walletDialog ?: return
        if (id <= 0L) {
            // سرور قدیمی شناسه نمی‌دهد: فقط موجودی تازه را بخوان.
            dialog.dismiss()
            loadData()
            return
        }
        lifecycleScope.launch {
            val path = "/express/partner/api/wallet/topup/$id"
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    if (recheck) PartnerApi.postJson("$path/recheck", JSONObject(), requireContext())
                    else PartnerApi.get(path, requireContext())
                }.getOrNull()
            }
            if (walletDialog !== dialog) return@launch
            if (res == null || (!res.has("state") && res.optBoolean("offline"))) {
                dialog.showError("برای دیدن نتیجه اینترنت لازم است.")
                return@launch
            }
            dialog.showResult(WalletCharge.parseResult(res, dialog.flow.options.texts))
        }
    }

    private fun onChargeReturn(target: WalletCharge.Return) {
        if (WalletCharge.returnsToPreviousScreen(target)) {
            if (!findNavController().navigateUp()) (activity as? MainActivity)?.openPartnerHome()
        } else if (root != null) {
            loadData()
        }
    }

    companion object {
        private const val TAG = "CommissionsFragment"
        private const val PAGE_BG = 0xFF0B1020.toInt()
        private var remembered: JSONObject? = null
        private var sessionStamp = 0

        fun clearRemembered() {
            remembered = null
            sessionStamp++
        }

        var pendingShortfall: Long = 0L
        /** زمینهٔ امضاشدهٔ کمبود از پاسخ سرور (charge_context). */
        var pendingChargeContext: String = ""
        /** شارژی که کاربر از صفحهٔ نتیجهٔ مرورگر با لینک ‎?topup=‎ به آن برگشته است. */
        var pendingTopupId: Long = 0L
        var activeTopupId: Long = 0L

        fun handOffShortfall(gap: Long, response: JSONObject?) {
            pendingShortfall = gap.coerceAtLeast(0L)
            pendingChargeContext = response?.optString("charge_context").orEmpty()
        }

        /** از لینک بازگشت صفحهٔ نتیجه: /express/partner/commissions?topup=N */
        fun topupIdFromUrl(url: String?): Long {
            val q = url.orEmpty().substringAfter('?', "")
            return q.split('&').firstOrNull { it.startsWith("topup=") }
                ?.substringAfter('=')?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        }
    }
}
