package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * قرارداد زندهٔ مشتریان: فقط `GET/POST /express/partner/customers`.
 * فهرست و فرم از همان HTML لاگین‌شده خوانده می‌شود؛ مسیر JSON جدا وجود ندارد.
 * POST با X-CSRFToken و Referer همین صفحه است.
 */
object PartnerCustomers {
    const val ORIGIN = "https://vinor.ir"
    const val REFERER_PATH = PartnerCrm.PATH

    data class Field(
        val name: String,
        val label: String,
        val multiline: Boolean,
        val phone: Boolean,
        val required: Boolean,
    )

    data class Row(
        val id: String,
        val title: String,
        val phone: String,
        val notes: String,
        val status: String,
    )

    data class Tab(val label: String, val href: String, val selected: Boolean)

    data class Search(val name: String, val placeholder: String)

    data class Page(
        val rows: List<Row>,
        val fields: List<Field>,
        val tabs: List<Tab>,
        val search: Search?,
        val emptyText: String,
        val submitLabel: String,
        val actionPath: String,
        val canDelete: Boolean,
        val deleteIdKey: String,
    )

    fun isLoginDocument(finalUrl: String, html: String): Boolean {
        val url = finalUrl.lowercase()
        if (url.contains("/express/partner/login")) return true
        val head = html.take(800).lowercase()
        return head.contains("redirecting") && html.contains("/express/partner/login")
    }

    fun csrfFromHtml(html: String): String {
        val tagged = Regex(
            """<input\b[^>]*name\s*=\s*["']csrf_token["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.value.orEmpty()
        val value = attr(tagged, "value")
        if (value.isNotBlank()) return value
        val meta = Regex(
            """<meta\b[^>]*name\s*=\s*["']csrf-token["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.value.orEmpty()
        return attr(meta, "content")
    }

    fun parse(html: String): Page {
        val fields = fieldsFromInputs(html).ifEmpty { fieldsFromScript(html) }
        val cards = rowsFromMarkup(html)
        val rows = if (cards.isNotEmpty()) cards else rowsFromJson(html)
        val deleteKey = deleteIdKey(html)
        return Page(
            rows = rows,
            fields = fields,
            tabs = tabsFrom(html),
            search = searchFrom(html),
            emptyText = emptyText(html, rows.isEmpty()),
            submitLabel = submitLabel(html),
            actionPath = actionPath(html),
            canDelete = deleteKey.isNotBlank() && rows.any { it.id.isNotBlank() },
            deleteIdKey = deleteKey,
        )
    }

    fun fieldError(field: Field, raw: String): String? {
        val value = if (field.phone) normalizePhone(raw) else raw.trim()
        if (value.isEmpty()) {
            return if (field.required) "${field.label} را وارد کنید." else null
        }
        if (field.phone && !value.matches(Regex("09[0-9]{9}"))) {
            return "شماره موبایل را درست وارد کنید."
        }
        return null
    }

    fun createBody(values: Map<String, String>, fields: List<Field>): JSONObject {
        val body = JSONObject()
        for (field in fields) {
            val raw = values[field.name].orEmpty()
            val value = if (field.phone) normalizePhone(raw) else raw.trim()
            if (value.isEmpty()) continue
            body.put(field.name, value)
        }
        return body
    }

    fun deleteBody(id: String, idKey: String): JSONObject {
        val body = JSONObject()
        val trimmed = id.trim()
        val number = trimmed.toLongOrNull()
        if (number != null && trimmed.all { it.isDigit() }) body.put(idKey, number)
        else body.put(idKey, trimmed)
        return body
    }

    fun isCsrfExpired(payload: JSONObject): Boolean = payload.optString("error") == "csrf_expired"

    fun isUnauthorized(payload: JSONObject): Boolean {
        if (payload.optInt("http_status") == 401) return true
        return payload.optString("error") == "unauthorized"
    }

    fun isOk(payload: JSONObject): Boolean {
        val code = payload.optInt("http_status", 200)
        if (code !in 200..299) return false
        if (isCsrfExpired(payload) || isUnauthorized(payload)) return false
        if (payload.has("success") && !payload.optBoolean("success")) return false
        if (payload.has("ok") && !payload.optBoolean("ok")) return false
        return true
    }

    fun userMessage(payload: JSONObject, fallback: String): String {
        val message = payload.optString("message").trim()
        if (message.isNotEmpty() && payload.optString("error") != "csrf_expired") return message
        val fields = payload.optJSONObject("fields")
        if (fields != null) {
            val keys = fields.keys()
            while (keys.hasNext()) {
                val text = fields.optString(keys.next()).trim()
                if (text.isNotEmpty()) return text
            }
        }
        val error = payload.optString("error").trim()
        if (error == "unauthorized") return "برای دیدن مشتریان باید وارد حساب شوید."
        if (error.isNotEmpty() && error != "csrf_expired" && error != "Not Found") return error
        if (payload.optInt("http_status") == 404) return "این صفحه پیدا نشد."
        return fallback
    }

    fun normalizePhone(raw: String): String {
        var digits = PartnerApi.asciiDigits(raw)
        if (digits.startsWith("0098")) digits = digits.drop(4)
        if (digits.startsWith("98") && digits.length >= 12) digits = digits.drop(2)
        if (digits.length == 10 && digits.startsWith("9")) digits = "0$digits"
        return digits
    }

    private fun fieldsFromInputs(html: String): List<Field> {
        val out = ArrayList<Field>()
        val seen = HashSet<String>()
        val tags = Regex(
            """<(input|textarea|select)\b([^>]*)/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (tag in tags.findAll(html)) {
            val kind = tag.groupValues[1].lowercase()
            val attrs = tag.groupValues[2]
            val name = attr(attrs, "name")
            if (!usableName(name) || !seen.add(name)) continue
            val type = attr(attrs, "type").lowercase()
            if (kind == "input" && type in setOf("hidden", "submit", "button", "checkbox", "radio", "file", "image")) {
                continue
            }
            if (isSearch(name, type, attr(attrs, "placeholder"))) continue
            val label = labelFor(html, name, attrs)
            out.add(
                Field(
                    name = name,
                    label = label,
                    multiline = kind == "textarea" || name in setOf("notes", "note", "description"),
                    phone = type == "tel" || name.contains("phone") || name.contains("mobile"),
                    required = Regex("""\brequired\b""", RegexOption.IGNORE_CASE).containsMatchIn(attrs),
                ),
            )
        }
        return out
    }

    private fun fieldsFromScript(html: String): List<Field> {
        val keys = keysNear(html, PartnerCrm.PATH)
        val out = ArrayList<Field>()
        for (key in keys) {
            if (!usableName(key) || key in jsNoise) continue
            val label = LABELS[key] ?: continue
            if (out.any { it.name == key }) continue
            out.add(
                Field(
                    name = key,
                    label = label,
                    multiline = key in setOf("notes", "note", "description"),
                    phone = key.contains("phone") || key.contains("mobile"),
                    required = key.contains("phone") ||
                        key == "customer_first_name" ||
                        key == "first_name" ||
                        key == "name" ||
                        key == "full_name",
                ),
            )
        }
        return out
    }

    private fun rowsFromMarkup(html: String): List<Row> {
        val visible = html
            .replace(Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        val out = ArrayList<Row>()
        val seen = HashSet<String>()
        fun take(tag: String, attrs: String, inner: String) {
            val cls = attr(attrs, "class")
            val customerish = cls.contains("customer", true) ||
                attrs.contains("data-customer", true) ||
                attr(attrs, "data-customer-id").isNotBlank()
            if (tag == "div" && !customerish) return
            if (tag == "tr" && !customerish && phoneIn(inner).isEmpty()) return
            if (tag != "tr" && !customerish && !inner.contains("tel:", true) && phoneIn(inner).isEmpty()) return
            if (skipClass(cls)) return
            val row = rowFrom(attrs, inner) ?: return
            if (seen.add(row.id + "|" + row.phone + "|" + row.title)) out.add(row)
        }
        val block = Regex(
            """<(article|li|tr)\b([^>]*)>(.*?)</\1>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (match in block.findAll(visible)) {
            take(match.groupValues[1].lowercase(), match.groupValues[2], match.groupValues[3])
        }
        val cards = Regex(
            """<div\b([^>]*\bclass\s*=\s*["'][^"']*(?:card|item|row)[^"']*["'][^>]*)>(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (match in cards.findAll(visible)) {
            take("div", match.groupValues[1], match.groupValues[2])
        }
        return out
    }

    private fun rowFrom(attrs: String, inner: String): Row? {
        val id = attr(attrs, "data-customer-id").ifBlank { attr(attrs, "data-id") }.ifBlank {
            val raw = attr(attrs, "id")
            raw.filter { it.isDigit() }
        }
        val phone = phoneIn(inner).ifBlank { normalizePhone(classText(inner, "phone")) }
        val title = classText(inner, "name").ifBlank { classText(inner, "title") }.ifBlank {
            plainLines(inner).firstOrNull { line ->
                normalizePhone(line) != phone && line != classText(inner, "status") && line.length > 1
            }.orEmpty()
        }
        if (title.isBlank() && phone.isBlank()) return null
        if (title == "مشتریان" && phone.isBlank()) return null
        return Row(
            id = id,
            title = title.ifBlank { phone },
            phone = phone,
            notes = classText(inner, "note").ifBlank { classText(inner, "desc") },
            status = classText(inner, "status").ifBlank { classText(inner, "badge") },
        )
    }

    private fun rowsFromJson(html: String): List<Row> {
        val out = ArrayList<Row>()
        val arrays = Regex(""""(customers|items|rows|results)"\s*:\s*(\[(?:[^\[\]]|\[[^\[\]]*\])*\])""")
        for (match in arrays.findAll(html)) {
            val arr = try {
                JSONArray(match.groupValues[2])
            } catch (_: Exception) {
                continue
            }
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val phone = normalizePhone(
                    firstString(obj, "phone", "customer_phone", "mobile", "tel"),
                )
                val first = firstString(obj, "customer_first_name", "first_name")
                val last = firstString(obj, "customer_last_name", "last_name")
                val combined = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
                val title = firstString(obj, "name", "full_name", "title", "customer_name").ifBlank { combined }
                if (title.isBlank() && phone.isBlank()) continue
                out.add(
                    Row(
                        id = firstString(obj, "id", "customer_id"),
                        title = title.ifBlank { phone },
                        phone = phone,
                        notes = firstString(obj, "notes", "note", "description"),
                        status = firstString(obj, "status", "state", "status_label"),
                    ),
                )
            }
        }
        return out
    }

    private fun tabsFrom(html: String): List<Tab> {
        val out = ArrayList<Tab>()
        val links = Regex(
            """<a\b([^>]*href\s*=\s*["']([^"']*customers[^"']*)["'][^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (link in links.findAll(html)) {
            val attrs = link.groupValues[1]
            val href = link.groupValues[2].replace("&amp;", "&")
            if (!href.contains("tab=") && !attr(attrs, "class").contains("tab", true)) continue
            val label = plainLines(link.groupValues[3]).firstOrNull().orEmpty()
            if (label.isBlank()) continue
            val selected = attr(attrs, "class").contains("active", true) ||
                attrs.contains("aria-current", true)
            out.add(Tab(label, href, selected))
        }
        return out
    }

    private fun searchFrom(html: String): Search? {
        val tags = Regex("""<input\b([^>]*)>""", RegexOption.IGNORE_CASE)
        for (tag in tags.findAll(html)) {
            val attrs = tag.groupValues[1]
            val name = attr(attrs, "name")
            val type = attr(attrs, "type").lowercase()
            val placeholder = attr(attrs, "placeholder")
            if (!isSearch(name, type, placeholder)) continue
            return Search(name.ifBlank { "q" }, placeholder.ifBlank { "جستجو" })
        }
        return null
    }

    private fun emptyText(html: String, noRows: Boolean): String {
        if (!noRows) return ""
        val node = Regex(
            """<[^>]*class\s*=\s*["'][^"']*empty[^"']*["'][^>]*>(.*?)</""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)
        val text = node?.let { plainLines(it.groupValues[1]).firstOrNull() }.orEmpty()
        return text.ifBlank { "مشتری‌ای ثبت نشده." }
    }

    private fun submitLabel(html: String): String {
        val button = Regex(
            """<button\b([^>]*)>(.*?)</button>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (match in button.findAll(html)) {
            val attrs = match.groupValues[1]
            val type = attr(attrs, "type").lowercase()
            if (type.isNotEmpty() && type != "submit") continue
            val text = plainLines(match.groupValues[2]).firstOrNull().orEmpty()
            if (text.isNotBlank() && !text.contains("حذف")) return text
        }
        return "ثبت مشتری"
    }

    private fun actionPath(html: String): String {
        val form = Regex("""<form\b([^>]*)>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1).orEmpty()
        val action = attr(form, "action")
        val path = action.substringBefore('?').substringAfter("vinor.ir")
        return if (path.contains("/customers")) path else PartnerCrm.PATH
    }

    private fun deleteIdKey(html: String): String {
        val mentionsDelete = html.contains("حذف") ||
            Regex("""data-action\s*=\s*["']delete["']""", RegexOption.IGNORE_CASE).containsMatchIn(html)
        if (!mentionsDelete) return ""
        val keys = keysNear(html, "delete") + keysNear(html, "حذف")
        return pick(keys, listOf("customer_id", "id"))
    }

    private fun labelFor(html: String, name: String, attrs: String): String {
        val forLabel = Regex(
            """<label\b[^>]*for\s*=\s*["']${Regex.escape(attr(attrs, "id"))}["'][^>]*>(.*?)</label>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        if (attr(attrs, "id").isNotBlank()) {
            val text = forLabel.find(html)?.let { plainLines(it.groupValues[1]).firstOrNull() }.orEmpty()
            if (text.isNotBlank()) return text
        }
        val placeholder = attr(attrs, "placeholder")
        if (placeholder.isNotBlank() && !placeholder.startsWith("09")) return placeholder
        val aria = attr(attrs, "aria-label")
        if (aria.isNotBlank()) return aria
        return LABELS[name] ?: name
    }

    private fun classText(inner: String, token: String): String {
        val match = Regex(
            """<(?:p|span|div|h[1-6]|a|td|strong)\b[^>]*class\s*=\s*["'][^"']*$token[^"']*["'][^>]*>(.*?)</""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(inner) ?: return ""
        return plainLines(match.groupValues[1]).firstOrNull().orEmpty()
    }

    private fun phoneIn(inner: String): String {
        val tel = Regex("""href\s*=\s*["']tel:([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(inner)?.groupValues?.getOrNull(1)
        val fromTel = normalizePhone(tel.orEmpty())
        if (fromTel.matches(Regex("09[0-9]{9}"))) return fromTel
        val text = plainLines(inner).joinToString(" ")
        val found = Regex("""(?:\+98|0098|0)?9[0-9]{2}[\s-]?[0-9]{3}[\s-]?[0-9]{4}""").find(text)
        return normalizePhone(found?.value.orEmpty())
    }

    private fun plainLines(html: String): List<String> {
        val text = html
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</(?:p|div|li|h[1-6]|tr|td)>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return text.split('\n').map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
    }

    private fun skipClass(cls: String): Boolean {
        if (cls.isBlank()) return false
        val tokens = cls.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val structural = tokens.any { it in setOf("page", "shell", "wrap", "list", "form", "modal", "empty", "header", "footer", "tabs", "toolbar") }
        val row = tokens.any { it in setOf("card", "item", "row", "entry") }
        return structural && !row
    }

    private fun isSearch(name: String, type: String, placeholder: String): Boolean {
        if (type == "search") return true
        if (name in setOf("q", "query", "search")) return true
        return placeholder.contains("جستجو")
    }

    private fun usableName(name: String): Boolean {
        if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return false
        return name !in setOf("csrf_token", "_csrf", "authenticity_token", "next", "submit")
    }

    private fun attr(tag: String, name: String): String {
        val match = Regex(
            """\b${Regex.escape(name)}\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(tag) ?: return ""
        return match.groupValues[1].trim()
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    private fun keysNear(html: String, endpoint: String): List<String> {
        val at = html.indexOf(endpoint)
        if (at < 0) return emptyList()
        val from = (at - 1600).coerceAtLeast(0)
        val to = (at + 1600).coerceAtMost(html.length)
        return Regex("""["']?([A-Za-z_][A-Za-z0-9_]*)["']?\s*:""")
            .findAll(html.substring(from, to))
            .map { it.groupValues[1] }
            .filter { it !in jsNoise }
            .toList()
    }

    private fun pick(keys: List<String>, options: List<String>): String {
        for (option in options) {
            if (option in keys) return option
        }
        return ""
    }

    private val LABELS = mapOf(
        "customer_first_name" to "نام",
        "first_name" to "نام",
        "customer_last_name" to "نام خانوادگی",
        "last_name" to "نام خانوادگی",
        "customer_phone" to "موبایل",
        "phone" to "موبایل",
        "mobile" to "موبایل",
        "notes" to "یادداشت",
        "note" to "یادداشت",
        "name" to "نام",
        "full_name" to "نام",
        "description" to "یادداشت",
    )

    private val jsNoise = setOf(
        "method", "headers", "body", "credentials", "signal", "mode", "cache",
        "redirect", "referrer", "integrity", "keepalive", "window", "document",
        "if", "function", "return", "var", "let", "const", "catch", "then",
        "Accept", "Content-Type",
    )
}
