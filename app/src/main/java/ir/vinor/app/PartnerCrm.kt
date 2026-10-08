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

    /**
     * تصمیم اولِ ردیف. اگر فلگ هنوز قطعی نیست، آخرین مقدار یا نمایش خوش‌بینانه
     * استفاده می‌شود تا ردیف بعد از بقیهٔ منو ظاهر نشود و لیست نپرد.
     */
    fun decide(
        isApproved: Boolean,
        profileFlag: Boolean?,
        homeFlag: Boolean?,
        homeFresh: Boolean,
        remembered: Boolean?,
    ): CustomersMenu {
        if (!isApproved) return CustomersMenu(visible = false, refresh = false)
        if (profileFlag != null) return CustomersMenu(profileFlag, refresh = false)
        if (homeFresh && homeFlag != null) return CustomersMenu(homeFlag, refresh = false)
        return CustomersMenu(visible = homeFlag ?: remembered ?: true, refresh = true)
    }
}

/** `refresh` یعنی نتیجه را باید با خانهٔ تازه یکی کرد، بدون عوض کردن ردیف اگر همان بماند. */
data class CustomersMenu(val visible: Boolean, val refresh: Boolean)
