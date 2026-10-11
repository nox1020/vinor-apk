package ir.vinor.app

/**
 * چت فقط برای نشست واردشده است.
 * حافظهٔ ترد وقتی حساب عوض شود یا خارج شود قابل نمایش نیست.
 */
object ChatSessionGate {
    fun accountKey(hasSession: Boolean, fingerprint: String): String? {
        if (!hasSession) return null
        val fp = fingerprint.trim()
        return fp.ifBlank { VinorCacheSession.ANON }
    }

    /** true یعنی حباب‌ها و thread_id حافظه مال این حساب نیستند. */
    fun shouldDropMemory(boundAccount: String?, currentAccount: String?): Boolean {
        if (currentAccount == null) return true
        return boundAccount != currentAccount
    }

    /**
     * ۴۰۱/۴۰۳ تاریخچه را با کش حساب قبلی نپوشان.
     * بقیهٔ خطاها همان fallback قبلی را دارند.
     */
    fun cacheMayHideAuthFailure(path: String, status: Int): Boolean {
        if (status != 401 && status != 403) return true
        val bare = path.substringBefore('?').substringBefore('#')
        return !bare.endsWith("/ai/history")
    }
}
