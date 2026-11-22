package ir.vinor.app

/** قواعد خالص لود تب «مشتریان» (تست واحد بدون اندروید). */
object CustomersLoad {
    /**
     * JSON سبک فقط برای صفحهٔ اصلی مشتریان (بدون query/تب/جستجوی HTML)
     * و تا وقتی سرور آن را پشتیبانی کند.
     */
    fun useJson(url: String, defaultUrl: String, jsonUnsupported: Boolean): Boolean {
        if (jsonUnsupported) return false
        if (url.contains('?') || url.contains('#')) return false
        return url.trimEnd('/') == defaultUrl.trimEnd('/')
    }
}
