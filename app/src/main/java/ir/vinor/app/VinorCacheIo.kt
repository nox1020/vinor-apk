package ir.vinor.app

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** نوشتن اتمیک فایل + LRU روی فهرست فایل. بدون Android API تا تست واحد ممکن باشد. */
object VinorCacheIo {

    fun writeAtomic(target: File, bytes: ByteArray): Boolean {
        return runCatching {
            val dir = target.parentFile ?: return false
            if (!dir.exists() && !dir.mkdirs()) return false
            val tmp = File(dir, target.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(target)) {
                tmp.inputStream().use { ins ->
                    FileOutputStream(target).use { out ->
                        ins.copyTo(out)
                        out.flush()
                        out.fd.sync()
                    }
                }
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun writeAtomicText(target: File, text: String): Boolean =
        writeAtomic(target, text.toByteArray(Charsets.UTF_8))

    fun readBytes(file: File): ByteArray? {
        if (!file.isFile) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len <= 0L || len > 8L * 1024 * 1024) return@use null
                val buf = ByteArray(len.toInt())
                raf.readFully(buf)
                buf
            }
        }.getOrNull()
    }

    fun readText(file: File): String? =
        readBytes(file)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

    fun readJson(file: File): JSONObject? {
        val raw = readText(file) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    fun deleteQuiet(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    fun lruTrim(files: List<File>, maxCount: Int, maxTotalBytes: Long): List<File> {
        if (files.isEmpty()) return emptyList()
        val sorted = files.sortedBy { it.lastModified() }
        var total = sorted.sumOf { it.length() }
        val removed = mutableListOf<File>()
        var i = 0
        while (i < sorted.size && (sorted.size - removed.size > maxCount || total > maxTotalBytes)) {
            val f = sorted[i]
            i++
            total -= f.length()
            removed.add(f)
        }
        return removed
    }
}
