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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.json.JSONObject

/**
 * پرسش‌نامهٔ تمام‌صفحهٔ «فروخته شد» (سبک دیوار): یک پرسش در هر مرحله، ردیف‌های بزرگ با رادیو یا فلش،
 * نوار مراحل، فلش برگشت و دکمهٔ اصلی چسبیده به پایین. منطق در [OwnerSoldFlow] است.
 *
 * [onSubmit] بدنهٔ JSON را می‌گیرد؛ صدازننده بعد از پاسخ سرور [showSuccess] یا [showError] را صدا می‌زند.
 */
class OwnerSoldWizardDialog(
    context: Context,
    private val flow: OwnerSoldFlow,
    private val listingTitle: String,
    private val onSubmit: (JSONObject) -> Unit,
    private val onFinished: (submitted: Boolean) -> Unit,
) : Dialog(context, R.style.Theme_Vinor_FullScreenDialog) {

    private val green = ContextCompat.getColor(context, R.color.vinor_accent_green)
    private val bg = ContextCompat.getColor(context, R.color.vinor_bg)
    private val raised = ContextCompat.getColor(context, R.color.vinor_raised)
    private val textColor = ContextCompat.getColor(context, R.color.vinor_text)
    private val muted = ContextCompat.getColor(context, R.color.vinor_text_muted)
    private val border = ContextCompat.getColor(context, R.color.vinor_border)
    private val errorColor = ContextCompat.getColor(context, R.color.vinor_error)
    private val regular: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.vazirmatn) }.getOrNull()

    private lateinit var progressBar: LinearLayout
    private lateinit var progressLabel: TextView
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var primary: Button
    private lateinit var skip: TextView
    private lateinit var errorText: TextView
    private lateinit var header: View
    private var submitting = false
    private var done = false

    init {
        setContentView(buildShell())
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        setCancelable(true)
        setOnDismissListener { onFinished(done) }
        render()
    }

    @Deprecated("Dialog back handling")
    override fun onBackPressed() {
        if (submitting) return
        if (done || !flow.back()) {
            dismiss()
            return
        }
        render()
    }

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

    private fun buildShell(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }
        // سربرگ: برگشت، عنوان، شمارهٔ مرحله، نوار مراحل
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        header = head
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = ImageButton(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(textColor)
            background = null
            contentDescription = "بازگشت"
            setOnClickListener { @Suppress("DEPRECATION") onBackPressed() }
            tag = "owner_sold_back"
        }
        row.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(OwnerSold.LABEL, 15f, bold = true))
        if (listingTitle.isNotBlank()) titles.addView(label(listingTitle, 12f, muted).apply { maxLines = 1 })
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progressLabel = label("", 12f, muted)
        row.addView(progressLabel)
        head.addView(row)
        progressBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) })
        root.addView(head)
        root.addView(View(context).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        scroll = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, dp(24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // پایین: خطا، «رد شدن» و دکمهٔ اصلی
        root.addView(View(context).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        errorText = label("", 13f, errorColor).apply { visibility = View.GONE; setPadding(0, 0, 0, dp(8)) }
        footer.addView(errorText)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        skip = label(flow.text("skip"), 14f, muted).apply {
            setPadding(dp(12), 0, dp(16), 0)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setOnClickListener { if (flow.skip()) render() }
        }
        buttons.addView(skip)
        primary = Button(context).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(regular, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(green)
            stateListAnimator = null
            setOnClickListener { onPrimary() }
        }
        buttons.addView(primary, LinearLayout.LayoutParams(0, dp(50), 1f))
        footer.addView(buttons)
        root.addView(footer)
        return root
    }

    private fun onPrimary() {
        if (done) {
            dismiss()
            return
        }
        if (submitting) return
        if (flow.closesWithoutChange()) {
            dismiss()
            return
        }
        if (flow.isLast()) {
            submitting = true
            errorText.visibility = View.GONE
            render()
            onSubmit(flow.body(flow.options.code.ifBlank { "" }))
            return
        }
        if (flow.next()) render()
    }

    fun showError(message: String) {
        submitting = false
        errorText.text = message
        errorText.visibility = View.VISIBLE
        render()
    }

    fun showSuccess(success: OwnerSold.Success) {
        submitting = false
        done = true
        header.visibility = View.GONE
        body.removeAllViews()
        errorText.visibility = View.GONE
        skip.visibility = View.GONE
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(dp(20), dp(56), dp(20), dp(24))
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x2610B981) }
            tag = "owner_sold_success_check"
        }
        circle.addView(label("✓", 38f, green, bold = true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(circle, LinearLayout.LayoutParams(dp(84), dp(84)))
        body.addView(label(success.title, 20f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(4)) })
        if (listingTitle.isNotBlank()) body.addView(label(listingTitle, 13f, muted).apply { gravity = Gravity.CENTER })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(20), 0, 0) }
        success.steps.forEach { step ->
            list.addView(label("•  $step", 14f).apply { setPadding(0, dp(6), 0, dp(6)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        body.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        primary.text = "بازگشت به ملک من"
        primary.isEnabled = true
        primary.alpha = 1f
    }

    private fun render() {
        if (done) return
        // نوار مراحل
        progressBar.removeAllViews()
        val total = flow.total()
        val idx = flow.index()
        for (i in 0 until total) {
            val seg = View(context).apply { background = rounded(if (i <= idx) green else 0x1FFFFFFF, radius = 4) }
            progressBar.addView(seg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
        progressLabel.text = flow.progressLabel()
        body.removeAllViews()
        when (flow.step) {
            OwnerSoldFlow.Step.CHANNEL -> renderChannel()
            OwnerSoldFlow.Step.VISIT -> renderVisit()
            OwnerSoldFlow.Step.SOURCE -> renderSource()
            OwnerSoldFlow.Step.PRICE -> renderPrice()
            OwnerSoldFlow.Step.RATING -> renderRating()
            OwnerSoldFlow.Step.SUMMARY -> renderSummary()
        }
        skip.visibility = if (flow.isOptional() && !submitting) View.VISIBLE else View.GONE
        primary.text = when {
            submitting -> "در حال ثبت…"
            flow.isLast() -> flow.text("submit")
            flow.closesWithoutChange() -> "بستن"
            else -> flow.text("next")
        }
        val enabled = !submitting && flow.canNext()
        primary.isEnabled = enabled
        primary.alpha = if (enabled) 1f else 0.4f
        scroll.scrollTo(0, 0)
    }

    private fun question(text: String, hint: String = "") {
        body.addView(label(text, 18f, bold = true).apply { setPadding(dp(16), 0, dp(16), 0) })
        if (hint.isNotBlank()) body.addView(label(hint, 13f, muted).apply { setPadding(dp(16), dp(4), dp(16), 0) })
        body.addView(View(context), LinearLayout.LayoutParams(1, dp(12)))
    }

    /** ردیف بزرگ قابل‌لمس: رادیو، متن و توضیح، فلش. */
    private fun choiceRow(title: String, sub: String, selected: Boolean, enabled: Boolean, chevron: Boolean, onTap: () -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            alpha = if (enabled) 1f else 0.45f
            isEnabled = enabled
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            if (enabled) setOnClickListener { onTap() }
            contentDescription = title
        }
        val radio = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), if (selected) green else 0x59FFFFFF)
                setColor(if (selected) green else Color.TRANSPARENT)
            }
        }
        row.addView(radio, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f))
        if (sub.isNotBlank()) texts.addView(label(sub, 12f, muted).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (chevron) {
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_left)
                setColorFilter(muted)
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
        }
        body.addView(row)
        body.addView(View(context).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    }

    private fun renderChannel() {
        question(flow.text("channel_question"))
        flow.options.channels.forEach { ch ->
            choiceRow(ch.label, ch.hint, flow.channel == ch.value, ch.enabled, chevron = true) {
                if (flow.selectChannel(ch.value)) render()
            }
        }
    }

    private fun renderVisit() {
        question(flow.text("visit_question"), flow.text("visit_hint"))
        flow.options.visits.forEach { v ->
            val sub = listOf("بازدید ${v.dateLabel}".takeIf { v.dateLabel.isNotBlank() }, v.statusLabel.takeIf { it.isNotBlank() })
                .filterNotNull().joinToString(" · ")
            choiceRow(v.name, sub, flow.visitId == v.visitId, true, chevron = false) {
                if (flow.selectVisit(v.visitId)) render()
            }
        }
    }

    private fun renderSource() {
        question(flow.text("source_question"), flow.text("source_hint"))
        if (flow.showReviewWarning()) {
            // هم‌ارز کادر کهربایی وب: «بیرون از وینور» به بررسی پشتیبانی می‌رود.
            body.addView(label(flow.options.reviewWarning, 13f, OwnerSold.REVIEW_WARNING_COLOR).apply {
                background = rounded(0x4D451A03, 0x4DFBBF24)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                tag = "owner_sold_review_warning"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(8)
            })
        }
        flow.options.sources.forEach { s ->
            choiceRow(s.label, "", flow.source == s.value, true, chevron = false) {
                if (flow.selectSource(s.value)) render()
            }
        }
    }

    private fun renderPrice() {
        question(flow.text("price_question"), flow.text("price_hint"))
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(raised, border)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val error = label("", 12f, errorColor).apply { setPadding(dp(16), dp(8), dp(16), 0) }
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = null
            setTextColor(textColor)
            setHintTextColor(0x66FFFFFF)
            hint = flow.text("price_placeholder")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(regular, Typeface.BOLD)
            filters = arrayOf(InputFilter.LengthFilter(24))
            setText(OwnerSoldFlow.formatPrice(flow.priceDigits))
            setSelection(text.length)
            tag = "owner_sold_price"
        }
        input.addTextChangedListener(object : TextWatcher {
            private var editing = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (editing) return
                editing = true
                flow.setPrice(s?.toString().orEmpty())
                val formatted = OwnerSoldFlow.formatPrice(flow.priceDigits)
                if (formatted != s?.toString()) {
                    input.setText(formatted)
                    input.setSelection(formatted.length)
                }
                error.text = if (flow.priceValid()) "" else "این مبلغ معتبر نیست."
                val ok = !submitting && flow.canNext()
                primary.isEnabled = ok
                primary.alpha = if (ok) 1f else 0.4f
                editing = false
            }
        })
        box.addView(input, LinearLayout.LayoutParams(0, dp(56), 1f))
        box.addView(label("تومان", 14f, muted))
        body.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16); marginEnd = dp(16)
        })
        body.addView(error)
    }

    private fun renderRating() {
        question(flow.text("rating_question"), flow.text("rating_hint"))
        val stars = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        for (n in 1..5) {
            stars.addView(label("★", 36f, if (n <= flow.rating) 0xFFFBBF24.toInt() else 0x38FFFFFF).apply {
                setPadding(dp(6), dp(4), dp(6), dp(4))
                contentDescription = "$n از ۵ — ${flow.options.ratingLabels[n].orEmpty()}"
                setOnClickListener { flow.setRating(n); render() }
            })
        }
        body.addView(stars)
        body.addView(label(flow.options.ratingLabels[flow.rating].orEmpty(), 13f, muted).apply {
            gravity = Gravity.CENTER; minHeight = dp(20)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val comment = EditText(context).apply {
            setText(flow.comment)
            hint = flow.text("comment_placeholder")
            setHintTextColor(0x66FFFFFF)
            setTextColor(textColor)
            typeface = regular
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(flow.options.commentMax))
            background = rounded(raised, border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { flow.setComment(s?.toString().orEmpty()) }
            })
        }
        body.addView(comment, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16); marginEnd = dp(16); topMargin = dp(16)
        })
    }

    private fun renderSummary() {
        question(flow.text("summary_title"))
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(raised, border)
        }
        flow.summaryRows().forEachIndexed { i, (k, v) ->
            if (i > 0) list.addView(View(context).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            row.addView(label(k, 14f, muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label(v, 14f, bold = true))
            list.addView(row)
        }
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16); marginEnd = dp(16); topMargin = dp(12)
        } }
        body.addView(list, lp())
        val review = flow.escrowIsReview()
        body.addView(label(flow.escrowText(), 13f, if (review) OwnerSold.REVIEW_WARNING_COLOR else 0xFFA7F3D0.toInt()).apply {
            background = if (review) rounded(0x4D451A03, 0x4DFBBF24) else rounded(0x4D022C22, 0x4D10B981)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            if (review) tag = "owner_sold_review_warning"
        }, lp())
        body.addView(label(flow.text("summary_hint"), 12f, muted).apply { setPadding(dp(16), dp(12), dp(16), 0) })
    }
}
