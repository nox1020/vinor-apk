package ir.vinor.app

import android.util.Log

/** لاگ آفلاین/کش بدون Cookie، توکن، تلفن یا payload حساس. */
object VinorCacheLog {
    private const val TAG = "VinorCache"

    fun event(name: String, extra: String = "") {
        val safe = extra.replace(Regex("(?i)(session|cookie|token|phone|otp)=[^\\s&]+"), "$1=*")
        val line = if (safe.isBlank()) name else "$name $safe"
        Log.i(TAG, line)
    }
}
