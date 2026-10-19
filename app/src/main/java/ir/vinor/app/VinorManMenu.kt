package ir.vinor.app

/**
 * مدل خالص منوی «وینور من». بدون وابستگی اندروید تا ترتیب قفل‌شدهٔ فاز A
 * از Fragment جدا بماند. تست این مرحله وب است و اینجا تست نوشته نمی‌شود.
 */
object VinorManMenu {

    const val ADVISOR_LABEL = "می‌خواهم بازاریاب وینور شوم"
    const val ADVISOR_SUBTITLE = "شروع همکاری فروش و دریافت پورسانت"
    const val ADVISOR_STATUS_LABEL = "وضعیت درخواست"
    const val ADVISOR_STATUS_SUBTITLE = "پیگیری تأیید همکاری"

    const val GROUP_ADS = 1
    const val GROUP_PARTNER = 2
    const val GROUP_SETTINGS = 3
    const val GROUP_LOGOUT = 4

    enum class Action {
        MY_PROPERTY,
        FAVORITES,
        NOTIFICATIONS,
        CONSULTANT,
        REQUESTS,
        CUSTOMERS,
        WALLET,
        INVITE,
        TRAINING,
        PLUS,
        SETTINGS,
        SUPPORT,
        LAWYER,
        TERMS,
        APP_UPDATE,
        LOGOUT,
    }

    /**
     * @param consultantApiEnabled اپ تا فاز B کلید فلگ را ندارد؛ پیش‌فرض سرور روشن است.
     */
    data class Flags(
        val consultantApiEnabled: Boolean = true,
    )

    data class Profile(
        val loggedIn: Boolean,
        val approved: Boolean = false,
        val pending: Boolean = false,
        val plusEnabled: Boolean = false,
        val lawyerOn: Boolean = false,
        val crmUiEnabled: Boolean = false,
        val showAppUpdate: Boolean = false,
        val unreadCount: Int = 0,
    )

    data class Row(
        val group: Int,
        val id: String,
        val label: String,
        val badge: String? = null,
        val subtitle: String? = null,
        val destination: String? = null,
        val action: Action,
        val requiresLogin: Boolean,
    )

    fun groupTitle(group: Int): String? = when (group) {
        GROUP_ADS -> "آگهی‌های من"
        GROUP_PARTNER -> "همکاری با وینور"
        GROUP_SETTINGS -> "تنظیمات و پشتیبانی"
        else -> null
    }

    /** گروه ۳ وقتی پروفایل در دسترس نیست: پشتیبانی و قوانین، بدون مهمان‌سازی. */
    fun supportRows(): List<Row> = listOf(
        Row(
            group = GROUP_SETTINGS,
            id = "support",
            label = "پشتیبانی",
            action = Action.SUPPORT,
            requiresLogin = false,
        ),
        Row(
            group = GROUP_SETTINGS,
            id = "terms",
            label = "قوانین و حریم خصوصی",
            destination = "/express/partner/legal/terms",
            action = Action.TERMS,
            requiresLogin = false,
        ),
    )

    fun rows(profile: Profile, flags: Flags = Flags()): List<Row> {
        val out = ArrayList<Row>()
        out += Row(
            GROUP_ADS,
            "my_property",
            "آگهی‌های من",
            destination = "/express/partner/my-property",
            action = Action.MY_PROPERTY,
            requiresLogin = true,
        )
        out += Row(
            GROUP_ADS,
            "favorites",
            "نشان‌شده‌ها",
            destination = "/express/partner/favorites",
            action = Action.FAVORITES,
            requiresLogin = true,
        )
        if (profile.loggedIn) {
            out += Row(
                GROUP_ADS,
                "notifications",
                "اعلان‌ها",
                badge = if (profile.unreadCount > 0) persianDigits(profile.unreadCount) else null,
                destination = "/express/partner/notifications",
                action = Action.NOTIFICATIONS,
                requiresLogin = true,
            )
        }

        if (!profile.approved && !profile.pending) {
            if (flags.consultantApiEnabled) {
                out += Row(
                    GROUP_PARTNER,
                    "consultant",
                    ADVISOR_LABEL,
                    subtitle = ADVISOR_SUBTITLE,
                    destination = "/express/partner/consultant",
                    action = Action.CONSULTANT,
                    requiresLogin = true,
                )
            } else {
                out += Row(
                    GROUP_PARTNER,
                    "consultant",
                    "همکاری با وینور",
                    destination = "/express/partner/apply",
                    action = Action.CONSULTANT,
                    requiresLogin = true,
                )
            }
        }
        if (profile.loggedIn && profile.pending) {
            val dest = if (flags.consultantApiEnabled) {
                "/express/partner/consultant"
            } else {
                "/express/partner/thanks"
            }
            out += Row(
                GROUP_PARTNER,
                "consultant_status",
                ADVISOR_STATUS_LABEL,
                subtitle = ADVISOR_STATUS_SUBTITLE,
                destination = dest,
                action = Action.CONSULTANT,
                requiresLogin = true,
            )
        }
        if (profile.loggedIn && profile.approved) {
            out += Row(
                GROUP_PARTNER,
                "requests",
                "پیگیری",
                destination = "/express/partner/requests",
                action = Action.REQUESTS,
                requiresLogin = true,
            )
            if (profile.crmUiEnabled) {
                out += Row(
                    GROUP_PARTNER,
                    "customers",
                    "مشتریان",
                    destination = "/express/partner/customers",
                    action = Action.CUSTOMERS,
                    requiresLogin = true,
                )
            }
            out += Row(
                GROUP_PARTNER,
                "wallet",
                "کیف پول",
                destination = "/express/partner/commissions",
                action = Action.WALLET,
                requiresLogin = true,
            )
            out += Row(
                GROUP_PARTNER,
                "invite",
                "دعوت همکار",
                destination = "/express/partner/invite",
                action = Action.INVITE,
                requiresLogin = true,
            )
        }
        if (profile.loggedIn && !profile.approved) {
            out += Row(
                GROUP_PARTNER,
                "invite_friends",
                "دعوت دوستان",
                destination = "/express/partner/invite",
                action = Action.INVITE,
                requiresLogin = true,
            )
        }
        out += Row(
            GROUP_PARTNER,
            "training",
            "آموزش",
            destination = "/express/partner/training",
            action = Action.TRAINING,
            requiresLogin = false,
        )
        if (profile.plusEnabled) {
            out += Row(
                GROUP_PARTNER,
                "plus",
                "وینور پلاس",
                destination = "/express/partner/plus",
                action = Action.PLUS,
                requiresLogin = false,
            )
        }

        if (profile.loggedIn) {
            out += Row(
                GROUP_SETTINGS,
                "settings",
                "تنظیمات",
                destination = "/express/partner/settings",
                action = Action.SETTINGS,
                requiresLogin = true,
            )
        }
        out += Row(
            GROUP_SETTINGS,
            "support",
            "پشتیبانی",
            action = Action.SUPPORT,
            requiresLogin = false,
        )
        if (profile.lawyerOn) {
            out += Row(
                GROUP_SETTINGS,
                "lawyer",
                "وکیل ملکی",
                destination = "/express/partner/home?ai=1&thread=__lawyer__",
                action = Action.LAWYER,
                requiresLogin = true,
            )
        }
        out += Row(
            GROUP_SETTINGS,
            "terms",
            "قوانین و حریم خصوصی",
            destination = "/express/partner/legal/terms",
            action = Action.TERMS,
            requiresLogin = false,
        )
        if (profile.loggedIn && profile.showAppUpdate) {
            out += Row(
                GROUP_SETTINGS,
                "app_update",
                "بروزرسانی اپلیکیشن",
                action = Action.APP_UPDATE,
                requiresLogin = false,
            )
        }
        if (profile.loggedIn) {
            out += Row(
                GROUP_LOGOUT,
                "logout",
                "خروج",
                action = Action.LOGOUT,
                requiresLogin = true,
            )
        }
        return out
    }

    /**
     * فقط شمارهٔ canonical یازده‌رقمی که با ۰۹ شروع شود ماسک می‌شود.
     * هر فرمت دیگر فقط «•••» است.
     */
    fun maskPhone(raw: String): String {
        val compact = buildString(raw.length) {
            for (ch in raw) {
                if (ch.isWhitespace()) continue
                append(
                    when (ch) {
                        in '۰'..'۹' -> '0' + (ch - '۰')
                        in '٠'..'٩' -> '0' + (ch - '٠')
                        else -> ch
                    },
                )
            }
        }
        val body = when {
            compact.startsWith("+98") -> "0" + compact.substring(3)
            compact.startsWith("0098") -> "0" + compact.substring(4)
            else -> compact
        }
        if (body.length == 11 && body.startsWith("09") && body.all { it in '0'..'9' }) {
            return persianDigits(body.take(4)) + "•••" + persianDigits(body.takeLast(4))
        }
        return "•••"
    }

    fun avatarLetter(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.first().toString()
    }

    fun persianDigits(value: Int): String = persianDigits(value.toString())

    fun persianDigits(latin: String): String = buildString(latin.length) {
        for (ch in latin) {
            append(if (ch in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[ch - '0'] else ch)
        }
    }

    /** مسیر نسبی داخلی: با `/` شروع شود و با `//` یا `/\` شروع نشود. */
    fun isSafeInternalPath(path: String): Boolean {
        if (!path.startsWith("/")) return false
        if (path.startsWith("//")) return false
        if (path.startsWith("/\\")) return false
        return true
    }
}
