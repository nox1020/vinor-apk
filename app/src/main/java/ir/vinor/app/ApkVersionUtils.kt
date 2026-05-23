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

    private fun versionParts(version: String): List<Int> {
        if (version.isBlank()) return emptyList()
        return version.split('.').map { part ->
            val digits = part.filter { it.isDigit() }
            digits.toIntOrNull() ?: 0
        }
    }
}
