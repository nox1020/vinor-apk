package ir.vinor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * آواتار وینور من یک‌بار برای هر نسخه (`?v=`) ذخیره می‌شود.
 * ورود دوباره یا تازه‌سازی پروفایل، تا وقتی عکس عوض نشده، شبکه را نمی‌زند.
 * فایل روی دیسک با اثر انگشت نشست جدا می‌شود تا عکسِ حساب قبلی نماند.
 */
object VinorAvatarCache {
    private const val MAX_BYTES = 2_000_000
    private const val FILE_BIN = "avatar.bin"
    private const val FILE_META = "avatar.json"

    private val lock = Any()
    private var memUrl = ""
    private var memBytes: ByteArray? = null
    private var memBitmap: Bitmap? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun resolve(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty() || raw == "null") return ""
        if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
        return if (raw.startsWith("/")) "${PartnerApi.BASE}$raw" else "${PartnerApi.BASE}/$raw"
    }

    fun base(url: String): String {
        val resolved = resolve(url)
        val cut = resolved.indexOfAny(charArrayOf('?', '#'))
        return if (cut < 0) resolved else resolved.substring(0, cut)
    }

    fun version(url: String): Int {
        val resolved = resolve(url)
        val query = resolved.substringAfter('?', "")
        if (query.isEmpty()) return 0
        val pair = query.split('&').firstOrNull { it.substringBefore('=') == "v" } ?: return 0
        return pair.substringAfter('=').toIntOrNull() ?: 0
    }

    /** همان فایل، حتی اگر ترتیب کوئری فرق کند؛ نسخهٔ متفاوت یعنی عکس جدید. */
    fun samePhoto(a: String, b: String): Boolean {
        val left = resolve(a)
        val right = resolve(b)
        return left.isNotEmpty() && base(left) == base(right) && version(left) == version(right)
    }

    fun peek(url: String): Bitmap? = synchronized(lock) {
        val bmp = memBitmap
        if (samePhoto(memUrl, url) && bmp != null && !bmp.isRecycled) bmp else null
    }

    fun rememberBitmap(url: String, bitmap: Bitmap) = synchronized(lock) {
        memUrl = resolve(url)
        memBitmap = bitmap
    }

    fun read(context: Context, url: String): ByteArray? {
        synchronized(lock) {
            if (samePhoto(memUrl, url)) memBytes?.let { return it }
        }
        val bytes = readStored(dir(context), url) ?: return null
        synchronized(lock) {
            if (!samePhoto(memUrl, url)) memBitmap = null
            memUrl = resolve(url)
            memBytes = bytes
        }
        return bytes
    }

    fun store(context: Context, url: String, bytes: ByteArray) {
        if (!writeStored(dir(context), url, bytes)) return
        synchronized(lock) {
            if (!samePhoto(memUrl, url)) memBitmap = null
            memUrl = resolve(url)
            memBytes = bytes
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            memUrl = ""
            memBytes = null
            memBitmap = null
        }
        val root = File(
            context.applicationContext.filesDir,
            "${VinorCachePolicy.ROOT_DIR}/v${VinorCachePolicy.SCHEMA_VERSION}/avatars",
        )
        runCatching { root.deleteRecursively() }
    }

    /** کوکی را از ترد اصلی بده. فقط وقتی نسخهٔ ذخیره‌شده نیست صدا زده می‌شود. */
    fun fetch(url: String, cookie: String): ByteArray? {
        val abs = resolve(url)
        if (!abs.startsWith("https://")) return null
        return try {
            val builder = Request.Builder().url(abs).addHeader("Accept", "image/*,*/*")
            if (cookie.isNotBlank() && abs.startsWith(PartnerApi.BASE)) {
                builder.addHeader("Cookie", cookie)
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.bytes() ?: return null
                body.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decode(bytes: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val edge = maxOf(bounds.outWidth, bounds.outHeight)
            while (edge / (sample * 2) >= 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }

    fun readStored(dir: File, url: String): ByteArray? {
        val meta = VinorCacheIo.readJson(File(dir, FILE_META)) ?: return null
        if (!samePhoto(meta.optString("url"), url)) return null
        val bytes = VinorCacheIo.readBytes(File(dir, FILE_BIN)) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return bytes
    }

    fun writeStored(dir: File, url: String, bytes: ByteArray): Boolean {
        val resolved = resolve(url)
        if (resolved.isEmpty() || bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        val meta = JSONObject()
            .put("url", resolved)
            .put("base", base(resolved))
            .put("version", version(resolved))
        if (!VinorCacheIo.writeAtomic(File(dir, FILE_BIN), bytes)) return false
        return VinorCacheIo.writeAtomicText(File(dir, FILE_META), meta.toString())
    }

    private fun dir(context: Context): File {
        val fp = VinorCacheSession.scopeForRead(context.applicationContext)
            .ifBlank { VinorCacheSession.ANON }
        return File(
            context.applicationContext.filesDir,
            "${VinorCachePolicy.ROOT_DIR}/v${VinorCachePolicy.SCHEMA_VERSION}/avatars/$fp",
        )
    }
}
