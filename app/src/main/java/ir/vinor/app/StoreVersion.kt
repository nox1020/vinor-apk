package ir.vinor.app

/**
 * همان فرمول `computeVersionCode` در app/build.gradle.
 * پلی و بازار این عدد را از version.txt می‌گیرند، نه از شمارهٔ اجرای CI.
 */
object StoreVersion {
    fun versionCode(versionName: String): Int {
        val parts = versionName.trim().split('.')
        fun part(index: Int): Int = parts.getOrNull(index)?.toIntOrNull() ?: 0
        return part(0) * 10000 + part(1) * 100 + part(2)
    }
}
