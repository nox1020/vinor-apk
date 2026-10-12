package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** منوی مدیریت ملک — قبل از جزئیات، به ترتیب دیوار. */
object MyPropertyManage {

    const val PAGE_TITLE = "مدیریت ملک"
    const val PAGE_PATH = "/express/partner/my-property"
    const val PORTFOLIO_PATH = "/express/partner/api/my-property/data"
    const val SUBMIT_PATH = "/express/partner/api/my-property/submit"
    const val PUBLISH_PATH = "/express/partner/api/my-property/publish"
    /** حذف ملک من. بدنه JSON است: ``{"code": "..."}``. */
    const val DELETE_PATH = "/express/partner/api/my-property/delete"
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

    fun codeOf(snapshot: JSONObject): String {
        val code = text(snapshot, "code")
        if (code.isNotBlank()) return code
        return text(snapshot, "id")
    }

    fun titleOf(snapshot: JSONObject): String = text(snapshot, "title").ifBlank { "ملک شما" }

    fun priceOf(snapshot: JSONObject): Long {
        val total = longOf(snapshot, "price_total")
        if (total > 0L) return total
        val toman = longOf(snapshot, "price_toman")
        if (toman > 0L) return toman
        return longOf(snapshot, "price")
    }

    fun isPublished(snapshot: JSONObject): Boolean {
        if (snapshot.has("published_for_sale")) return snapshot.optBoolean("published_for_sale")
        return text(snapshot, "badge_label") == "منتشرشده"
    }

    fun statusOf(snapshot: JSONObject): String {
        if (snapshot.has("published_for_sale")) {
            return if (snapshot.optBoolean("published_for_sale")) "منتشرشده" else "خصوصی"
        }
        val badge = text(snapshot, "badge_label")
        if (badge.isNotBlank()) return badge
        return "خصوصی"
    }

    fun markPublished(snapshot: JSONObject, published: Boolean) {
        snapshot.put("published_for_sale", published)
        val badge = text(snapshot, "badge_label")
        if (badge.isBlank() || badge == "منتشرشده" || badge == "خصوصی") {
            snapshot.put("badge_label", if (published) "منتشرشده" else "خصوصی")
        }
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

    /**
     * همان قرارداد ثبت ملک، با کد تا سرور همین آگهی را به‌روز کند.
     * روش پرداخت و پورسانت فعلی حفظ می‌شود تا تعیین قیمت شرایط فروش را عوض نکند.
     */
    fun priceBody(snapshot: JSONObject, price: Long): JSONObject {
        val body = JSONObject()
            .put("code", codeOf(snapshot))
            .put("price_total", price.toString())
            .put("payment_method", paymentOf(snapshot).ifBlank { "توافقی" })
            .put("express_commission_pct", commissionOf(snapshot).ifBlank { "0" })
        val size = sizeOf(snapshot)
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

    /** بدنهٔ JSON حذف. فرم HTML صفحهٔ ملک فرستاده نمی‌شود. */
    fun deleteBody(code: String): JSONObject =
        JSONObject().put("code", code.trim())

    fun deleteSucceeded(post: JSONObject): Boolean {
        val status = post.optInt("http_status", 200)
        if (status !in 200..299) return false
        if (!post.optBoolean("ok")) return false
        return text(post, "code").isNotBlank()
    }

    /** ۴۰۴ یعنی این مسیر حذف برای این ملک نیست؛ دکمه پنهان می‌شود. */
    fun deleteUnavailable(post: JSONObject): Boolean = post.optInt("http_status") == 404

    /**
     * موفقیت فقط وقتی است که فهرست ملک من این کد را دیگر نداشته باشد.
     * پاسخ خام POST کافی نیست؛ ریدایرکت HTML هم نباید حذف‌نشده را موفق نشان دهد.
     */
    fun removedFromPortfolio(data: JSONObject, code: String): Boolean {
        val wanted = code.trim()
        if (wanted.isEmpty()) return false
        val status = data.optInt("http_status", 200)
        if (status !in 200..299) return false
        if (data.optBoolean("offline")) return false
        if (data.optString("error") == "unauthorized") return false
        val items = data.optJSONArray("items") ?: data.optJSONArray("listings")
        val property = data.optJSONObject("property")
        val shaped = items != null || property != null || data.has("has_property")
        if (!shaped) return false
        if (data.optBoolean("has_property") && property == null && items == null) return false
        return !portfolioContains(items, property, wanted)
    }

    fun deleteFailure(post: JSONObject, fallback: String): String {
        if (post.optBoolean("ok") || post.optBoolean("success")) return fallback
        val err = post.optString("error").trim()
        if (err.isBlank() || err == "پاسخ نامعتبر" || err.equals("Not Found", ignoreCase = true)) {
            return fallback
        }
        return failureMessage(post, fallback)
    }

    /**
     * توقف همان مسیر انتشار است و پرچم توقف را می‌فرستد.
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

    /** ویرایش ملک منتشرشده نباید روش پرداخت را به توافقی برگرداند. */
    fun editPayment(snapshot: JSONObject?, selected: String, publicListing: Boolean): String {
        val chosen = selected.trim()
        if (isChosenPayment(chosen)) return chosen
        val known = paymentOf(snapshot)
        if (known.isNotBlank()) return known
        return if (publicListing) "" else "توافقی"
    }

    /** ویرایش نباید پورسانت ثبت‌شده را صفر کند. */
    fun editCommission(snapshot: JSONObject?, typed: String, publicListing: Boolean): String {
        val entered = typed.trim()
        if (publicListing && entered.isNotBlank()) return entered
        val known = commissionOf(snapshot)
        if (known.isNotBlank()) return known
        return if (publicListing) "" else "0"
    }

    fun sizeOf(snapshot: JSONObject): Long {
        val direct = longOf(snapshot, "size")
        if (direct > 0L) return direct
        return digits(rowValue(snapshot, "متراژ")).toLongOrNull() ?: 0L
    }

    fun imageOf(snapshot: JSONObject): String {
        listOf("image_url", "image_full", "image_thumb", "image_raw", "cover_image").forEach { key ->
            val value = text(snapshot, key)
            if (value.isNotBlank() && value != "null") return value
        }
        val images = snapshot.optJSONArray("images") ?: return ""
        val first = images.opt(0) ?: return ""
        return when (first) {
            is String -> first.trim()
            is JSONObject -> text(first, "url").ifBlank { text(first, "src") }
            else -> ""
        }
    }

    fun landDataPath(code: String): String {
        val encoded = URLEncoder.encode(code.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        return "/express/partner/api/lands/$encoded/data"
    }

    /** موفقیت فقط وقتی پاسخ انتشار قبول شود و جزئیات تازهٔ ملک دیگر منتشرشده نباشد. */
    fun unpublishStopped(post: JSONObject, land: JSONObject?): Boolean {
        val status = post.optInt("http_status", 200)
        if (status !in 200..299) return false
        if (!post.optBoolean("ok")) return false
        if (freshLandSays(land, published = false)) return true
        if (freshLandSays(land, published = true)) return false
        return postProvesStopped(post)
    }

    /**
     * کش جزئیاتِ قبل از توقف، یا ۴۰۴ بعد از خصوصی شدن، نباید موفقیت را پس بگیرد.
     * جزئیات تازه اگر هنوز منتشر باشد ملاک است.
     */
    fun postProvesStopped(post: JSONObject): Boolean {
        val code = text(post, "code")
        if (code.isBlank()) return false
        listOf(post.optJSONArray("items"), post.optJSONArray("listings")).forEach { rows ->
            if (rows == null) return@forEach
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (codeOf(row) != code || !row.has("published_for_sale")) continue
                return !row.optBoolean("published_for_sale")
            }
        }
        val property = post.optJSONObject("property")
        if (property != null && codeOf(property) == code && property.has("published_for_sale")) {
            return !property.optBoolean("published_for_sale")
        }
        return false
    }

    private fun freshLandSays(land: JSONObject?, published: Boolean): Boolean {
        if (land == null || land.optBoolean("from_cache")) return false
        if (!land.has("published_for_sale")) return false
        val landStatus = land.optInt("http_status", 200)
        if (landStatus != 0 && landStatus !in 200..299) return false
        return land.optBoolean("published_for_sale") == published
    }

    /**
     * پاسخ موفق کافی نیست اگر جزئیات تازه هنوز خصوصی باشد.
     * کش قبل از انتشار، مثل توقف، نباید موفقیت را پس بگیرد.
     */
    fun publishAccepted(post: JSONObject, land: JSONObject?): Boolean {
        val status = post.optInt("http_status", 200)
        if (status !in 200..299) return false
        if (!post.optBoolean("ok")) return false
        if (freshLandSays(land, published = true)) return true
        if (freshLandSays(land, published = false)) return false
        return true
    }

    /** اگر پاسخ سرور موفق باشد ولی وضعیت ملک عوض نشده، پیام موفقیت نشان داده نمی‌شود. */
    fun unpublishFailure(post: JSONObject, fallback: String): String {
        if (post.optBoolean("ok")) return fallback
        return failureMessage(post, fallback)
    }

    /** پاسخ «قبلاً منتشر شده» یعنی آگهی روی سرور روشن است و صفحه باید همان را نشان دهد. */
    fun absorbAlreadyPublished(snapshot: JSONObject, post: JSONObject): Boolean {
        if (post.optString("error_code") != "already_published") return false
        markPublished(snapshot, true)
        return true
    }

    fun detailAccepted(obj: JSONObject): Boolean {
        if (obj.optBoolean("offline") || obj.optBoolean("non_json")) return false
        if (obj.optBoolean("ok") || text(obj, "title").isNotBlank()) return true
        return obj.has("published_for_sale")
    }

    /**
     * جزئیات کامل ادغام می‌شود. اگر فقط پرچم انتشار آمده باشد — حتی با ok:false —
     * همان پرچم و can_publish روی کارت می‌نشیند و بقیهٔ فیلدها دست نمی‌خورند.
     */
    fun applyListingDetail(card: JSONObject, detail: JSONObject): JSONObject {
        if (detail.optBoolean("offline") || detail.optBoolean("non_json")) return JSONObject(card.toString())
        val status = detail.optInt("http_status", 200)
        if (status != 0 && status !in 200..299) return JSONObject(card.toString())
        val full = detail.optBoolean("ok") || text(detail, "title").isNotBlank()
        if (full) return mergeDetail(card, detail)
        if (!detail.has("published_for_sale")) return JSONObject(card.toString())
        val out = JSONObject(card.toString())
        val published = detail.optBoolean("published_for_sale")
        out.put("published_for_sale", published)
        if (detail.has("can_publish")) out.put("can_publish", detail.optBoolean("can_publish"))
        markPublished(out, published)
        return out
    }

    /** توقف موفق، عکس کارت را برای ساخت دوبارهٔ صفحه برمی‌گرداند. */
    fun finishUnpublish(snapshot: JSONObject, post: JSONObject, land: JSONObject?): JSONObject? {
        if (!unpublishStopped(post, land)) return null
        val next = if (land != null) applyListingDetail(snapshot, land) else JSONObject(snapshot.toString())
        markPublished(next, false)
        return next
    }

    fun commitSnapshot(snapshot: JSONObject): String = snapshot.toString()

    fun readSnapshot(raw: String?): JSONObject =
        runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: JSONObject()

    /** ویرایش ذخیره‌شده روی همان کارت مدیریت می‌نشیند تا بازگشت، وضعیت کهنه نیاورد. */
    fun applySavedEdit(snapshot: JSONObject, body: JSONObject): JSONObject {
        val out = JSONObject(snapshot.toString())
        listOf("title", "city", "location", "category", "description", "payment_method").forEach { key ->
            val value = text(body, key)
            if (value.isNotBlank()) out.put(key, value)
        }
        val price = priceOf(body)
        if (price > 0L) out.put("price_total", price)
        val size = sizeOf(body)
        if (size > 0L) out.put("size", size.toString())
        val code = text(body, "code")
        if (code.isNotBlank()) out.put("code", code)
        return out
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
        if (text(out, "size").isBlank()) {
            val size = sizeOf(detail)
            if (size > 0L) out.put("size", size.toString())
        }
        if (imageOf(out).isBlank()) {
            val image = imageOf(detail)
            if (image.isNotBlank()) out.put("image_url", image)
        }
        return out
    }

    fun failureMessage(obj: JSONObject, fallback: String): String {
        if (obj.optInt("http_status") == 404) return fallback
        val err = obj.optString("error").trim().ifBlank { obj.optString("message").trim() }
        if (err.isBlank() || err.equals("Not Found", ignoreCase = true)) return fallback
        return err
    }

    private fun portfolioContains(items: JSONArray?, property: JSONObject?, code: String): Boolean {
        if (items != null) {
            for (i in 0 until items.length()) {
                val row = items.optJSONObject(i) ?: continue
                if (codeOf(row) == code) return true
            }
        }
        if (property != null && !property.optBoolean("is_demo") && codeOf(property) == code) return true
        return false
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
