package ir.vinor.app

object ApkVersionUtils {

    fun isServerNewer(serverVersion: String, clientVersion: String): Boolean {
        val server = serverVersion.trim()
        val client = clientVersion.trim()
        if (server.isEmpty() || client.isEmpty()) return false
        val a = versionParts(client)
        val b = versionParts(server)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x < y) return true
            if (x > y) return false
        }
        return false
    }

    /** فرمول Gradle وینور: MMmmpp — 1.0.272 → 10272 */
    fun versionCodeFromName(version: String): Long {
        val parts = versionParts(version)
        val major = parts.getOrElse(0) { 0 }.toLong()
        val minor = parts.getOrElse(1) { 0 }.toLong()
        val patch = parts.getOrElse(2) { 0 }.toLong()
        val code = major * 10000L + minor * 100L + patch
        return if (code > 0L) code else 0L
    }

    /**
     * مرجع اصلی: versionCode. اگر هر دو طرف code معتبر دارند با code مقایسه می‌شود.
     * در غیر این صورت fallback به versionName (سازگاری با API قدیمی).
     */
    fun isRemoteNewer(
        remoteCode: Long,
        installedCode: Long,
        remoteName: String = "",
        installedName: String = "",
    ): Boolean {
        if (remoteCode > 0L && installedCode > 0L) {
            return remoteCode > installedCode
        }
        return isServerNewer(remoteName, installedName)
    }

    fun parseLong(raw: Any?): Long {
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun versionParts(version: String): List<Int> {
        if (version.isBlank()) return emptyList()
        return version.split('.').map { part ->
            val digits = part.filter { it.isDigit() }
            digits.toIntOrNull() ?: 0
        }
    }
}
