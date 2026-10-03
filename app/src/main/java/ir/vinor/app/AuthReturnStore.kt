package ir.vinor.app

import android.content.Context
import android.net.Uri

/**
 * مقصد داخلی اپ بعد از ورود دوباره.
 * فقط صفحه‌های خود اپ ذخیره می‌شود؛ URL بیرونی و next به هاست دیگر رد می‌شود.
 */
object AuthReturnStore {

    const val REASON_SESSION_EXPIRED = "session_expired"

    private const val PREFS = "vinor_auth_return"
    private const val KEY_KIND = "kind"
    private const val KEY_PARAM = "param"

    data class Dest(val kind: String, val param: String)

    fun save(context: Context, dest: Dest) {
        if (!isInternal(dest)) return
        prefs(context).edit()
            .putString(KEY_KIND, dest.kind)
            .putString(KEY_PARAM, dest.param)
            .apply()
    }

    fun peek(context: Context): Dest? {
        val kind = prefs(context).getString(KEY_KIND, null)?.trim().orEmpty()
        if (kind.isEmpty()) return null
        val dest = Dest(kind, prefs(context).getString(KEY_PARAM, null).orEmpty())
        if (!isInternal(dest)) {
            clear(context)
            return null
        }
        return dest
    }

    fun consume(context: Context): Dest? {
        val dest = peek(context) ?: return null
        clear(context)
        return dest
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_KIND).remove(KEY_PARAM).apply()
    }

    fun isInternal(dest: Dest): Boolean {
        val param = dest.param.trim()
        if (param.contains("://") || param.startsWith("//") || param.contains("\\")) return false
        if (param.contains("@") || param.contains("next=", ignoreCase = true)) return false
        return when (dest.kind) {
            "home", "chat", "search", "profile", "profile_edit", "notifications",
            "notes", "commissions", "routine", "my_properties", "consultant" -> param.isEmpty()
            "property_form" -> param == "0" || param == "1"
            "land" -> isSafeLandCode(param)
            "native" -> isSafeNativePage(param)
            "partner" -> internalPartnerPath(param) != null
            else -> false
        }
    }

    fun isSafeLandCode(code: String): Boolean {
        if (code.isBlank() || code.length > 120) return false
        if (code.contains("://") || code.contains("//") || code.any { it.isWhitespace() }) return false
        return code.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' || it == '.' }
    }

    fun isSafeNativePage(page: String): Boolean = page in NATIVE_PAGES

    /** مسیر نسبی وینور، یا URL کامل فقط روی vinor.ir که به همان مسیر تبدیل می‌شود. */
    fun internalPartnerPath(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 512) return null
        if (trimmed.contains("\\") || trimmed.contains("@")) return null
        if (trimmed.contains("next=", ignoreCase = true)) return null
        val pathAndQuery = if (trimmed.contains("://") || trimmed.startsWith("//")) {
            if (!UrlUtils.isTrustedVinorHttps(if (trimmed.startsWith("//")) "https:$trimmed" else trimmed)) {
                return null
            }
            val uri = Uri.parse(if (trimmed.startsWith("//")) "https:$trimmed" else trimmed)
            val path = uri.path ?: return null
            if (!path.startsWith("/")) return null
            val query = uri.query
            if (query.isNullOrBlank()) path else "$path?$query"
        } else {
            if (!trimmed.startsWith("/")) return null
            trimmed
        }
        val pathOnly = pathAndQuery.substringBefore('?')
        if (!pathOnly.startsWith("/") || pathOnly.startsWith("//")) return null
        if (pathAndQuery.contains("://")) return null
        return pathAndQuery
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val NATIVE_PAGES = setOf(
        NativePartnerFragment.PAGE_NEWS,
        NativePartnerFragment.PAGE_TRAINING,
        NativePartnerFragment.PAGE_LEVELS,
        NativePartnerFragment.PAGE_INVITE,
        NativePartnerFragment.PAGE_SETTINGS,
        NativePartnerFragment.PAGE_FAVORITES,
        NativePartnerFragment.PAGE_REQUESTS,
        NativePartnerFragment.PAGE_APPLY,
        NativePartnerFragment.PAGE_LISTINGS,
        NativePartnerFragment.PAGE_TERMS,
        NativePartnerFragment.PAGE_PRIVACY,
    )
}
