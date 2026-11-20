package ir.vinor.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.json.JSONObject

/**
 * قواعد صفحهٔ اصلی کیف پول v2 (هم‌ارز وب wallet_home.html) — بدون وابستگی به View تا تست شود.
 * هر مفهوم موجودی فقط یک بار: «موجودی قابل استفاده» روی کارت، «بلوکه برای پورسانت» و
 * «در انتظار برداشت» به‌صورت چیپ؛ «کل موجودی» نمایش داده نمی‌شود.
 */
object WalletHome {
    data class WithdrawButton(val visible: Boolean, val dimmed: Boolean)

    /** پاسخ /api/wallet قابل نقاشی است؟ (سرور قدیمی یا خطا → نه) */
    fun loaded(obj: JSONObject?): Boolean =
        obj != null && obj.optBoolean("ok") && obj.has("min_amount") && obj.optJSONObject("wallet") != null

    /** چیپ‌های کارت: بلوکه همیشه، «در انتظار برداشت» فقط وقتی مبلغی هست. */
    fun chips(h: WalletCharge.Header): List<WalletCharge.Line> =
        h.lines.filter { it.key == "blocked" || (it.key == "pending_withdrawal" && h.pendingWithdrawal > 0L) }

    fun withdrawButton(o: WalletCharge.Options, hasHandler: Boolean): WithdrawButton {
        val visible = hasHandler && (!o.withdraw.known || o.withdraw.enabled)
        return WithdrawButton(visible, dimmed = o.withdraw.known && !o.withdraw.canWithdraw)
    }

    /** همهٔ برچسب‌های موجودی که روی صفحه می‌آیند (برای تست «هر برچسب یک بار»). */
    fun balanceLabels(o: WalletCharge.Options): List<String> =
        listOf(o.text("available_label")) + chips(o.header).map { it.label }
}

/**
 * سازندهٔ نمای کیف پول v2 برای صفحهٔ اصلی (تب کیف پول) و دیالوگ شارژ: کارت گرادیانی، چیپ‌ها،
 * درخواست‌های برداشت و تایم‌لاین روزبه‌روز؛ و نوار دکمه‌های چسبان «افزایش اعتبار» + «برداشت».
 */
class WalletHomeView(private val context: Context) {
    private val textColor = ContextCompat.getColor(context, R.color.vinor_text)
    private val muted = ContextCompat.getColor(context, R.color.vinor_text_muted)
    private val amber = 0xFFFBBF24.toInt()
    private val regular: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.vazirmatn) }.getOrNull()

    fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun label(text: String, size: Float, color: Int = textColor, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (bold) Typeface.create(regular, Typeface.BOLD) else regular
            textDirection = View.TEXT_DIRECTION_RTL
        }

    fun rounded(fill: Int, stroke: Int = 0, radius: Int = 12): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun matchWrap(top: Int = 0, side: Int = 16) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(side); marginEnd = dp(side); topMargin = dp(top)
        }

    /** نوار دو دکمه‌ای چسبان؛ دکمهٔ اول «افزایش اعتبار»، دوم «برداشت». */
    fun actionBar(onTopup: () -> Unit, onWithdraw: () -> Unit): LinearLayout {
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tag = "wallet_home_actions" }
        bar.addView(Button(context).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(regular, Typeface.BOLD)
            setTextColor(0xFF052E1F.toInt())
            background = rounded(0xFF34D399.toInt(), radius = 16)
            stateListAnimator = null
            tag = "wallet_home_topup"
            text = "+  " + WalletCharge.DEFAULT_TEXTS["topup_button"].orEmpty()
            setOnClickListener { onTopup() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(Button(context).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(regular, Typeface.BOLD)
            setTextColor(textColor)
            background = rounded(0x0FFFFFFF, 0x24FFFFFF, 16)
            stateListAnimator = null
            tag = "wallet_home_withdraw"
            text = "↑  " + WalletCharge.DEFAULT_TEXTS["withdraw_button"].orEmpty()
            setOnClickListener { onWithdraw() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(10) })
        return bar
    }

    fun bindActions(bar: LinearLayout, o: WalletCharge.Options, hasWithdraw: Boolean) {
        (bar.getChildAt(0) as? Button)?.text = "+  " + o.text("topup_button")
        val wd = bar.getChildAt(1) as? Button ?: return
        val state = WalletHome.withdrawButton(o, hasWithdraw)
        wd.visibility = if (state.visible) View.VISIBLE else View.GONE
        wd.text = "↑  " + o.text("withdraw_button")
        wd.alpha = if (state.dimmed) 0.55f else 1f
    }

    /** محتوای صفحه را در [body] می‌ریزد (body قبلاً خالی شده است). */
    fun render(body: LinearLayout, o: WalletCharge.Options) {
        val h = o.header
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TR_BL,
                intArrayOf(0xFF0F766E.toInt(), 0xFF134E4A.toInt(), 0xFF312E81.toInt())).apply {
                cornerRadius = dp(28).toFloat()
            }
            tag = "wallet_balance_card"
        }
        card.addView(label(o.text("available_label"), 12f, 0xB8FFFFFF.toInt()))
        card.addView(label(WalletCharge.toman(h.available), 32f, Color.WHITE, bold = true).apply {
            setPadding(0, dp(4), 0, 0); tag = "wallet_available"
        })
        card.addView(label(o.text("available_hint"), 12f, 0xA6FFFFFF.toInt()).apply { setPadding(0, dp(2), 0, 0) })
        val chips = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        WalletHome.chips(h).forEach { line ->
            val glyph = if (line.key == "blocked") "🔒" else "⏳"
            chips.addView(label("$glyph  ${line.label}  ${line.value}", 12f, Color.WHITE, bold = true).apply {
                background = rounded(0x1FFFFFFF, 0x29FFFFFF, 99)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                tag = "wallet_line_${line.key}"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        card.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        body.addView(card, matchWrap(0, 14))
        if (h.blocked > 0L) {
            body.addView(label(o.text("blocked_hint"), 11.5f, muted).apply { setPadding(dp(22), dp(8), dp(22), 0) })
        }
        val reqs = o.withdraw.requests
        if (reqs.isNotEmpty()) {
            body.addView(label(o.text("requests_title"), 14f, bold = true).apply { setPadding(dp(18), dp(22), dp(18), dp(4)) })
            reqs.forEach { r -> addRequest(body, r) }
        }
        body.addView(label(o.text("transactions_title"), 14f, bold = true).apply { setPadding(dp(18), dp(22), dp(18), dp(4)) })
        if (o.groups.isEmpty()) {
            body.addView(label(o.text("transactions_empty"), 13f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(32), dp(16), 0) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        o.groups.forEach { g ->
            body.addView(label(g.label, 11f, muted, bold = true).apply {
                background = rounded(0xFF121A2E.toInt(), 0x24FFFFFF, 99)
                setPadding(dp(10), dp(2), dp(10), dp(2))
                tag = "wallet_day"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(50); topMargin = dp(12); bottomMargin = dp(4)
            })
            g.items.forEach { addTxn(body, it) }
        }
    }

    private fun pillColors(tone: String): Pair<Int, Int> = when (tone) {
        "blue" -> 0x2460A5FA to 0xFF93C5FD.toInt()
        else -> chipColors(tone)
    }

    private fun addRequest(body: LinearLayout, r: WalletWithdrawWizard.Request) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF121A2E.toInt(), 0x24FFFFFF, 18)
            tag = "wallet_request_${r.status}"
        }
        val mid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(label(r.amountLabel, 14f, bold = true))
        mid.addView(label(listOf(r.dateLabel, r.tracking).filter(String::isNotBlank).joinToString(" · "), 11.5f, muted).apply { maxLines = 1 })
        if (r.adminNote.isNotBlank()) mid.addView(label(r.adminNote, 11.5f, 0xFFFDA4AF.toInt()))
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val (fill, fg) = pillColors(r.tone)
        row.addView(label(r.statusLabel, 11f, fg, bold = true).apply {
            background = rounded(fill, radius = 99)
            setPadding(dp(9), dp(2), dp(9), dp(2))
        })
        body.addView(row, matchWrap(8, 14))
    }

    private fun toneColor(tone: String): Int = when (tone) {
        "green" -> 0xFF34D399.toInt()
        "red" -> 0xFFFB7185.toInt()
        "amber" -> amber
        else -> muted
    }

    private fun chipColors(tone: String): Pair<Int, Int> = when (tone) {
        "green" -> 0x2610B981 to 0xFF6EE7B7.toInt()
        "amber" -> 0x26FBBF24 to 0xFFFCD34D.toInt()
        "red" -> 0x26F43F5E to 0xFFFDA4AF.toInt()
        else -> 0x14FFFFFF to 0xFFD4D4D8.toInt()
    }

    private fun iconFor(kind: String): Pair<String, Int> = when (kind) {
        "topup" -> "↓" to 0xFF34D399.toInt()
        "hold" -> "🔒" to amber
        "release" -> "🔓" to amber
        "commission_paid" -> "✓" to 0xFFFB7185.toInt()
        "withdrawal" -> "↑" to 0xFFFB7185.toInt()
        else -> "•" to 0xFF93C5FD.toInt()
    }

    private fun addTxn(body: LinearLayout, it: WalletCharge.Item) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minimumHeight = dp(64)
            tag = "wallet_txn_${it.kind}"
        }
        val (glyph, color) = iconFor(it.kind)
        val icon = FrameLayout(context).apply { background = rounded(0xFF121A2E.toInt(), 0x24FFFFFF, 14) }
        icon.addView(label(glyph, 16f, color, bold = true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val mid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(label(it.title, 14f).apply { maxLines = 1 })
        val sub = listOf(it.time, it.subtitle).filter { s -> s.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) mid.addView(label(sub, 12f, muted).apply { maxLines = 1 })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val end = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        end.addView(label(it.amountLabel, 14f, toneColor(it.tone), bold = true).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            if (it.tone == "muted") paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        })
        if (it.chipLabel.isNotBlank()) {
            val (fill, fg) = chipColors(it.chipTone)
            end.addView(label(it.chipLabel, 11f, fg, bold = true).apply {
                background = rounded(fill, radius = 99)
                setPadding(dp(8), dp(1), dp(8), dp(1))
            })
        }
        row.addView(end)
        val wrap = FrameLayout(context)
        wrap.addView(View(context).apply { setBackgroundColor(0x24FFFFFF) },
            FrameLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply { marginStart = dp(16 + 19) })
        wrap.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(wrap)
    }
}
