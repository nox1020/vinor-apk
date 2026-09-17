package ir.vinor.app

import android.content.Context
import org.json.JSONArray

object VinorFavoritesStore {
    private const val PREFS = "vinor_favs"
    private const val KEY = "vinor_dashboard_favs"

    fun codes(context: Context): MutableList<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]").orEmpty()
        return try {
            val arr = JSONArray(raw.ifBlank { "[]" })
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val code = arr.optString(i).trim()
                if (code.isNotBlank() && code !in out) out.add(code)
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun contains(context: Context, code: String): Boolean =
        codes(context).contains(code.trim())

    fun toggle(context: Context, code: String): Boolean {
        val trimmed = code.trim()
        if (trimmed.isBlank()) return false
        val list = codes(context)
        val on = if (list.remove(trimmed)) {
            false
        } else {
            list.add(trimmed)
            true
        }
        save(context, list)
        return on
    }

    private fun save(context: Context, codes: List<String>) {
        val arr = JSONArray()
        codes.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}
