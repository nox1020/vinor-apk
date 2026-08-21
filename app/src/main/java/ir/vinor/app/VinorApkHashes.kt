package ir.vinor.app

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** SHA-256 کل فایل APK — بدون وابستگی Android (قابل تست روی JVM). */
object VinorApkHashes {

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }

    fun matches(file: File, expected: String): Boolean {
        val want = normalize(expected)
        if (want.isEmpty()) return true
        if (!file.isFile) return false
        return sha256Hex(file).equals(want, ignoreCase = true)
    }

    fun normalize(value: String): String =
        value.trim().lowercase().replace(" ", "").replace(":", "")
}
