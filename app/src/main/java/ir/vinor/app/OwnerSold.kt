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
        return Options(
            code = obj.optString("code").trim(),
            question = obj.optString("question").trim().ifBlank { DEFAULT_QUESTION },
            sold = obj.optBoolean("sold", false),
            canMarkSold = obj.optBoolean("can_mark_sold", false),
            choices = choices,
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

    fun message(post: JSONObject): String {
        if (succeeded(post)) return post.optString("message").trim().ifBlank { "فروش ثبت شد." }
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
        next.put("express_status", "sold")
        return next
    }
}
