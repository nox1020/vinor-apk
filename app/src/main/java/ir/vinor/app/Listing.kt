package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * یک آگهی، با همان معنی انتشار سرور.
 *
 * منتشر است اگر یکی از این‌ها روشن باشد: `extras.published_for_sale`،
 * `source=my_property_published`، یا `public` همراه `listed_by_owner`.
 * نوشتن وضعیت همهٔ همان پرچم‌ها را با هم عوض می‌کند.
 * `extras.previous_code` کد قبلی است وقتی سرور به‌خاطر مالک دوم کد تازه داده.
 */
object Listings {
    private val publishSources = setOf("my_property", "my_property_published", "owner_home")

    fun canonicalCode(raw: String): String {
        val out = StringBuilder()
        for (ch in raw.trim()) {
            when (ch) {
                in '۰'..'۹' -> out.append('0' + (ch - '۰'))
                in '٠'..'٩' -> out.append('0' + (ch - '٠'))
                else -> out.append(ch)
            }
        }
        return out.toString().trim()
    }

    fun codeOf(land: JSONObject): String {
        val code = text(land, "code")
        if (code.isNotBlank()) return canonicalCode(code)
        return canonicalCode(text(land, "id"))
    }

    fun previousCode(land: JSONObject): String {
        val extras = land.optJSONObject("extras")
        val fromExtras = if (extras == null) "" else text(extras, "previous_code")
        if (fromExtras.isNotBlank()) return canonicalCode(fromExtras)
        return canonicalCode(text(land, "previous_code"))
    }

    fun published(land: JSONObject): Boolean {
        val extras = land.optJSONObject("extras")
        if (extras != null && flagTrue(extras, "published_for_sale")) return true
        if (text(land, "source").equals("my_property_published", ignoreCase = true)) return true
        if (flagTrue(land, "public") && (flagTrue(extras, "listed_by_owner") || flagTrue(land, "listed_by_owner"))) {
            return true
        }
        if (flagTrue(land, "published_for_sale")) return true
        if (!hasPublishSignal(land) && text(land, "badge_label") == "منتشرشده") return true
        return false
    }

    fun statusLabel(land: JSONObject): String {
        val badge = text(land, "badge_label")
        if (!hasPublishSignal(land) && badge.isNotBlank()) return badge
        return if (published(land)) "منتشرشده" else "خصوصی"
    }

    fun hasPublishSignal(land: JSONObject): Boolean {
        if (land.has("published_for_sale") && !land.isNull("published_for_sale")) return true
        val extras = land.optJSONObject("extras")
        if (extras != null && extras.has("published_for_sale") && !extras.isNull("published_for_sale")) return true
        if (text(land, "source").lowercase() in publishSources) return true
        if (extras != null && (extras.has("listed_by_owner") || extras.has("owner_home"))) return true
        if (land.has("owner_home") && !land.isNull("owner_home")) return true
        if (text(land, "ad_type").equals("owner_home", ignoreCase = true)) return true
        return false
    }

    fun isMyProperty(land: JSONObject): Boolean {
        val extras = land.optJSONObject("extras")
        val source = text(land, "source").lowercase()
        if (source in publishSources) return true
        if (flagTrue(land, "owner_home") || flagTrue(extras, "owner_home") || flagTrue(extras, "listed_by_owner")) {
            return true
        }
        if (text(land, "ad_type").equals("owner_home", ignoreCase = true)) return true
        return false
    }

    /** ملک منِ منتشرنشده. فید عمومی این ردیف را نشان نمی‌دهد. */
    fun isPrivateOwnerHome(land: JSONObject): Boolean {
        if (published(land)) return false
        val extras = land.optJSONObject("extras")
        if (flagTrue(land, "owner_home") || flagTrue(extras, "owner_home") || flagTrue(extras, "listed_by_owner")) {
            return true
        }
        val source = text(land, "source").lowercase()
        if (source == "my_property" || source == "owner_home") return true
        if (text(land, "ad_type").equals("owner_home", ignoreCase = true)) return true
        return false
    }

    /** کد تازهٔ سرور را روی کارت قدیمی بنشان و کد قبلی را نگه دار. */
    fun adoptCode(snapshot: JSONObject, incoming: JSONObject) {
        val next = codeOf(incoming)
        if (next.isBlank()) return
        val prev = previousCode(incoming)
        val current = codeOf(snapshot)
        val renamed = prev.isNotBlank() && (current == prev || current == next || current.isBlank())
        if (!renamed && current.isNotBlank()) return
        snapshot.put("code", next)
        if (prev.isBlank()) return
        val extras = JSONObject(snapshot.optJSONObject("extras")?.toString() ?: "{}")
        extras.put("previous_code", prev)
        snapshot.put("extras", extras)
    }

    fun align(target: JSONObject, published: Boolean) {
        val extras = JSONObject(target.optJSONObject("extras")?.toString() ?: "{}")
        if (published) {
            extras.put("published_for_sale", true)
            extras.put("public", true)
            extras.put("owner_home", false)
            extras.put("listed_by_owner", true)
            extras.put("listed_by_special", true)
            target.put("extras", extras)
            target.put("published_for_sale", true)
            target.put("public", true)
            target.put("is_express", true)
            target.put("ad_type", "express")
            target.put("owner_home", false)
            target.put("source", "my_property_published")
            if (!text(target, "express_status").equals("sold", ignoreCase = true)) {
                target.put("express_status", "approved")
                val status = text(target, "status").lowercase()
                if (status.isEmpty() || status == "pending" || status == "hidden") {
                    target.put("status", "approved")
                }
            }
        } else {
            extras.put("published_for_sale", false)
            extras.put("public", false)
            extras.put("owner_home", true)
            extras.put("listed_by_owner", true)
            extras.put("listed_by_special", false)
            target.put("extras", extras)
            target.put("published_for_sale", false)
            target.put("public", false)
            target.put("is_express", false)
            target.put("ad_type", "owner_home")
            target.put("status", "pending")
            target.put("express_status", "hidden")
            target.put("owner_home", true)
            target.put("source", "my_property")
        }
        val badge = text(target, "badge_label")
        if (badge.isBlank() || badge == "منتشرشده" || badge == "خصوصی") {
            target.put("badge_label", if (published) "منتشرشده" else "خصوصی")
        }
    }

    fun flagTrue(obj: JSONObject?, key: String): Boolean {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return false
        return when (val value = obj.opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.trim().lowercase() in setOf("1", "true", "yes", "on")
            else -> false
        }
    }

    fun text(obj: JSONObject, key: String): String {
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

    /** کد را در فهرست عوض کن. کد تازه اگر هست تکرار نمی‌شود. */
    fun retargetCode(codes: List<String>, from: String, to: String): List<String> {
        val oldCode = canonicalCode(from)
        val newCode = canonicalCode(to)
        if (oldCode.isBlank() || newCode.isBlank() || oldCode == newCode) return codes
        val out = ArrayList<String>()
        for (code in codes) {
            val next = if (canonicalCode(code) == oldCode) newCode else code
            if (next.isNotBlank() && next !in out) out.add(next)
        }
        return out
    }
}

/**
 * تنها جای نگهداری آگهی در حافظهٔ فرایند.
 * صفحه‌ها از اینجا می‌خوانند و انتشار همین‌جا هر کپی را عوض می‌کند.
 */
object ListingRepository {
    private val byCode = LinkedHashMap<String, JSONObject>()
    private val alias = HashMap<String, String>()

    fun clear() {
        byCode.clear()
        alias.clear()
    }

    fun currentCode(code: String): String {
        val key = Listings.canonicalCode(code)
        if (key.isBlank()) return ""
        return alias[key] ?: key
    }

    fun ingest(raw: JSONObject) {
        val code = Listings.codeOf(raw)
        if (code.isBlank()) return
        val copy = JSONObject(raw.toString())
        if (Listings.hasPublishSignal(copy) || Listings.isMyProperty(copy)) {
            Listings.align(copy, Listings.published(raw))
        }
        byCode[code] = copy
        val previous = Listings.previousCode(copy)
        if (previous.isNotBlank() && previous != code) alias[previous] = code
    }

    fun ingestTree(node: Any?) {
        when (node) {
            is JSONObject -> {
                ingest(node)
                val keys = node.keys().asSequence().toList()
                for (key in keys) ingestTree(node.opt(key))
            }
            is JSONArray -> {
                for (i in 0 until node.length()) ingestTree(node.opt(i))
            }
        }
    }

    fun adopt(raw: JSONObject) {
        val code = Listings.codeOf(raw)
        if (code.isBlank()) return
        val copy = JSONObject(raw.toString())
        byCode[code] = copy
        val previous = Listings.previousCode(copy)
        if (previous.isNotBlank() && previous != code) alias[previous] = code
    }

    fun resolve(code: String): JSONObject? {
        val key = currentCode(code)
        if (key.isBlank()) return null
        val hit = byCode[key] ?: return null
        return JSONObject(hit.toString())
    }

    /** وضعیت انتشار همین کد را قطعی کن و نمونهٔ هم‌تراز را برگردان. */
    fun applyPublished(code: String, published: Boolean): JSONObject {
        val current = currentCode(code).ifBlank { Listings.canonicalCode(code) }
        val base = byCode[current]?.let { JSONObject(it.toString()) } ?: JSONObject().put("code", current)
        if (Listings.codeOf(base).isBlank()) base.put("code", current)
        Listings.align(base, published)
        byCode[Listings.codeOf(base)] = JSONObject(base.toString())
        val previous = Listings.previousCode(base)
        if (previous.isNotBlank()) alias[previous] = Listings.codeOf(base)
        if (current.isNotBlank() && current != Listings.codeOf(base)) alias[current] = Listings.codeOf(base)
        return JSONObject(base.toString())
    }

    /** هر شیئی که همین آگهی است پرچم‌های قطعی را می‌گیرد، شامل کد قبلی. */
    fun rewrite(node: Any?) {
        when (node) {
            is JSONObject -> {
                val canonical = canonicalFor(node)
                if (canonical != null) copyPublishState(canonical, node)
                val keys = node.keys().asSequence().toList()
                for (key in keys) rewrite(node.opt(key))
            }
            is JSONArray -> {
                for (i in 0 until node.length()) rewrite(node.opt(i))
            }
        }
    }

    private fun canonicalFor(node: JSONObject): JSONObject? {
        val code = Listings.codeOf(node)
        val previous = Listings.previousCode(node)
        val key = when {
            code.isNotBlank() && byCode.containsKey(currentCode(code)) -> currentCode(code)
            previous.isNotBlank() && alias.containsKey(previous) -> alias[previous].orEmpty()
            code.isNotBlank() && alias.containsKey(code) -> alias[code].orEmpty()
            else -> ""
        }
        if (key.isBlank()) return null
        return byCode[key]
    }

    private fun copyPublishState(from: JSONObject, onto: JSONObject) {
        listOf(
            "code",
            "published_for_sale",
            "public",
            "is_express",
            "source",
            "owner_home",
            "ad_type",
            "status",
            "express_status",
            "badge_label",
        ).forEach { key ->
            if (from.has(key)) onto.put(key, from.opt(key))
        }
        val fromExtras = from.optJSONObject("extras") ?: return
        val extras = JSONObject(onto.optJSONObject("extras")?.toString() ?: "{}")
        listOf(
            "published_for_sale",
            "public",
            "owner_home",
            "listed_by_owner",
            "listed_by_special",
            "previous_code",
        ).forEach { key ->
            if (fromExtras.has(key)) extras.put(key, fromExtras.opt(key))
        }
        onto.put("extras", extras)
    }
}
