package ir.vinor.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** دیسک تصاویر کارت/ملک — شامل CDN دیوار؛ وابسته به نشست نیست. */
object VinorImageDiskCache {

    private const val MAX_FILE_BYTES = 1_500_000
    private const val MAX_FILES = 220
    private const val MAX_TOTAL_BYTES = 48L * 1024 * 1024

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vinor-image-cache").apply { isDaemon = true }
    }

    private fun dir(context: Context): File {
        val d = File(
            context.filesDir,
            "${VinorCachePolicy.ROOT_DIR}/v${VinorCachePolicy.SCHEMA_VERSION}/images/${VinorCacheSession.PUBLIC}",
        )
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun key(url: String): String {
        val n = url.trim()
        return MessageDigest.getInstance("SHA-256").digest(n.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun read(context: Context, url: String): ByteArray? {
        val u = url.trim()
        if (u.isEmpty() || !u.startsWith("http")) return null
        val d = dir(context)
        val id = key(u)
        val bin = File(d, "$id.bin")
        val bytes = VinorCacheIo.readBytes(bin) ?: return null
        if (bytes.isEmpty()) {
            VinorCacheIo.deleteQuiet(bin)
            VinorCacheIo.deleteQuiet(File(d, "$id.json"))
            return null
        }
        File(d, "$id.json").setLastModified(System.currentTimeMillis())
        bin.setLastModified(System.currentTimeMillis())
        return bytes
    }

    fun write(context: Context, url: String, bytes: ByteArray) {
        val u = url.trim()
        if (u.isEmpty() || !u.startsWith("http")) return
        if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) return
        val app = context.applicationContext
        io.execute {
            runCatching {
                val d = dir(app)
                val id = key(u)
                val bin = File(d, "$id.bin")
                val meta = File(d, "$id.json")
                if (!VinorCacheIo.writeAtomic(bin, bytes)) return@runCatching
                val json = JSONObject()
                    .put("url", u)
                    .put("createdAt", System.currentTimeMillis())
                    .put("size", bytes.size)
                VinorCacheIo.writeAtomicText(meta, json.toString())
                trim(d)
            }
        }
    }

    fun clear(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }

    private fun trim(dir: File) {
        val bins = dir.listFiles { f -> f.name.endsWith(".bin") }?.toList().orEmpty()
        val remove = VinorCacheIo.lruTrim(bins, MAX_FILES, MAX_TOTAL_BYTES)
        remove.forEach { bin ->
            val id = bin.name.removeSuffix(".bin")
            VinorCacheIo.deleteQuiet(bin)
            VinorCacheIo.deleteQuiet(File(dir, "$id.json"))
        }
    }
}
