package ir.vinor.app

import org.json.JSONObject

/** حافظهٔ فرآیندی کارت ملک — تا با نابودی Fragment دوباره GET نشود. */
object VinorLandCardCache {
    private const val MAX = 80
    private val lock = Any()
    private val lands = linkedMapOf<String, JSONObject>()

    fun get(code: String): JSONObject? {
        val key = code.trim()
        if (key.isEmpty()) return null
        synchronized(lock) {
            val hit = lands.remove(key) ?: return null
            lands[key] = hit
            return hit
        }
    }

    fun put(code: String, land: JSONObject) {
        val key = code.trim()
        if (key.isEmpty()) return
        synchronized(lock) {
            lands.remove(key)
            lands[key] = land
            while (lands.size > MAX) {
                val oldest = lands.keys.firstOrNull() ?: break
                lands.remove(oldest)
            }
        }
    }
}
