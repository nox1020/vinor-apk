package ir.vinor.app

/**
 * صفحه میانی فوتر «افزودن».
 * فقط مسیرهای موجود وصل می‌شوند؛ بدون مسیر ساختگی.
 */
object AddHubActions {

    const val PAGE_TITLE = "چه کاری می‌خواهید انجام دهید؟"
    const val FOOTER_LABEL = "افزودن"
    const val PAGE_PATH = "/express/partner/add"

    data class Row(
        val id: String,
        val title: String,
        val subtitle: String,
        val path: String?,
        val iconRes: Int,
    )

    fun rows(): List<Row> = listOf(
        Row(
            id = "listing",
            title = "ثبت آگهی",
            subtitle = "ملک را برای فروش یا اجاره آگهی کنید.",
            path = "/express/partner/my-property?register=1",
            iconRes = R.drawable.ic_building,
        ),
        Row(
            id = "customer",
            title = "افزودن مشتری",
            subtitle = "مشخصات و نیاز ملکی مشتری را ثبت کنید.",
            path = "/express/partner/customers",
            iconRes = R.drawable.ic_user_plus_stroke,
        ),
        Row(
            id = "consign",
            title = "سپردن ملک",
            subtitle = "فروش یا اجاره ملک خود را به وینور بسپارید.",
            path = null,
            iconRes = R.drawable.ic_handshake,
        ),
        Row(
            id = "valuation",
            title = "استعلام قیمت ملک",
            subtitle = "درخواست برآورد قیمت ملک را ثبت کنید.",
            path = null,
            iconRes = R.drawable.ic_scale,
        ),
    )

    fun missingPaths(): List<String> = rows().filter { it.path.isNullOrBlank() }.map { it.title }
}
