package ir.vinor.app

/**
 * تب وینور (صفحه اصلی) - WebView.
 * صفحه داشبورد وب و جزئیات فایل‌ها داخل خود اپ (WebView) باز می‌شوند.
 * لینک‌های دیگر (مثلاً لاگین عمومی، صفحات غیرمرتبط) در مرورگر خارج از اپ باز می‌شوند.
 */
class DashboardFragment : BaseWebViewFragment() {

    override val targetUrl: String = VinorPartnerUrls.ENTRY
    override val fragmentTag: String = "DashboardFragment"

    /** همیشه فقط همین صفحه وینور لود شود (بدون جایگزینی با منو). */
    override fun getUrlToLoad(): String = targetUrl

    // از این به بعد، منطق عمومی BaseWebViewFragment برای همه لینک‌ها (داخلی و خارجی)
    // استفاده می‌شود و همه داخل همین WebView باز می‌شوند؛ بنابراین این متد نیازی
    // به override اختصاصی ندارد و به مقدار پیش‌فرض (null) بسنده می‌کنیم.

    /** تغییر آدرس از منو اعمال نشود؛ همیشه داشبورد بماند. */
    override fun reloadWithUrl(url: String) {
        if (isWebViewInitialized() && isAdded) {
            webView.loadUrl(targetUrl)
        }
    }
}
