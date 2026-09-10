package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/** کاروسل خانهٔ وب وقتی JSON هنوز آگهی ندارد — همان کارت‌های HTML. */
object HomeAdsHtml {
    private val cardRe = Regex(
        """<a[^>]*class="[^"]*vn-home-divar__card[^"]*"[\s\S]*?</a>""",
        RegexOption.IGNORE_CASE,
    )

    fun parseNews(html: String): JSONArray {
        if (html.isBlank()) return JSONArray()
        val raw = Regex("""var ITEMS = (\[[\s\S]*?\]);""").find(html)?.groupValues?.get(1) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun parse(html: String): JSONArray {
        val out = JSONArray()
        if (html.isBlank()) return out
        val seen = LinkedHashSet<String>()
        for (match in cardRe.findAll(html)) {
            val block = match.value
            val href = unescape(attr(block, "href"))
            val divar = attr(block, "data-divar-token")
            val land = attr(block, "data-land-code")
            val key = when {
                land.isNotBlank() -> "v:$land"
                divar.isNotBlank() -> "d:$divar"
                href.isNotBlank() -> "h:$href"
                else -> continue
            }
            if (!seen.add(key)) continue
            val title = inner(block, "vn-home-divar__name").ifBlank { unescape(attr(block, "title")) }
            val source = when {
                land.isNotBlank() -> "vinor"
                divar.isNotBlank() || href.contains("/divar/") -> "divar"
                else -> ""
            }
            out.put(
                JSONObject()
                    .put("title", title)
                    .put("price", inner(block, "vn-home-divar__price"))
                    .put("location", inner(block, "vn-home-divar__loc"))
                    .put("image_url", unescape(imgSrc(block)))
                    .put("image_count", shots(block))
                    .put("token", land.ifBlank { divar })
                    .put("source", source)
                    .put("url", href)
                    .put("commission_amount", 0),
            )
        }
        return out
    }

    private fun attr(block: String, name: String): String {
        val dq = Regex("""\b$name="([^"]*)"""", RegexOption.IGNORE_CASE).find(block)
        if (dq != null) return dq.groupValues[1]
        val sq = Regex("""\b$name='([^']*)'""", RegexOption.IGNORE_CASE).find(block)
        return sq?.groupValues?.get(1).orEmpty()
    }

    private fun inner(block: String, cls: String): String {
        val re = Regex("""class="[^"]*$cls[^"]*"[^>]*>([^<]*)""", RegexOption.IGNORE_CASE)
        return unescape(re.find(block)?.groupValues?.get(1).orEmpty())
    }

    private fun imgSrc(block: String): String {
        val re = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return re.find(block)?.groupValues?.get(1).orEmpty()
    }

    private fun shots(block: String): Int {
        val span = Regex(
            """class="[^"]*vn-home-divar__shots[^"]*"[\s\S]*?</span>""",
            RegexOption.IGNORE_CASE,
        ).find(block)?.value ?: return 0
        return digits(span)
    }

    private fun digits(raw: String): Int {
        val mapped = buildString {
            raw.forEach { c ->
                when (c) {
                    in '۰'..'۹' -> append('0' + (c - '۰'))
                    in '0'..'9' -> append(c)
                }
            }
        }
        return mapped.toIntOrNull() ?: 0
    }

    private fun unescape(value: String): String =
        value.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
}
