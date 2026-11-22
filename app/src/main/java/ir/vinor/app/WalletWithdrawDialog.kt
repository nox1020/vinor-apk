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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.json.JSONObject

/**
 * ویزارد تمام‌صفحهٔ «برداشت» به سبک دیوار (هم‌ارز وب): هر مرحله یک سؤال با گزینه‌های بزرگ.
 * منطق در [WalletWithdrawFlow]؛ شبکه را صدازننده انجام می‌دهد ([onSubmit] → [showOutcome]).
 */
class WalletWithdrawDialog(
    context: Context,
    val flow: WalletWithdrawFlow,
    private val onSubmit: (JSONObject) -> Unit,
    private val onClosed: (submitted: Boolean) -> Unit,
) : Dialog(context, R.style.Theme_Vinor_FullScreenDialog) {

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
    private lateinit var errorText: TextView
    private var busy = false

    init {
        setContentView(buildShell())
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        setCancelable(true)
        setOnDismissListener { onClosed(flow.submitted != null) }
        render()
    }

    @Deprecated("Dialog back handling")
    override fun onBackPressed() {
        if (busy) return
        if (!flow.back()) dismiss() else render()
    }

    // ---------------------------------------------------------------- host API

    fun updateOptions(options: WalletWithdrawWizard.Options) {
        flow.applyOptions(options)
        if (!busy) render()
    }

    fun showOutcome(outcome: WalletWithdrawWizard.Outcome) {
        busy = false
        when (outcome) {
            is WalletWithdrawWizard.Outcome.Submitted -> {
                errorText.visibility = View.GONE
                flow.done(outcome.request)
            }
            is WalletWithdrawWizard.Outcome.Failed -> {
                errorText.text = outcome.message
                errorText.visibility = View.VISIBLE
            }
        }
        render()
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

    private fun buildShell(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)) }
        header = head
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(textColor)
            background = null
            contentDescription = "بازگشت"
            tag = "withdraw_back"
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
        scroll = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(20), 0, dp(24)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(View(context).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val footer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12)) }
        errorText = label("", 13f, errorColor).apply { visibility = View.GONE; setPadding(0, 0, 0, dp(8)); tag = "withdraw_error" }
        footer.addView(errorText)
        primary = Button(context).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(regular, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(green)
            stateListAnimator = null
            tag = "withdraw_primary"
            setOnClickListener { onPrimary() }
        }
        footer.addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(footer)
        return root
    }

    private fun onPrimary() {
        if (busy) return
        errorText.visibility = View.GONE
        when {
            !flow.options.loaded -> return
            flow.belowMin || flow.step == WalletWithdrawFlow.Step.DONE -> dismiss()
            flow.step == WalletWithdrawFlow.Step.SUMMARY -> {
                busy = true
                render()
                onSubmit(flow.body())
            }
            flow.next() -> { render(); scroll.scrollTo(0, 0) }
        }
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        val o = flow.options
        val step = flow.step
        val stepped = o.loaded && !flow.belowMin && step != WalletWithdrawFlow.Step.DONE
        header.visibility = if (step == WalletWithdrawFlow.Step.DONE) View.GONE else View.VISIBLE
        title.text = o.text("title")
        progressLabel.text = if (stepped) flow.progressLabel() else ""
        progressBar.removeAllViews()
        progressBar.visibility = if (stepped) View.VISIBLE else View.GONE
        if (stepped) {
            for (i in 1..3) {
                val on = i <= flow.progressIndex()
                progressBar.addView(View(context).apply { background = rounded(if (on) green else 0x1FFFFFFF, radius = 4) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 1) marginStart = dp(6) })
            }
        }
        body.removeAllViews()
        body.gravity = Gravity.NO_GRAVITY
        body.setPadding(0, dp(20), 0, dp(24))
        when {
            !o.loaded -> renderLoading()
            flow.belowMin -> renderBelowMin()
            step == WalletWithdrawFlow.Step.AMOUNT -> renderAmount()
            step == WalletWithdrawFlow.Step.DEST -> renderDest()
            step == WalletWithdrawFlow.Step.SUMMARY -> renderSummary()
            else -> renderDone()
        }
        paintPrimary()
    }

    private fun paintPrimary() {
        val o = flow.options
        primary.text = when {
            !o.loaded -> "…"
            flow.belowMin || flow.step == WalletWithdrawFlow.Step.DONE -> o.text("back_to_wallet")
            flow.step == WalletWithdrawFlow.Step.SUMMARY -> if (busy) o.text("submitting") else o.text("submit")
            else -> o.text("next")
        }
        val enabled = o.loaded && !busy && (flow.belowMin || flow.canContinue())
        primary.isEnabled = enabled
        primary.alpha = if (enabled) 1f else 0.4f
    }

    private fun question(text: String) {
        body.addView(label(text, 20f, bold = true).apply { setPadding(dp(16), 0, dp(16), 0); tag = "withdraw_question" })
    }

    private fun renderLoading() {
        body.addView(label("…", 20f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(80), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        circle.addView(label(glyph, 34f, color, bold = true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(circle, LinearLayout.LayoutParams(dp(84), dp(84)))
    }

    private fun centerText(text: String, size: Float, color: Int = textColor, bold: Boolean = false, top: Int = 12) {
        if (text.isBlank()) return
        body.addView(label(text, size, color, bold).apply { gravity = Gravity.CENTER; setPadding(0, dp(top), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun renderBelowMin() {
        val o = flow.options
        centered()
        badge("!", 0x26FBBF24, amber, "withdraw_below_min")
        centerText(o.text("below_min_title"), 20f, bold = true, top = 20)
        centerText(o.text("below_min_body"), 14f, muted)
        o.notes.forEach { centerText(it.text, 13f, muted, top = 8) }
    }

    private fun notes() {
        flow.options.notes.forEach { n ->
            body.addView(label((if (n.key == "blocked") "🔒  " else "⏳  ") + n.text, 13f, 0xFFD4D4D8.toInt()).apply {
                background = rounded(0x0DFFFFFF, 0x1AFFFFFF)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                tag = "withdraw_note_${n.key}"
            }, matchWrap(12))
        }
    }

    private var input: EditText? = null
    private var wordsView: TextView? = null
    private var amountError: TextView? = null
    private var field: LinearLayout? = null
    private var chipRow: LinearLayout? = null

    private fun renderAmount() {
        val o = flow.options
        question(o.text("amount_question"))
        body.addView(label("${o.text("amount_available").substringBefore(":")}: ${WalletWithdrawWizard.toman(o.available)}", 13f, muted)
            .apply { setPadding(dp(16), dp(4), dp(16), 0); tag = "withdraw_available" })
        // چیپ‌ها: دو ستونی تا «همهٔ موجودی» با مبلغش جا شود
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        chipRow = grid
        o.chips.chunked(2).forEach { pair ->
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, chip ->
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(52)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    tag = "withdraw_chip_${chip.key}"
                    setOnClickListener { flow.pick(chip); syncInput(); paintAmount() }
                }
                cell.addView(label(chip.label, 15f, bold = true).apply { gravity = Gravity.CENTER })
                if (chip.sub.isNotBlank()) cell.addView(label(chip.sub, 11f, muted).apply { gravity = Gravity.CENTER })
                r.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            if (pair.size == 1) r.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
            grid.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        body.addView(grid, matchWrap(12))
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
            tag = "withdraw_amount_input"
            setText(if (flow.takeAll) "" else WalletCharge.grouped(flow.digits))
            setSelection(text.length)
        }
        input = et
        f.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        f.addView(label(o.text("amount_suffix"), 14f, muted))
        body.addView(f, matchWrap(14))
        wordsView = label("", 13f, 0xFF6EE7B7.toInt()).apply { setPadding(dp(16), dp(8), dp(16), 0); tag = "withdraw_words" }
        body.addView(wordsView)
        body.addView(label(o.text("amount_min"), 12f, muted).apply { setPadding(dp(16), dp(2), dp(16), 0) })
        amountError = label("", 12f, errorColor).apply { setPadding(dp(16), dp(2), dp(16), 0) }
        body.addView(amountError)
        notes()
        et.addTextChangedListener(object : TextWatcher {
            private var self = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (self) return
                flow.setInput(s?.toString())
                val shown = WalletCharge.grouped(flow.digits)
                if (shown != s?.toString()) {
                    self = true
                    et.setText(shown)
                    et.setSelection(shown.length)
                    self = false
                }
                paintAmount()
            }
        })
        paintAmount()
    }

    private fun syncInput() {
        val et = input ?: return
        val shown = if (flow.takeAll) "" else WalletCharge.grouped(flow.digits)
        et.setText(shown)
        et.setSelection(shown.length)
    }

    private fun paintAmount() {
        wordsView?.text = WalletCharge.words(flow.amount())
        val err = flow.amountError()
        amountError?.text = err
        field?.background = rounded(0x0AFFFFFF, if (err.isNotEmpty()) errorColor else 0x29FFFFFF, 14)
        val picked = flow.selectedChip()
        chipRow?.let { g ->
            for (i in 0 until g.childCount) {
                val r = g.getChildAt(i) as? LinearLayout ?: continue
                for (j in 0 until r.childCount) {
                    val cell = r.getChildAt(j) as? LinearLayout ?: continue
                    val key = (cell.tag as? String)?.removePrefix("withdraw_chip_") ?: continue
                    val on = key == picked
                    cell.background = rounded(if (on) 0x2410B981 else Color.TRANSPARENT, if (on) green else 0x29FFFFFF, 14)
                    (cell.getChildAt(0) as? TextView)?.setTextColor(if (on) 0xFF6EE7B7.toInt() else textColor)
                }
            }
        }
        paintPrimary()
    }

    private fun option(tag: String, titleText: String, sub: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(if (selected) 0x1A10B981 else Color.TRANSPARENT, if (selected) green else 0x29FFFFFF, 14)
            this.tag = tag
            setOnClickListener { onClick() }
        }
        val radio = FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), if (selected) green else 0x59FFFFFF) }
        }
        if (selected) radio.addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(green) } },
            FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        row.addView(radio, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(titleText, 15f, bold = true))
        if (sub.isNotBlank()) col.addView(label(sub, 12.5f, muted).apply { maxLines = 1 })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private var shebaError: TextView? = null
    private var holderError: TextView? = null
    private var shebaField: LinearLayout? = null

    private fun renderDest() {
        val o = flow.options
        question(o.text("dest_question"))
        o.destinations.forEach { d ->
            body.addView(option("withdraw_dest_${d.id}", d.bankName.ifBlank { "حساب بانکی" }, "${d.shebaMasked} · ${d.holder}",
                flow.destId == d.id) { flow.chooseDest(d.id); render() }, matchWrap(10))
        }
        body.addView(option("withdraw_dest_new", o.text("dest_new"), o.text("dest_new_hint"), flow.destId == WalletWithdrawFlow.NEW) {
            flow.chooseDest(WalletWithdrawFlow.NEW); render()
        }, matchWrap(10))
        if (flow.destId == WalletWithdrawFlow.NEW) {
            val sf = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), 0, dp(14), 0)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            shebaField = sf
            sf.addView(label("IR", 16f, muted, bold = true).apply { textDirection = View.TEXT_DIRECTION_LTR })
            val se = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(32))
                hint = o.text("sheba_placeholder")
                setHintTextColor(muted)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = regular
                background = null
                textDirection = View.TEXT_DIRECTION_LTR
                tag = "withdraw_sheba"
                setText(flow.shebaInput)
            }
            sf.addView(se, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            body.addView(sf, matchWrap(14))
            shebaError = label("", 12f, errorColor).apply { setPadding(dp(16), dp(4), dp(16), 0) }
            body.addView(shebaError)
            body.addView(label(o.text("holder_label"), 13f, muted).apply { setPadding(dp(16), dp(12), dp(16), 0) })
            val he = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                filters = arrayOf(InputFilter.LengthFilter(WalletWithdrawWizard.HOLDER_MAX))
                hint = o.text("holder_placeholder")
                setHintTextColor(muted)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create(regular, Typeface.BOLD)
                background = rounded(0x0AFFFFFF, 0x29FFFFFF, 14)
                setPadding(dp(14), 0, dp(14), 0)
                minHeight = dp(56)
                textDirection = View.TEXT_DIRECTION_RTL
                tag = "withdraw_holder"
                setText(flow.holderInput)
            }
            body.addView(he, matchWrap(6))
            body.addView(label(o.text("holder_hint"), 12f, muted).apply { setPadding(dp(16), dp(4), dp(16), 0) })
            holderError = label("", 12f, errorColor).apply { setPadding(dp(16), dp(2), dp(16), 0) }
            body.addView(holderError)
            se.addTextChangedListener(simpleWatcher { flow.setSheba(it); paintDest() })
            he.addTextChangedListener(simpleWatcher { flow.setHolder(it); paintDest() })
        }
        paintDest()
    }

    private fun simpleWatcher(block: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { block(s?.toString().orEmpty()) }
    }

    private fun paintDest() {
        val sMsg = flow.shebaMessage()
        shebaError?.text = sMsg
        holderError?.text = flow.holderMessage()
        shebaField?.background = rounded(0x0AFFFFFF, if (sMsg.isNotEmpty()) errorColor else 0x29FFFFFF, 14)
        paintPrimary()
    }

    private fun renderSummary() {
        val o = flow.options
        question(o.text("summary_question"))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = rounded(0x0DFFFFFF, 0x1AFFFFFF) }
        flow.summaryRows().forEachIndexed { i, (k, v) ->
            if (i > 0) list.addView(View(context).apply { setBackgroundColor(0x1AFFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
            row.addView(label(k, 14f, muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label(v, if (i == 0) 17f else 14f, bold = true).apply { if (i == 1) textDirection = View.TEXT_DIRECTION_LTR })
            list.addView(row)
        }
        body.addView(list, matchWrap(16))
        body.addView(label(o.text("summary_note"), 13f, 0xFFA7F3D0.toInt()).apply {
            background = rounded(0x4D022C22, 0x4D10B981)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }, matchWrap(16))
    }

    private fun renderDone() {
        val r = flow.submitted ?: return
        val o = flow.options
        centered()
        badge("✓", 0x2610B981, green, "withdraw_done")
        centerText(o.text("success_title"), 20f, bold = true, top = 20)
        centerText(r.amountLabel, 26f, 0xFF6EE7B7.toInt(), bold = true)
        centerText(o.text("success_body"), 14f, 0xFFD4D4D8.toInt())
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = rounded(0x0DFFFFFF, 0x1AFFFFFF) }
        listOf(o.text("success_tracking") to r.tracking, o.text("success_status") to r.statusLabel, o.text("summary_dest") to r.destination)
            .filter { it.second.isNotBlank() }
            .forEachIndexed { i, (k, v) ->
                if (i > 0) list.addView(View(context).apply { setBackgroundColor(0x1AFFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
                row.addView(label(k, 14f, muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(label(v, 14f, if (i == 1) amber else textColor, bold = true).apply {
                    if (i != 1) textDirection = View.TEXT_DIRECTION_LTR
                    tag = "withdraw_done_$i"
                })
                list.addView(row)
            }
        body.addView(list, matchWrap(24, 0))
    }
}
