package ir.vinor.app

/**
 * CRM مشتریان همکار — همان فلگ وب `crm_ui_enabled`.
 * روی `GET /express/partner/home/data` برمی‌گردد. اگر `profile/data` هم کلید را داشت، همان مقدم است.
 */
object PartnerCrm {
    const val FLAG = "crm_ui_enabled"
    const val PATH = "/express/partner/customers"

    /**
     * ردیف منو فقط برای همکار تأییدشده و وقتی فلگ CRM روشن است.
     * `null` یعنی آن payload کلید را ندارد؛ در نبود هر دو منبع، ردیف مخفی می‌ماند.
     */
    fun showMenu(isApproved: Boolean, profileFlag: Boolean?, homeFlag: Boolean?): Boolean {
        if (!isApproved) return false
        return profileFlag ?: homeFlag ?: false
    }
}
