package ir.vinor.app

import org.json.JSONObject

/**
 * پرسش‌نامهٔ مرحله‌ای «فروخته شد» به سبک دیوار؛ هم‌ارز صفحهٔ وب
 * /express/partner/my-property/<code>/sold. منطق مراحل اینجا و بدون وابستگی به Android است تا
 * با تست واحد پوشش داده شود. پاسخ‌های اختیاری فقط برای آمار/اطلاع است؛ پورسانت و بلوکه را
 * سرور مثل قبل حساب می‌کند.
 */
class OwnerSoldFlow(val options: OwnerSold.Options) {

    enum class Step { CHANNEL, VISIT, SOURCE, PRICE, RATING, SUMMARY }

    data class ChannelOption(val value: String, val label: String, val hint: String, val enabled: Boolean)
    data class SourceOption(val value: String, val label: String)
    data class VisitRow(
        val visitId: String,
        val name: String,
        val dateLabel: String,
        val statusLabel: String,
        val summary: String,
    )

    var channel: String = ""
        private set
    var visitId: String = ""
        private set
    var source: String = ""
        private set
    var priceDigits: String = ""
        private set
    var rating: Int = 0
        private set
    var comment: String = ""
        private set
    var step: Step = Step.CHANNEL
        private set
    private val trail = ArrayList<Step>().apply { add(Step.CHANNEL) }

    fun text(key: String): String = options.texts[key] ?: DEFAULT_TEXTS[key].orEmpty()

    fun steps(): List<Step> = when (channel) {
        CHANNEL_VINOR -> listOf(Step.CHANNEL, Step.VISIT, Step.PRICE, Step.RATING, Step.SUMMARY)
        CHANNEL_OUTSIDE -> listOf(Step.CHANNEL, Step.SOURCE, Step.PRICE, Step.SUMMARY)
        else -> listOf(Step.CHANNEL)
    }

    /** تعداد کل مراحل در نوار پیشرفت (قبل از انتخاب، مسیر بلندتر). */
    fun total(): Int = when (channel) {
        CHANNEL_OUTSIDE -> 4
        else -> 5
    }

    fun index(): Int = steps().indexOf(step).coerceAtLeast(0)

    fun progressLabel(): String = "مرحلهٔ ${ListingWeek.persianDigits((index() + 1).toString())} از ${ListingWeek.persianDigits(total().toString())}"

    fun isOptional(s: Step = step): Boolean = s == Step.SOURCE || s == Step.PRICE || s == Step.RATING

    fun isLast(): Boolean = step == Step.SUMMARY

    /** «هنوز قطعی نشده / منصرف شدم» بدون فرستادن چیزی به سرور بسته می‌شود. */
    fun closesWithoutChange(): Boolean = step == Step.CHANNEL && channel == CHANNEL_NOT_FINAL

    fun channelEnabled(value: String): Boolean =
        options.channels.firstOrNull { it.value == value }?.enabled ?: (value != CHANNEL_VINOR || options.visits.isNotEmpty())

    fun selectChannel(value: String): Boolean {
        if (value !in CHANNELS || !channelEnabled(value)) return false
        channel = value
        if (value != CHANNEL_VINOR) {
            visitId = ""
            rating = 0
            comment = ""
        }
        if (value != CHANNEL_OUTSIDE) source = ""
        return true
    }

    fun selectVisit(id: String): Boolean {
        if (channel != CHANNEL_VINOR || options.visits.none { it.visitId == id }) return false
        visitId = id
        return true
    }

    fun selectSource(value: String): Boolean {
        if (channel != CHANNEL_OUTSIDE || options.sources.none { it.value == value }) return false
        source = value
        return true
    }

    /** ورودی کاربر (فارسی یا لاتین، با جداکننده) → فقط رقم‌ها. */
    fun setPrice(input: String) {
        priceDigits = digitsOf(input).trimStart('0')
    }

    fun priceValid(): Boolean {
        if (priceDigits.isEmpty()) return true
        val n = priceDigits.toLongOrNull() ?: return false
        return n in 1..options.finalPriceMax
    }

    fun setRating(n: Int) {
        if (channel != CHANNEL_VINOR) return
        rating = if (n in 1..5 && n != rating) n else 0
    }

    fun setComment(value: String) {
        if (channel != CHANNEL_VINOR) return
        comment = value.take(options.commentMax)
    }

    fun canNext(): Boolean = when (step) {
        Step.CHANNEL -> channel.isNotEmpty()
        Step.VISIT -> visitId.isNotEmpty()
        Step.PRICE -> priceValid()
        else -> true
    }

    fun next(): Boolean {
        if (!canNext() || closesWithoutChange()) return false
        val list = steps()
        val i = list.indexOf(step)
        if (i < 0 || i >= list.size - 1) return false
        step = list[i + 1]
        trail.add(step)
        return true
    }

    /** «رد شدن» روی مرحلهٔ اختیاری: پاسخ همان مرحله پاک و مرحلهٔ بعد. */
    fun skip(): Boolean {
        when (step) {
            Step.SOURCE -> source = ""
            Step.PRICE -> priceDigits = ""
            Step.RATING -> { rating = 0; comment = "" }
            else -> return false
        }
        return next()
    }

    /** false یعنی روی مرحلهٔ اول هستیم و برگشت، پرسش‌نامه را می‌بندد. */
    fun back(): Boolean {
        if (trail.size <= 1) return false
        trail.removeAt(trail.size - 1)
        step = trail.last()
        return true
    }

    fun selectedVisit(): VisitRow? = options.visits.firstOrNull { it.visitId == visitId }

    fun summaryRows(): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        if (channel == CHANNEL_VINOR) {
            rows.add("روش فروش" to text("channel_vinor"))
            selectedVisit()?.let { v -> rows.add("بازدید" to listOf(v.name, v.dateLabel).filter { it.isNotBlank() }.joinToString(" · ")) }
            rows.add("رضایت از بازاریاب" to if (rating > 0) "★".repeat(rating) + " " + options.ratingLabels[rating].orEmpty() else NOT_GIVEN)
        } else {
            rows.add("روش فروش" to text("channel_outside"))
            rows.add("از طریق" to (options.sources.firstOrNull { it.value == source }?.label ?: NOT_GIVEN))
        }
        rows.add("قیمت نهایی" to if (priceDigits.isNotEmpty()) formatPrice(priceDigits) + " تومان" else NOT_GIVEN)
        return rows
    }

    /** متن سرور دربارهٔ پورسانت و بلوکه برای صفحهٔ مرور. */
    fun escrowText(): String = if (channel == CHANNEL_VINOR) {
        selectedVisit()?.summary.orEmpty()
    } else {
        options.outsideSummary.ifBlank { options.choices.lastOrNull()?.confirmMessage.orEmpty() }
    }

    fun escrowIsReview(): Boolean = channel == CHANNEL_OUTSIDE && options.reviewRequiredIfOutside

    /** هشدار کهربایی روی مرحلهٔ «از چه طریقی فروختید؟». */
    fun showReviewWarning(): Boolean = step == Step.SOURCE && options.reviewRequiredIfOutside

    fun body(code: String): JSONObject {
        val body = JSONObject().put("code", code.trim()).put("channel", channel)
        if (channel == CHANNEL_VINOR) {
            body.put("visit_id", visitId)
            if (rating in 1..5) body.put("marketer_rating", rating)
            if (comment.isNotBlank()) body.put("comment", comment.trim())
        }
        if (channel == CHANNEL_OUTSIDE && source.isNotEmpty()) body.put("sold_source", source)
        priceDigits.toLongOrNull()?.takeIf { priceValid() && it > 0 }?.let { body.put("final_price", it) }
        return body
    }

    companion object {
        const val CHANNEL_VINOR = OwnerSold.CHANNEL_VINOR
        const val CHANNEL_OUTSIDE = OwnerSold.CHANNEL_OUTSIDE
        const val CHANNEL_NOT_FINAL = "not_final"
        val CHANNELS = setOf(CHANNEL_VINOR, CHANNEL_OUTSIDE, CHANNEL_NOT_FINAL)
        const val COMMENT_MAX = 300
        const val FINAL_PRICE_MAX = 10_000_000_000_000L
        const val NOT_GIVEN = "ثبت نشد"

        val DEFAULT_TEXTS: Map<String, String> = mapOf(
            "channel_question" to "ملک‌تان از چه راهی فروخته شد؟",
            "channel_vinor" to "با بازدیدی که در وینور هماهنگ شد",
            "channel_vinor_hint" to "بازاریاب وینور مشتری را آورد",
            "channel_vinor_disabled" to "هنوز بازدید انجام‌شده‌ای برای این ملک ثبت نشده است",
            "channel_outside" to "خودم بیرون از وینور فروختم",
            "channel_not_final" to "هنوز قطعی نشده / منصرف شدم",
            "channel_not_final_hint" to "چیزی ثبت نمی‌شود و آگهی همین‌طور می‌ماند",
            "visit_question" to "کدام بازدید به فروش رسید؟",
            "visit_hint" to "پورسانت به بازاریاب همین بازدید می‌رسد.",
            "source_question" to "از چه طریقی فروختید؟",
            "source_hint" to "اختیاری؛ فقط برای بهتر شدن وینور می‌پرسیم.",
            "price_question" to "قیمت نهایی فروش چقدر بود؟",
            "price_hint" to "اختیاری و فقط برای اطلاع است؛ روی پورسانت اثری ندارد.",
            "price_placeholder" to "مثلاً ۲٬۵۰۰٬۰۰۰٬۰۰۰",
            "rating_question" to "از بازاریاب راضی بودید؟",
            "rating_hint" to "اختیاری؛ نظر شما فقط برای پشتیبانی وینور دیده می‌شود.",
            "comment_placeholder" to "اگر نکته‌ای دارید بنویسید (اختیاری)",
            "summary_title" to "مرور و ثبت فروش",
            "summary_hint" to "بعد از ثبت، آگهی بسته می‌شود و دیگر قابل بازگرداندن نیست.",
            "submit" to "ثبت فروش",
            "next" to "ادامه",
            "skip" to "رد شدن",
            "not_final_message" to "تغییری ثبت نشد. هر وقت فروش قطعی شد، دوباره «فروخته شد» را بزنید.",
        )

        val DEFAULT_SOURCES = listOf(
            SourceOption("agency", "مشاور املاک"),
            SourceOption("acquaintance", "آشنا و همسایه"),
            SourceOption("other_platform", "سایت یا اپ دیگر"),
            SourceOption("other", "سایر"),
        )

        val DEFAULT_RATING_LABELS = mapOf(1 to "خیلی بد", 2 to "بد", 3 to "معمولی", 4 to "خوب", 5 to "عالی")

        fun defaultChannelLabel(value: String, texts: Map<String, String>): String = when (value) {
            CHANNEL_VINOR -> texts["channel_vinor"].orEmpty()
            CHANNEL_OUTSIDE -> texts["channel_outside"].orEmpty()
            else -> texts["channel_not_final"].orEmpty()
        }

        /** سرور قدیمی channels نمی‌فرستد. */
        fun defaultChannels(hasVisits: Boolean, texts: Map<String, String>): List<ChannelOption> = listOf(
            ChannelOption(
                CHANNEL_VINOR,
                texts["channel_vinor"].orEmpty(),
                if (hasVisits) texts["channel_vinor_hint"].orEmpty() else texts["channel_vinor_disabled"].orEmpty(),
                hasVisits,
            ),
            ChannelOption(CHANNEL_OUTSIDE, texts["channel_outside"].orEmpty(), "", true),
            ChannelOption(CHANNEL_NOT_FINAL, texts["channel_not_final"].orEmpty(), texts["channel_not_final_hint"].orEmpty(), true),
        )

        fun digitsOf(input: String): String {
            val out = StringBuilder()
            for (ch in input) {
                when (ch) {
                    in '0'..'9' -> out.append(ch)
                    in '۰'..'۹' -> out.append('0' + (ch - '۰'))
                    in '٠'..'٩' -> out.append('0' + (ch - '٠'))
                }
            }
            return out.toString()
        }

        /** «2450000000» → «۲٬۴۵۰٬۰۰۰٬۰۰۰». */
        fun formatPrice(digits: String): String {
            val clean = digitsOf(digits).trimStart('0')
            if (clean.isEmpty()) return ""
            val grouped = StringBuilder()
            clean.reversed().forEachIndexed { i, c ->
                if (i > 0 && i % 3 == 0) grouped.append('٬')
                grouped.append(c)
            }
            return ListingWeek.persianDigits(grouped.reverse().toString())
        }
    }
}
