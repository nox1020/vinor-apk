package ir.vinor.app

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/** مسیر فایل‌های بروزرسانی در filesDir — مستقل از WebView. */
object VinorApkUpdateFiles {

    const val APK_DIR = "apk-updates"
    const val MIN_APK_BYTES = 50_000L
    private const val TAG = "VinorApkUpdateFiles"

    fun updatesDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, APK_DIR)
        if (!dir.exists()) dir.mkdirs()
        try {
            val legacy = File(context.applicationContext.cacheDir, APK_DIR)
            if (legacy.isDirectory) {
                legacy.listFiles()?.forEach { src ->
                    val dest = File(dir, src.name)
                    if (!dest.exists() && src.isFile) {
                        src.copyTo(dest, overwrite = false)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return dir
    }

    fun apkFile(context: Context, versionName: String): File {
        val safe = VinorDownloadBridge.sanitizeFilename("vinor-$versionName.apk")
        return File(updatesDir(context), safe)
    }

    fun partFile(context: Context, versionName: String): File {
        return File(apkFile(context, versionName).absolutePath + ".part")
    }

    fun looksLikeHtml(file: File): Boolean {
        return try {
            file.inputStream().use { ins ->
                val buf = ByteArray(64)
                val n = ins.read(buf)
                if (n <= 0) return false
                val head = buf.copyOf(n).toString(Charsets.UTF_8).trimStart().lowercase()
                head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head")
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isValidApkFile(file: File?): Boolean {
        if (file == null || !file.isFile || file.length() < MIN_APK_BYTES) return false
        return try {
            file.inputStream().use { ins ->
                val magic = ByteArray(4)
                if (ins.read(magic) != 4) return false
                if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) return false
            }
            ZipFile(file).use { zip ->
                zip.getEntry("AndroidManifest.xml") != null
            }
        } catch (e: Exception) {
            Log.w(TAG, "isValidApkFile failed: ${file.name}", e)
            false
        }
    }

    fun cleanup(
        context: Context,
        keepVersionName: String = "",
        keepReadyFile: Boolean = true,
    ) {
        val dir = updatesDir(context)
        val keepApk = if (keepVersionName.isNotBlank()) apkFile(context, keepVersionName) else null
        val keepPart = if (keepVersionName.isNotBlank()) partFile(context, keepVersionName) else null
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val name = f.name
            val isApkOrPart = name.endsWith(".apk", true) || name.endsWith(".apk.part", true) ||
                name.endsWith(".part", true)
            if (!isApkOrPart) return@forEach
            if (keepReadyFile && keepApk != null && f.absolutePath == keepApk.absolutePath) return@forEach
            if (keepPart != null && f.absolutePath == keepPart.absolutePath) return@forEach
            runCatching { f.delete() }
        }
    }
}
