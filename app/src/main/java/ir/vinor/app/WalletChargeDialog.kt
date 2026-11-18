package ir.vinor.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.json.JSONObject

/**
 * کیف پول و «افزایش اعتبار» تمام‌صفحه (سبک دیوار، هم‌ارز وب): صفحهٔ کیف پول با تراکنش‌های روزبه‌روز،
 * مرحلهٔ مبلغ با دکمه‌های پیشنهادی و نوشتن مبلغ به حروف، مرحلهٔ تأیید، انتظار درگاه و نتیجه.
 * منطق در [WalletChargeFlow] است؛ شبکه را صدازننده انجام می‌دهد.
 */
class WalletChargeDialog(
    context: Context,
    val flow: WalletChargeFlow,
    private val onPay: (JSONObject) -> Unit,
    private val onCheck: (txnId: Long, recheck: Boolean) -> Unit,
    private val onReturn: (WalletCharge.Return) -> Unit,
    private val onClosed: (charged: Boolean) -> Unit,
) : Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val green = ContextCompat.getColor(context, R.color.vinor_accent_green)
    private val bg = ContextCompat.getColor(context, R.color.vinor_bg)
    private val textColor = ContextCompat.getColor(context, R.color.vinor_text)
    private val muted = ContextCompat.getColor(context, R.color.vinor_text_muted)
    private val border = ContextCompat.getColor(context, R.color.vinor_border)
    private val errorColor = ContextCompat.getColor(context, R.color.vinor_error)
    private val amber = 0xFFFBBF24.toInt()
    private val regular: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.vazirmatn) }.getOrNull()

    private lateinit var title: TextView
    private lateinit var progressLabel: TextView
    private lateinit var progressBar: LinearLayout
    private lateinit var header: View
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var errorText: TextView
    private var busy = false
    private var charged = false

    init {
        setContentView(buildShell())
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        setCancelable(true)
        setOnDismissListener { onClosed(charged) }
        render()
    }

    @Deprecated("Dialog back handling")
    override fun onBackPressed() {
        if (busy) return
        if (!flow.back()) dismiss() else render()
    }

    // ---------------------------------------------------------------- host API

    fun updateOptions(options: WalletCharge.Options) {
        flow.applyOptions(options)
        if (!busy) render()
    }

    fun paymentStarted(txnId: Long) {
        busy = false
        flow.startedPayment(txnId)
        render()
    }

    fun showError(message: String) {
        busy = false
        errorText.text = message
        errorText.visibility = View.VISIBLE
        render()
    }

    fun showResult(result: WalletCharge.Result) {
        busy = false
        if (result.state == "success") charged = true
        flow.showResult(result)
        render()
    }

    /** وقتی کاربر از مرورگر برمی‌گردد. */
    fun onHostResume() {
        if (flow.step == WalletChargeFlow.Step.WAITING && flow.txnId > 0L && !busy) {
            busy = true
            render()
            onCheck(flow.txnId, false)
        }
    }

    // ---------------------------------------------------------------- views

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int = textColor, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (bold) Typeface.create(regular, Typeface.BOLD) else regular
            textDirection = View.TEXT_DIRECTION_RTL
        }

    private fun rounded(fill: Int, stroke: Int = 0, radius: Int = 12): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun matchWrap(top: Int = 0, side: Int = 16) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(side); marginEnd = dp(side); topMargin = dp(top)
        }

    private fun button(fill: Boolean): Button = Button(context).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create(regular, Typeface.BOLD)
        setTextColor(if (fill) Color.BLACK else textColor)
        background = if (fill) rounded(green) else rounded(Color.TRANSPARENT, 0x26FFFFFF)
        stateListAnimator = null
    }

    private fun buildShell(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        header = head
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(textColor)
            background = null
            contentDescription = "بازگشت"
            tag = "wallet_back"
            setOnClickListener { @Suppress("DEPRECATION") onBackPressed() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        title = label("", 15f, bold = true)
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progressLabel = label("", 12f, muted)
        row.addView(progressLabel)
        head.addView(row)
        progressBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) })
        root.addView(head)
        root.addView(View(context).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        scroll = ScrollView(context).apply { isFillViewport = true }
        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(20), 0, dp(24)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(View(context).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val footer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12)) }
        errorText = label("", 13f, errorColor).apply { visibility = View.GONE; setPadding(0, 0, 0, dp(8)) }
        footer.addView(errorText)
        primary = button(true).apply { setOnClickListener { onPrimary() }; tag = "wallet_primary" }
        footer.addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        secondary = button(false).apply { setOnClickListener { onSecondary() }; tag = "wallet_secondary" }
        footer.addView(secondary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) })
        root.addView(footer)
        return root
    }

    private fun onPrimary() {
        if (busy) return
        errorText.visibility = View.GONE
        when (flow.step) {
            WalletChargeFlow.Step.HOME -> { flow.openAmount(); render() }
            WalletChargeFlow.Step.AMOUNT -> if (flow.next()) render()
            WalletChargeFlow.Step.SUMMARY -> {
                busy = true
                render()
                onPay(flow.body())
            }
            WalletChargeFlow.Step.WAITING -> {
                busy = true
                render()
                onCheck(flow.txnId, false)
            }
            WalletChargeFlow.Step.RESULT -> {
                val r = flow.result ?: return dismiss()
                when {
                    r.state == "pending" && r.canRecheck -> { busy = true; render(); onCheck(flow.txnId, true) }
                    r.canRetry -> { flow.retry(); render() }
                    else -> finishTo(r.returnTarget)
                }
            }
        }
    }

    private fun onSecondary() {
        if (busy) return
        val r = flow.result
        if (flow.step == WalletChargeFlow.Step.RESULT && r != null) finishTo(r.returnTarget) else dismiss()
    }

    private fun finishTo(target: WalletCharge.Return) {
        dismiss()
        onReturn(target)
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        val step = flow.step
        val o = flow.options
        header.visibility = if (step == WalletChargeFlow.Step.RESULT) View.GONE else View.VISIBLE
        title.text = when (step) {
            WalletChargeFlow.Step.HOME -> o.text("wallet_title")
            WalletChargeFlow.Step.SUMMARY -> o.text("summary_title")
            else -> o.text("amount_title")
        }
        progressLabel.text = flow.progressLabel()
        progressBar.removeAllViews()
        val stepped = step == WalletChargeFlow.Step.AMOUNT || step == WalletChargeFlow.Step.SUMMARY
        progressBar.visibility = if (stepped) View.VISIBLE else View.GONE
        if (stepped) {
            for (i in 0 until 2) {
                val on = i == 0 || step == WalletChargeFlow.Step.SUMMARY
                progressBar.addView(View(context).apply { background = rounded(if (on) green else 0x1FFFFFFF, radius = 4) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(6) })
            }
        }
        body.removeAllViews()
        body.gravity = Gravity.NO_GRAVITY
        body.setPadding(0, dp(20), 0, dp(24))
        secondary.visibility = View.GONE
        when (step) {
            WalletChargeFlow.Step.HOME -> renderHome()
            WalletChargeFlow.Step.AMOUNT -> renderAmount()
            WalletChargeFlow.Step.SUMMARY -> renderSummary()
            WalletChargeFlow.Step.WAITING -> renderWaiting()
            WalletChargeFlow.Step.RESULT -> renderResult()
        }
        val enabled = !busy && (step != WalletChargeFlow.Step.AMOUNT || flow.canContinue())
        primary.isEnabled = enabled
        primary.alpha = if (enabled) 1f else 0.4f
        if (step != WalletChargeFlow.Step.AMOUNT) scroll.scrollTo(0, 0)
    }

    private fun renderHome() {
        val o = flow.options
        val h = o.header
        body.addView(label(o.text("available_label"), 13f, muted).apply { setPadding(dp(16), 0, dp(16), 0) })
        body.addView(label(WalletCharge.toman(h.available), 30f, bold = true).apply { setPadding(dp(16), dp(4), dp(16), 0); tag = "wallet_available" })
        body.addView(label(o.text("available_hint"), 12f, muted).apply { setPadding(dp(16), dp(4), dp(16), 0) })
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = rounded(0x0DFFFFFF, 0x1AFFFFFF) }
        h.lines.forEachIndexed { i, line ->
            if (i > 0) box.addView(View(context).apply { setBackgroundColor(0x1AFFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); tag = "wallet_line_${line.key}" }
            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(label(line.label, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(label(line.value, 14f, bold = true))
            cell.addView(top)
            if (line.hint.isNotBlank()) cell.addView(label(line.hint, 12f, muted).apply { setPadding(0, dp(2), 0, 0) })
            box.addView(cell)
        }
        body.addView(box, matchWrap(16))
        body.addView(label(o.text("transactions_title"), 15f, bold = true).apply { setPadding(dp(16), dp(24), dp(16), 0) })
        if (o.groups.isEmpty()) {
            body.addView(label(o.text("transactions_empty"), 13f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(32), dp(16), 0) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        o.groups.forEach { g ->
            body.addView(label(g.label, 12f, muted, bold = true).apply { setPadding(dp(16), dp(16), dp(16), dp(6)) })
            g.items.forEach { addTxn(it) }
        }
        primary.text = o.text("topup_button")
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

    private fun addTxn(it: WalletCharge.Item) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minimumHeight = dp(64)
            tag = "wallet_txn_${it.kind}"
        }
        val (glyph, color) = iconFor(it.kind)
        val icon = FrameLayout(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x0FFFFFFF) } }
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
        body.addView(row)
        body.addView(View(context).apply { setBackgroundColor(0x0DFFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    }

    private var input: EditText? = null
    private var wordsView: TextView? = null
    private var amountError: TextView? = null
    private var field: LinearLayout? = null
    private var chips: LinearLayout? = null

    private fun renderAmount() {
        val o = flow.options
        body.addView(label(o.text("amount_question"), 18f, bold = true).apply { setPadding(dp(16), 0, dp(16), 0) })
        body.addView(label("${o.text("available_label")}: ${WalletCharge.toman(o.header.available)}", 13f, muted).apply { setPadding(dp(16), dp(4), dp(16), 0) })
        if (o.reasonLine.isNotBlank()) {
            body.addView(label("ⓘ  " + o.reasonLine, 13f, 0xFFFDE68A.toInt()).apply {
                background = rounded(0x4D451A03, 0x4DFBBF24)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                tag = "wallet_reason"
            }, matchWrap(16))
        }
        val f = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(14), 0, dp(14), 0)
        }
        field = f
        val et = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(24))
            hint = o.text("amount_placeholder")
            setHintTextColor(muted)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create(regular, Typeface.BOLD)
            background = null
            textDirection = View.TEXT_DIRECTION_RTL
            tag = "wallet_amount_input"
            setText(flow.inputLabel())
            setSelection(text.length)
        }
        input = et
        f.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        f.addView(label(o.text("amount_suffix"), 14f, muted))
        body.addView(f, matchWrap(20))
        wordsView = label("", 13f, 0xFF6EE7B7.toInt()).apply { setPadding(dp(16), dp(8), dp(16), 0); tag = "wallet_words" }
        body.addView(wordsView)
        body.addView(label(o.text("amount_min"), 12f, muted).apply { setPadding(dp(16), dp(2), dp(16), 0) })
        amountError = label("", 12f, errorColor).apply { setPadding(dp(16), dp(2), dp(16), 0) }
        body.addView(amountError)
        val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val c = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), 0) }
        chips = c
        o.presets.forEach { p ->
            c.addView(label(p.label, 14f, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(16), 0, dp(16), 0)
                tag = p.amount
                setOnClickListener { flow.pick(p.amount); syncInput(); paintAmount() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        chipScroll.addView(c)
        body.addView(chipScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        et.addTextChangedListener(object : TextWatcher {
            private var self = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (self) return
                flow.setInput(s?.toString())
                val shown = flow.inputLabel()
                if (shown != s?.toString()) {
                    self = true
                    et.setText(shown)
                    et.setSelection(shown.length)
                    self = false
                }
                paintAmount()
            }
        })
        primary.text = o.text("next")
        paintAmount()
    }

    private fun syncInput() {
        val et = input ?: return
        val shown = flow.inputLabel()
        et.setText(shown)
        et.setSelection(shown.length)
    }

    private fun paintAmount() {
        wordsView?.text = flow.words()
        val err = flow.error()
        amountError?.text = err
        field?.background = rounded(0x0AFFFFFF, if (err.isNotEmpty()) errorColor else 0x29FFFFFF, 14)
        val picked = flow.selectedPreset()
        chips?.let { c ->
            for (i in 0 until c.childCount) {
                val v = c.getChildAt(i) as TextView
                val on = v.tag == picked
                v.background = rounded(if (on) 0x2410B981 else Color.TRANSPARENT, if (on) green else 0x29FFFFFF, 99)
                v.setTextColor(if (on) 0xFF6EE7B7.toInt() else textColor)
            }
        }
        val enabled = !busy && flow.canContinue()
        primary.isEnabled = enabled
        primary.alpha = if (enabled) 1f else 0.4f
    }

    private fun renderSummary() {
        val o = flow.options
        body.addView(label(o.text("summary_question"), 18f, bold = true).apply { setPadding(dp(16), 0, dp(16), 0) })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = rounded(0x0DFFFFFF, 0x1AFFFFFF) }
        flow.summaryRows().forEachIndexed { i, (k, v) ->
            if (i > 0) list.addView(View(context).apply { setBackgroundColor(0x1AFFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
            row.addView(label(k, 14f, muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label(v, 14f, bold = true))
            list.addView(row)
        }
        body.addView(list, matchWrap(16))
        body.addView(label(o.text("summary_note"), 13f, 0xFFA7F3D0.toInt()).apply {
            background = rounded(0x4D022C22, 0x4D10B981)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }, matchWrap(16))
        primary.text = if (busy) o.text("paying") else o.text("pay")
    }

    private fun centered() {
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(dp(20), dp(56), dp(20), dp(24))
    }

    private fun badge(glyph: String, fill: Int, color: Int, tag: String) {
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
            this.tag = tag
        }
        circle.addView(label(glyph, 36f, color, bold = true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(circle, LinearLayout.LayoutParams(dp(84), dp(84)))
    }

    private fun centerText(text: String, size: Float, color: Int = textColor, bold: Boolean = false, top: Int = 12) {
        if (text.isBlank()) return
        body.addView(label(text, size, color, bold).apply { gravity = Gravity.CENTER; setPadding(0, dp(top), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun renderWaiting() {
        val o = flow.options
        centered()
        badge("⏳", 0x26FBBF24, amber, "wallet_waiting")
        centerText(o.text("waiting_title"), 20f, bold = true, top = 20)
        centerText(WalletCharge.toman(flow.amount()), 24f, bold = true)
        centerText(o.text("waiting_body"), 14f, muted)
        primary.text = if (busy) "…" else o.text("check_result")
    }

    private fun renderResult() {
        val r = flow.result ?: return
        val o = flow.options
        centered()
        when (r.state) {
            "success" -> badge("✓", 0x2610B981, green, "wallet_result_success")
            "pending" -> badge("⏳", 0x26FBBF24, amber, "wallet_result_pending")
            else -> badge("✕", 0x26F43F5E, errorColor, "wallet_result_${r.state}")
        }
        centerText(r.title, 20f, bold = true, top = 20)
        centerText(r.amountLabel, 26f, if (r.state == "success") 0xFF6EE7B7.toInt() else textColor, bold = true)
        centerText(r.message, 14f, 0xFFD4D4D8.toInt())
        if (r.note.isNotBlank()) {
            body.addView(label(r.note, 13f, 0xFFD4D4D8.toInt()).apply {
                background = rounded(0x0DFFFFFF, 0x1AFFFFFF)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                tag = "wallet_result_note"
            }, matchWrap(16, 0))
        }
        if (r.state == "success") {
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = rounded(0x0DFFFFFF, 0x1AFFFFFF) }
            val rows = mutableListOf<Pair<String, String>>()
            if (r.balanceLabel.isNotBlank()) rows.add(o.text("success_balance") to r.balanceLabel)
            if (r.refId.isNotBlank()) rows.add(o.text("success_ref") to ListingWeek.persianDigits(r.refId))
            rows.add(o.text("summary_gateway") to o.gatewayLabel)
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) list.addView(View(context).apply { setBackgroundColor(0x1AFFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
                row.addView(label(k, 14f, muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(label(v, 14f, bold = true))
                list.addView(row)
            }
            body.addView(list, matchWrap(24, 0))
        }
        val back = r.returnTarget.label.ifBlank { o.text("back_to_wallet") }
        when {
            r.state == "pending" && r.canRecheck -> { primary.text = if (busy) "…" else o.text("recheck"); showSecondary(back) }
            r.canRetry -> { primary.text = o.text("retry"); showSecondary(back) }
            else -> primary.text = back
        }
    }

    private fun showSecondary(text: String) {
        secondary.text = text
        secondary.visibility = View.VISIBLE
    }
}
