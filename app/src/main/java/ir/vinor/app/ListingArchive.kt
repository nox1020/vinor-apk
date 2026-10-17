package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * بایگانی آگهی مالک. سرور حذف را هم به بایگانی تبدیل کرده؛ اپ دیگر آن مسیر را صدا نمی‌زند.
 * پیش‌فرض فهرست، فعال است.
 */
object ListingArchive {
    const val ACTIVE = "active"
    const val ARCHIVED = "archived"
    const val PORTFOLIO_PATH = "/express/partner/api/my-property/data"
    const val LISTINGS_PATH = "/express/partner/api/listings/data"
    const val ARCHIVE_PATH = "/express/partner/api/my-property/archive"
    const val RESTORE_PATH = "/express/partner/api/my-property/restore"
    const val BADGE = "بسته"
    const val ARCHIVE_LABEL = "بایگانی"
    const val RESTORE_LABEL = "بازگردوندن به فعال"
    const val EMPTY_NONE = "هنوز آگهی‌ای ثبت نکرده‌اید"
    const val EMPTY_ACTIVE = "آگهی فعالی نیست."
    const val EMPTY_ARCHIVED = "آگهی بایگانی‌شده‌ای نیست."

    enum class Outcome { OK, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, OFFLINE, FAILED }

    data class Counts(val active: Int, val archived: Int)

    data class Tab(val id: String, val title: String, val count: Int)

    data class Page(
        val status: String,
        val activeCount: Int,
        val archivedCount: Int,
        val items: List<JSONObject>,
        val emptyMessage: String,
        val showRegister: Boolean,
    )

    fun normalize(status: String?): String =
        if (status?.trim()?.equals(ARCHIVED, ignoreCase = true) == true) ARCHIVED else ACTIVE

    fun portfolioPath(status: String = ACTIVE): String = withStatus(PORTFOLIO_PATH, status)

    fun listingsPath(status: String = ACTIVE): String = withStatus(LISTINGS_PATH, status)

    fun cachePaths(): List<String> = listOf(
        PORTFOLIO_PATH,
        portfolioPath(ACTIVE),
        portfolioPath(ARCHIVED),
        LISTINGS_PATH,
        listingsPath(ACTIVE),
        listingsPath(ARCHIVED),
    )

    fun path(restore: Boolean): String = if (restore) RESTORE_PATH else ARCHIVE_PATH

    fun body(code: String): JSONObject = JSONObject().put("code", code.trim())

    fun actionLabel(archived: Boolean): String = if (archived) RESTORE_LABEL else ARCHIVE_LABEL

    fun tabs(active: Int, archived: Int): List<Tab> = listOf(
        Tab(ACTIVE, "فعال", active),
        Tab(ARCHIVED, "بایگانی‌شده", archived),
    )

    fun isArchived(item: JSONObject): Boolean {
        if (item.optBoolean("is_archived")) return true
        return when (val flag = item.opt("archived")) {
            is Boolean -> flag
            is Number -> flag.toInt() != 0
            is String -> flag.trim().lowercase() in setOf("1", "true", "yes", "on")
            else -> false
        }
    }

    fun counts(payload: JSONObject): Counts {
        val obj = payload.optJSONObject("counts") ?: JSONObject()
        return Counts(obj.optInt("active"), obj.optInt("archived"))
    }

    fun statusOf(payload: JSONObject): String = normalize(payload.optString("status"))

    /** فقط ردیف‌های همان نما. پرچم ناهماهنگ از کش کهنه نشان داده نمی‌شود. */
    fun visible(payload: JSONObject, status: String = ACTIVE): List<JSONObject> {
        val wantArchived = normalize(status) == ARCHIVED
        return rawItems(payload).filter { isArchived(it) == wantArchived }
    }

    fun page(payload: JSONObject, requested: String = ACTIVE): Page {
        val status = normalize(requested)
        val counts = counts(payload)
        val items = visible(payload, status)
        val any = payload.optBoolean("has_property") || counts.active + counts.archived > 0
        val empty = when {
            !any -> EMPTY_NONE
            status == ARCHIVED -> EMPTY_ARCHIVED
            else -> EMPTY_ACTIVE
        }
        return Page(
            status = status,
            activeCount = counts.active,
            archivedCount = counts.archived,
            items = items,
            emptyMessage = empty,
            showRegister = !any,
        )
    }

    fun outcome(post: JSONObject): Outcome {
        if (post.optBoolean("offline")) return Outcome.OFFLINE
        val status = if (post.has("http_status")) post.optInt("http_status") else 200
        val code = post.optString("error_code").trim()
        val error = post.optString("error").trim()
        if (status == 401 || error == "unauthorized") return Outcome.UNAUTHORIZED
        if (status == 403 || code == "forbidden") return Outcome.FORBIDDEN
        if (status == 404 || code == "not_found") return Outcome.NOT_FOUND
        if (status in 200..299 && post.optBoolean("ok")) return Outcome.OK
        return Outcome.FAILED
    }

    fun message(post: JSONObject, fallback: String): String {
        val error = post.optString("error").trim()
        if (error.isNotEmpty() && error.any { it.code > 127 }) return error
        val note = post.optString("message").trim()
        if (note.isNotEmpty() && !note.equals("Not Found", ignoreCase = true) && outcome(post) == Outcome.OK) {
            return note
        }
        return when (outcome(post)) {
            Outcome.UNAUTHORIZED -> "برای این کار وارد شوید."
            Outcome.FORBIDDEN -> "این آگهی برای شما نیست."
            Outcome.NOT_FOUND -> "این آگهی پیدا نشد."
            Outcome.OFFLINE -> "برای انجام این عملیات اینترنت لازم است."
            Outcome.OK -> note.ifBlank { fallback }
            Outcome.FAILED -> fallback
        }
    }

    fun applyResult(snapshot: JSONObject, post: JSONObject): JSONObject {
        val next = JSONObject(snapshot.toString())
        val archived = isArchived(post)
        next.put("archived", archived)
        next.put("is_archived", archived)
        next.put("archived_at", post.optString("archived_at"))
        next.put("archived_reason", if (archived) post.optString("archived_reason") else "")
        return next
    }

    private fun withStatus(path: String, status: String): String =
        "$path?status=${normalize(status)}"

    private fun rawItems(payload: JSONObject): List<JSONObject> {
        val array = payload.optJSONArray("items") ?: payload.optJSONArray("listings") ?: JSONArray()
        val out = ArrayList<JSONObject>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            out.add(row)
        }
        return out
    }
}
