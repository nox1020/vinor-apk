package ir.vinor.app

import org.json.JSONObject
import java.net.URLEncoder

/**
 * «فروخته شد» از طرف مالک؛ هم‌ارز صفحهٔ وب /express/partner/my-property/<code>/sold.
 *
 * دکمه فقط وقتی دیده می‌شود که سرور روی کارت ملک can_mark_sold=true بفرستد (یعنی کاربر
 * مالک همین آگهی است و هنوز فروخته نشده). مالکیت دوباره روی سرور چک می‌شود (403).
 * فروش از طریق بازدید بازاریاب همان «تأیید معامله + بستن پرونده» پیگیری است و پورسانت را
 * از بلوکه می‌پردازد؛ فروش بیرون از وینور آگهی را می‌بندد و بلوکه را آزاد می‌کند، مگر
 * بازاریاب بازدید انجام‌شده داشته باشد که آن‌وقت به بررسی پشتیبانی می‌رود.
 */
object OwnerSold {
    const val LABEL = "فروخته شد"
    const val SOLD_BADGE = "فروخته‌شده"
    const val OPTIONS_PATH = "/express/partner/api/my-property/sold-options"
    const val SOLD_PATH = "/express/partner/api/my-property/sold"
    const val CHANNEL_VINOR = "vinor"
    const val CHANNEL_OUTSIDE = "outside"
    const val DEFAULT_QUESTION = "ملک از طریق بازدید بازاریاب وینور فروخته شد؟"
    const val DEFAULT_OUTSIDE_LABEL = "خودم بیرون از وینور فروختم"
    const val FAILURE = "ثبت فروش انجام نشد."
    /** همان متن کهربایی صفحهٔ وب وقتی «بیرون از وینور» به بررسی پشتیبانی می‌رود. */
    const val DEFAULT_REVIEW_WARNING =
        "بازاریاب وینور برای این ملک بازدید ثبت‌شده دارد؛ اگر از طریق او فروخته‌اید، همان بازدید را انتخاب کنید. " +
            "با «بیرون از وینور» پورسانت بلوکه‌شده تا بررسی پشتیبانی آزاد نمی‌شود."
    /** کهربایی ۳۰۰، هم‌رنگ text-amber-300 وب. */
    const val REVIEW_WARNING_COLOR = 0xFFFCD34D.toInt()

    data class Choice(
        val channel: String,
        val visitId: String,
        val label: String,
        val confirmMessage: String,
    )

    data class Options(
        val code: String,
        val question: String,
        val sold: Boolean,
        val canMarkSold: Boolean,
        val choices: List<Choice>,
        val reviewRequiredIfOutside: Boolean = false,
        val reviewWarning: String = "",
        val title: String = "",
        val texts: Map<String, String> = OwnerSoldFlow.DEFAULT_TEXTS,
        val channels: List<OwnerSoldFlow.ChannelOption> = emptyList(),
        val sources: List<OwnerSoldFlow.SourceOption> = OwnerSoldFlow.DEFAULT_SOURCES,
        val visits: List<OwnerSoldFlow.VisitRow> = emptyList(),
        val outsideSummary: String = "",
        val ratingLabels: Map<Int, String> = OwnerSoldFlow.DEFAULT_RATING_LABELS,
        val commentMax: Int = OwnerSoldFlow.COMMENT_MAX,
        val finalPriceMax: Long = OwnerSoldFlow.FINAL_PRICE_MAX,
    )

    fun canMarkSold(snapshot: JSONObject): Boolean =
        snapshot.optBoolean("can_mark_sold", false) && !isSold(snapshot)

    fun isSold(snapshot: JSONObject): Boolean {
        if (snapshot.optBoolean("sold", false)) return true
        return snapshot.optString("express_status").trim().equals("sold", ignoreCase = true)
    }

    fun optionsPath(code: String): String {
        val encoded = URLEncoder.encode(code.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        return "$OPTIONS_PATH?code=$encoded"
    }

    /** بازدیدهای انجام‌شده اول، «بیرون از وینور» همیشه آخر. */
    fun parseOptions(obj: JSONObject): Options {
        val choices = ArrayList<Choice>()
        val visits = obj.optJSONArray("visits")
        if (visits != null) {
            for (i in 0 until visits.length()) {
                val v = visits.optJSONObject(i) ?: continue
                val id = v.optString("visit_id").trim()
                if (id.isEmpty()) continue
                val label = v.optString("label").trim().ifBlank { v.optString("partner_name").trim() }
                choices.add(
                    Choice(
                        channel = CHANNEL_VINOR,
                        visitId = id,
                        label = "از طریق بازدید $label",
                        confirmMessage = v.optString("confirm_message").trim()
                            .ifBlank { "فروش از طریق این بازدید ثبت شود؟" },
                    ),
                )
            }
        }
        choices.add(
            Choice(
                channel = CHANNEL_OUTSIDE,
                visitId = "",
                label = obj.optString("outside_label").trim().ifBlank { DEFAULT_OUTSIDE_LABEL },
                confirmMessage = obj.optString("outside_confirm_message").trim()
                    .ifBlank { "آگهی بسته و از فهرست عمومی برداشته می‌شود. ادامه می‌دهید؟" },
            ),
        )
        val review = obj.optBoolean("review_required_if_outside", false)
        val visitRows = ArrayList<OwnerSoldFlow.VisitRow>()
        if (visits != null) {
            for (i in 0 until visits.length()) {
                val v = visits.optJSONObject(i) ?: continue
                val id = v.optString("visit_id").trim()
                if (id.isEmpty()) continue
                val name = v.optString("partner_name").trim().ifBlank { "بازاریاب وینور" }
                visitRows.add(
                    OwnerSoldFlow.VisitRow(
                        visitId = id,
                        name = name,
                        dateLabel = v.optString("visit_date_label").trim().ifBlank { v.optString("visit_date").trim() },
                        statusLabel = v.optString("status_label").trim(),
                        summary = v.optString("summary").trim()
                            .ifBlank { v.optString("confirm_message").trim() },
                    ),
                )
            }
        }
        val texts = HashMap(OwnerSoldFlow.DEFAULT_TEXTS)
        obj.optJSONObject("texts")?.let { t ->
            t.keys().forEach { k -> t.optString(k).trim().takeIf { it.isNotEmpty() }?.let { texts[k] = it } }
        }
        val channelRows = ArrayList<OwnerSoldFlow.ChannelOption>()
        obj.optJSONArray("channels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val value = c.optString("value").trim()
                if (value !in OwnerSoldFlow.CHANNELS) continue
                channelRows.add(
                    OwnerSoldFlow.ChannelOption(
                        value = value,
                        label = c.optString("label").trim().ifBlank { OwnerSoldFlow.defaultChannelLabel(value, texts) },
                        hint = c.optString("hint").trim(),
                        enabled = c.optBoolean("enabled", true),
                    ),
                )
            }
        }
        if (channelRows.isEmpty()) channelRows.addAll(OwnerSoldFlow.defaultChannels(visitRows.isNotEmpty(), texts))
        val sourceRows = ArrayList<OwnerSoldFlow.SourceOption>()
        obj.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val value = c.optString("value").trim()
                val label = c.optString("label").trim()
                if (value.isNotEmpty() && label.isNotEmpty()) sourceRows.add(OwnerSoldFlow.SourceOption(value, label))
            }
        }
        val ratingLabels = HashMap(OwnerSoldFlow.DEFAULT_RATING_LABELS)
        obj.optJSONObject("rating_labels")?.let { r ->
            r.keys().forEach { k -> k.toIntOrNull()?.let { n -> r.optString(k).trim().takeIf { it.isNotEmpty() }?.let { ratingLabels[n] = it } } }
        }
        return Options(
            code = obj.optString("code").trim(),
            question = obj.optString("question").trim().ifBlank { DEFAULT_QUESTION },
            sold = obj.optBoolean("sold", false),
            canMarkSold = obj.optBoolean("can_mark_sold", false),
            choices = choices,
            reviewRequiredIfOutside = review,
            reviewWarning = if (review) obj.optString("review_warning").trim().ifBlank { DEFAULT_REVIEW_WARNING } else "",
            title = obj.optString("title").trim(),
            texts = texts,
            channels = channelRows,
            sources = sourceRows.ifEmpty { OwnerSoldFlow.DEFAULT_SOURCES },
            visits = visitRows,
            outsideSummary = obj.optString("outside_summary").trim().ifBlank {
                obj.optString("outside_confirm_message").trim()
            },
            ratingLabels = ratingLabels,
            commentMax = obj.optInt("comment_max", OwnerSoldFlow.COMMENT_MAX).coerceIn(1, 2000),
            finalPriceMax = obj.optLong("final_price_max", OwnerSoldFlow.FINAL_PRICE_MAX).coerceAtLeast(1L),
        )
    }

    fun body(code: String, choice: Choice): JSONObject {
        val body = JSONObject()
            .put("code", code.trim())
            .put("channel", choice.channel)
        if (choice.visitId.isNotBlank()) body.put("visit_id", choice.visitId)
        return body
    }

    fun succeeded(post: JSONObject): Boolean {
        val status = if (post.has("http_status")) post.optInt("http_status") else 200
        return status in 200..299 && post.optBoolean("ok") && post.optBoolean("sold")
    }

    fun isWalletShort(post: JSONObject): Boolean =
        post.optString("error_code").trim() == "wallet_insufficient"

    /** «هنوز قطعی نشده»: سرور هیچ تغییری نداده است. */
    fun isNoop(post: JSONObject): Boolean = post.optBoolean("noop", false)

    data class Success(val title: String, val steps: List<String>)

    /** متن صفحهٔ موفقیت از سرور؛ سرور قدیمی فقط message دارد. */
    fun success(post: JSONObject): Success {
        val steps = ArrayList<String>()
        post.optJSONArray("next_steps")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { steps.add(it) }
        }
        if (steps.isEmpty()) post.optString("message").trim().takeIf { it.isNotEmpty() }?.let { steps.add(it) }
        return Success(post.optString("success_title").trim().ifBlank { "فروش ثبت شد" }, steps)
    }

    /** معامله با بازاریاب ثبت شد ولی پرداخت/بستن نشد؛ دوباره زدن «فروخته شد» ادامه می‌دهد. */
    fun dealStarted(post: JSONObject): Boolean = post.optBoolean("deal_started", false)

    fun message(post: JSONObject): String {
        if (succeeded(post)) return post.optString("message").trim().ifBlank { "فروش ثبت شد." }
        if (isNoop(post)) return post.optString("message").trim().ifBlank { OwnerSoldFlow.DEFAULT_TEXTS["not_final_message"].orEmpty() }
        val error = post.optString("error").trim()
        if (error.isNotEmpty() && error.any { it.code > 127 }) return error
        if (post.optBoolean("offline")) return "برای انجام این عملیات اینترنت لازم است."
        val status = post.optInt("http_status", 0)
        return when {
            status == 401 -> "برای این کار وارد شوید."
            status == 403 || post.optString("error_code") == "forbidden" ->
                "فقط مالک آگهی می‌تواند آن را فروخته‌شده اعلام کند."
            status == 404 -> "این آگهی پیدا نشد."
            else -> FAILURE
        }
    }

    /** کارت بعد از فروش موفق: بسته، بایگانی و بدون دکمهٔ «فروخته شد». */
    fun applyResult(snapshot: JSONObject, post: JSONObject): JSONObject {
        val next = ListingArchive.applyResult(snapshot, post)
        next.put("sold", true)
        next.put("can_mark_sold", false)
        next.put("can_restore", false)
        next.put("express_status", "sold")
        return next
    }
}
