package ir.vinor.app

/**
 * تب ثبت آگهی - داشبورد همکار اکسپرس
 * URL: /express/partner/dashboard
 * نیاز به لاگین دارد - اگر لاگین نباشد به /express/partner/login ریدایرکت می‌شود
 */
class AddListingFragment : BaseWebViewFragment() {
    override val targetUrl: String = VinorPartnerUrls.ENTRY
    override val fragmentTag: String = "AddListingFragment"
}

