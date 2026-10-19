package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * عکس سرصفحه همان تصویر اول آرایهٔ images است.
 * سرور فعلی فیلد جدا ندارد و partner_card_image_url اولین تصویر ذخیره‌شده را نشان می‌دهد.
 */
object CoverPhotos {
    const val MAX = 12

    /** جزئیات آگهی بیش از این تعداد تصویر برنمی‌گرداند. */
    const val DETAIL_CAP = 8

    const val LABEL = "سرصفحه"

    data class Slot(val path: String, val url: String, val name: String)

    fun slotsFrom(obj: JSONObject?): List<Slot> {
        if (obj == null) return emptyList()
        val out = mutableListOf<Slot>()
        val images = obj.optJSONArray("images")
        if (images != null) {
            for (i in 0 until images.length()) {
                val slot = slotOf(images.opt(i)) ?: continue
                if (out.none { it.path == slot.path }) out.add(slot)
            }
        }
        if (out.isEmpty()) {
            listOf("image_url", "image_full", "image_thumb", "image_raw", "cover_image").forEach { key ->
                val slot = slotOf(if (obj.has(key)) obj.opt(key) else null) ?: return@forEach
                if (out.none { it.path == slot.path }) out.add(slot)
            }
        }
        return out
    }

    fun withCover(slots: List<Slot>, index: Int): List<Slot> {
        if (index !in slots.indices || index == 0) return slots
        val next = slots.toMutableList()
        val chosen = next.removeAt(index)
        next.add(0, chosen)
        return next
    }

    fun paths(slots: List<Slot>): JSONArray {
        val arr = JSONArray()
        slots.forEach { slot ->
            if (slot.path.isNotBlank()) arr.put(slot.path)
        }
        return arr
    }

    fun displayArray(slots: List<Slot>): JSONArray {
        val arr = JSONArray()
        slots.forEach { slot ->
            val url = slot.url.ifBlank { displayUrl(slot.path) }
            if (url.isNotBlank()) arr.put(url)
        }
        return arr
    }

    /** ثبت تازه همیشه تصاویر را می‌فرستد. ویرایش فقط وقتی کاربر عکس را عوض کرده و لیست خالی نیست. */
    fun includeOnSubmit(editing: Boolean, changed: Boolean, count: Int): Boolean {
        if (!editing) return true
        return changed && count > 0
    }

    fun atDetailCap(count: Int): Boolean = count >= DETAIL_CAP

    /**
     * کارت لیست فقط جلد را دارد. جزئیات، اگر بلندتر یا هم‌اندازه باشد، گالری قابل‌ویرایش را جایگزین می‌کند.
     */
    fun adoptGallery(card: JSONObject, detail: JSONObject) {
        val incoming = slotsFrom(detail)
        if (incoming.isEmpty()) return
        val current = slotsFrom(card)
        if (current.isNotEmpty() && incoming.size < current.size) return
        card.put("images", displayArray(incoming))
    }

    fun storagePath(raw: String): String {
        var s = raw.trim()
        if (s.isBlank() || s == "null") return ""
        val query = s.indexOf('?')
        if (query >= 0) s = s.substring(0, query)
        val hash = s.indexOf('#')
        if (hash >= 0) s = s.substring(0, hash)
        s = s.trim()
        if (s.isBlank() || s == "null") return ""
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (s.startsWith("/uploads/")) return s.removePrefix("/")
        return s.removePrefix("/")
    }

    fun displayUrl(raw: String): String {
        val s = raw.trim()
        if (s.isBlank() || s == "null") return ""
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("/")) return s
        val path = storagePath(s)
        return if (path.startsWith("uploads/")) "/$path" else s
    }

    private fun slotOf(raw: Any?): Slot? {
        if (raw == null || raw === JSONObject.NULL) return null
        return when (raw) {
            is JSONObject -> {
                val pathRaw = firstText(raw, "path", "src", "url")
                val path = storagePath(pathRaw)
                if (path.isBlank()) null
                else {
                    val urlRaw = firstText(raw, "url", "src", "path")
                    val name = firstText(raw, "name").ifBlank { nameOf(path) }
                    Slot(path, displayUrl(urlRaw.ifBlank { path }), name)
                }
            }
            is String -> fromText(raw)
            else -> fromText(raw.toString())
        }
    }

    private fun fromText(raw: String): Slot? {
        val path = storagePath(raw)
        if (path.isBlank()) return null
        return Slot(path, displayUrl(raw), nameOf(path))
    }

    private fun firstText(obj: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            if (!obj.has(key) || obj.isNull(key)) return@forEach
            val value = obj.optString(key).trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun nameOf(path: String): String {
        val leaf = path.substringAfterLast('/').substringBefore('?')
        return leaf.ifBlank { "image" }
    }
}
