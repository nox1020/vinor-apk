package ir.vinor.app

import org.json.JSONObject
import java.net.URLEncoder

/** منوی مدیریت ملک — قبل از جزئیات، به ترتیب دیوار. */
object MyPropertyManage {

    const val PAGE_TITLE = "مدیریت ملک"
    const val DELETE_PATH = "/express/partner/api/my-property/delete"
    const val SUBMIT_PATH = "/express/partner/api/my-property/submit"
    const val PUBLISH_PATH = "/express/partner/api/my-property/publish"
    /** همان مسیر انتشار؛ توقف با published_for_sale=false. مسیر جداگانه روی سرور نیست. */
    const val UNPUBLISH_PATH = "$PUBLISH_PATH?published_for_sale=0"

    enum class Action {
        VIEW,
        EDIT,
        PRICE,
        DELETE,
        SHARE,
        PUBLISH,
    }

    fun actions(): List<Action> = listOf(
        Action.VIEW,
        Action.EDIT,
        Action.PRICE,
        Action.DELETE,
        Action.SHARE,
        Action.PUBLISH,
    )

    fun label(action: Action, published: Boolean = false): String = when (action) {
        Action.VIEW -> "نمایش ملک"
        Action.EDIT -> "ویرایش"
        Action.PRICE -> "تعیین قیمت"
        Action.DELETE -> "حذف آگهی"
        Action.SHARE -> "اشتراک‌گذاری"
        Action.PUBLISH -> if (published) "توقف انتشار فروش" else "انتشار برای فروش"
    }

    fun codeOf(snapshot: JSONObject): String = text(snapshot, "code")

    fun titleOf(snapshot: JSONObject): String = text(snapshot, "title").ifBlank { "ملک شما" }

    fun priceOf(snapshot: JSONObject): Long {
        val total = longOf(snapshot, "price_total")
        if (total > 0L) return total
        return longOf(snapshot, "price_toman")
    }

    fun statusOf(snapshot: JSONObject): String {
        val badge = text(snapshot, "badge_label")
        if (badge.isNotBlank()) return badge
        return if (snapshot.optBoolean("published_for_sale")) "منتشرشده" else "خصوصی"
    }

    fun shareUrl(code: String): String {
        val encoded = URLEncoder.encode(code.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        return "${VinorPartnerUrls.BASE}/express/partner/lands/$encoded"
    }

    fun shareMessage(snapshot: JSONObject): String {
        val title = titleOf(snapshot)
        return "$title\n${shareUrl(codeOf(snapshot))}"
    }

    fun priceAmount(raw: String): Long = digits(raw).toLongOrNull() ?: 0L

    /** همان قرارداد ثبت ملک، با کد تا سرور همین آگهی را به‌روز کند. */
    fun priceBody(snapshot: JSONObject, price: Long): JSONObject {
        val body = JSONObject()
            .put("code", codeOf(snapshot))
            .put("price_total", price.toString())
            .put("payment_method", "توافقی")
            .put("express_commission_pct", "0")
        val size = longOf(snapshot, "size")
        if (size > 0L) {
            body.put("size", size.toString())
            body.put("price_per_meter", (price / size).toString())
        }
        listOf("title", "city", "location", "category", "description").forEach { key ->
            val value = text(snapshot, key)
            if (value.isNotBlank()) body.put(key, value)
        }
        return body
    }

    fun deleteBody(code: String): JSONObject = JSONObject().put("code", code.trim())

    /**
     * توقف همان مسیر انتشار است. سرور قبل از خواندن پرچم توقف، نحوهٔ پرداخت را اجباری می‌کند؛
     * بدون آن پاسخ «نحوه پرداخت را انتخاب کنید» می‌دهد و آگهی روشن می‌ماند.
     * روش و پورسانت فعلی ملک حفظ می‌شود تا شرایط فروش عوض نشود.
     */
    fun unpublishBody(code: String, snapshot: JSONObject? = null): JSONObject {
        return JSONObject()
            .put("code", code.trim())
            .put("payment_method", paymentOf(snapshot).ifBlank { "نقدی" })
            .put("commission_pct", commissionOf(snapshot).ifBlank { "2" })
            .put("auto_send_all_partners", false)
            .put("published_for_sale", false)
            .put("unpublish", true)
    }

    fun paymentOf(snapshot: JSONObject?): String {
        if (snapshot == null) return ""
        val direct = text(snapshot, "payment_method")
        if (isChosenPayment(direct)) return direct
        return rowValue(snapshot, "نحوه پرداخت").takeIf { isChosenPayment(it) }.orEmpty()
    }

    fun commissionOf(snapshot: JSONObject?): String {
        if (snapshot == null) return ""
        val direct = text(snapshot, "commission_pct")
        if (direct.isNotBlank()) return direct
        return text(snapshot, "express_commission_pct")
    }

    fun landDataPath(code: String): String {
        val encoded = URLEncoder.encode(code.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        return "/express/partner/api/lands/$encoded/data"
    }

    /** موفقیت فقط وقتی پاسخ انتشار قبول شود و جزئیات ملک دیگر منتشرشده نباشد. */
    fun unpublishStopped(post: JSONObject, land: JSONObject?): Boolean {
        val status = post.optInt("http_status", 200)
        if (status !in 200..299) return false
        if (!post.optBoolean("ok")) return false
        if (land == null || !land.has("published_for_sale")) return false
        val landStatus = land.optInt("http_status", 200)
        if (landStatus != 0 && landStatus !in 200..299) return false
        return !land.optBoolean("published_for_sale")
    }

    /** اگر پاسخ انتشار موفق باشد ولی ملک هنوز منتشر است، پیام موفقیت سرور نشان داده نمی‌شود. */
    fun unpublishFailure(post: JSONObject, fallback: String): String {
        if (post.optBoolean("ok")) return fallback
        return failureMessage(post, fallback)
    }

    fun publishBody(code: String, payment: String, commission: String, sendAll: Boolean): JSONObject {
        return JSONObject()
            .put("code", code.trim())
            .put("payment_method", payment.ifBlank { "نقدی" })
            .put("commission_pct", commission.trim().ifBlank { "2" })
            .put("auto_send_all_partners", sendAll)
    }

    fun mergeDetail(card: JSONObject, detail: JSONObject): JSONObject {
        if (!detail.optBoolean("ok") && text(detail, "title").isBlank()) return JSONObject(card.toString())
        val out = JSONObject(card.toString())
        listOf("title", "city", "location", "category", "description", "image_url", "size").forEach { key ->
            if (text(out, key).isBlank()) {
                val value = text(detail, key)
                if (value.isNotBlank()) out.put(key, value)
            }
        }
        if (priceOf(out) <= 0L) {
            val price = priceOf(detail)
            if (price > 0L) out.put("price_total", price)
        }
        if (text(out, "code").isBlank()) {
            val code = text(detail, "code")
            if (code.isNotBlank()) out.put("code", code)
        }
        if (detail.has("published_for_sale")) out.put("published_for_sale", detail.optBoolean("published_for_sale"))
        if (detail.has("can_publish")) out.put("can_publish", detail.optBoolean("can_publish"))
        if (paymentOf(out).isBlank()) {
            val pay = paymentOf(detail)
            if (pay.isNotBlank()) out.put("payment_method", pay)
        }
        if (commissionOf(out).isBlank()) {
            val commission = commissionOf(detail)
            if (commission.isNotBlank()) out.put("commission_pct", commission)
        }
        return out
    }

    fun failureMessage(obj: JSONObject, fallback: String): String {
        if (obj.optInt("http_status") == 404) return fallback
        val err = obj.optString("error").trim().ifBlank { obj.optString("message").trim() }
        if (err.isBlank() || err.equals("Not Found", ignoreCase = true)) return fallback
        return err
    }

    private fun isChosenPayment(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isNotBlank() && trimmed != "انتخاب کنید"
    }

    private fun rowValue(obj: JSONObject, label: String): String {
        val rows = obj.optJSONArray("rows") ?: return ""
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (text(row, "label") == label) return text(row, "value")
        }
        return ""
    }

    private fun text(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val raw = obj.opt(key) ?: return ""
        return when (raw) {
            is Number -> {
                val asLong = raw.toLong()
                if (asLong.toDouble() == raw.toDouble()) asLong.toString() else raw.toString()
            }
            else -> obj.optString(key).trim()
        }
    }

    private fun longOf(obj: JSONObject, key: String): Long = digits(text(obj, key)).toLongOrNull() ?: 0L

    private fun digits(raw: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        val ar = "٠١٢٣٤٥٦٧٨٩"
        val out = StringBuilder()
        for (ch in raw) {
            val i = fa.indexOf(ch)
            if (i >= 0) {
                out.append(i)
                continue
            }
            val j = ar.indexOf(ch)
            if (j >= 0) {
                out.append(j)
                continue
            }
            if (ch in '0'..'9') out.append(ch)
        }
        return out.toString()
    }
}
