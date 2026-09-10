package ir.vinor.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PartnerImages {
    private val mem = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun resolve(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return if (raw.startsWith("/")) "${PartnerApi.BASE}$raw" else "${PartnerApi.BASE}/$raw"
    }

    fun roundClip(view: View, radiusDp: Float = 12f) {
        val radius = radiusDp * view.resources.displayMetrics.density
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), radius)
            }
        }
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.invalidateOutline() }
    }

    fun coverUrl(land: org.json.JSONObject?): String {
        if (land == null) return ""
        listOf("image_url", "image_full", "image_thumb", "image_raw").forEach { key ->
            val v = land.optString(key).trim()
            if (v.isNotBlank() && v != "null") return v
        }
        val images = land.optJSONArray("images") ?: return ""
        val first = images.opt(0)
        return when (first) {
            is String -> first
            is org.json.JSONObject -> first.optString("url").ifBlank { first.optString("src") }
            else -> ""
        }
    }

    fun bind(scope: CoroutineScope, view: ImageView, url: String?, fallback: View? = null) {
        val resolved = resolve(url)
        if (resolved.isBlank()) {
            view.setImageDrawable(null)
            fallback?.isVisible = true
            return
        }
        val cached = mem.get(resolved)
        if (cached != null && !cached.isRecycled) {
            view.setImageBitmap(cached)
            fallback?.isVisible = false
            return
        }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(resolved, view.context) }
            if (bmp == null) {
                fallback?.isVisible = true
                return@launch
            }
            mem.put(resolved, bmp)
            view.setImageBitmap(bmp)
            fallback?.isVisible = false
        }
    }

    private fun decode(url: String, context: android.content.Context): Bitmap? {
        return try {
            val bytes = PartnerApi.bytes(url, context) ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
}
