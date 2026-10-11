package ir.vinor.app

/**
 * تب روشن فوتر از ریشهٔ بک‌استک است، نه از صفحهٔ رویی.
 * صفحهٔ آموزش روی خانه تب آگهی‌ها را روشن نگه می‌دارد؛ همان صفحه از وینور من، تب من را.
 */
object FooterTabs {

    enum class Tab { ADS, SEARCH, POST, CHAT, ME }

    data class Ids(
        val dashboard: Int,
        val search: Int,
        val profile: Int,
        val myProperties: Int,
        val propertyForm: Int,
    )

    fun selected(stack: List<Int>, chatOpen: Boolean, ids: Ids): Tab {
        // بک‌استک با خودِ گراف ناوبری شروع می‌شود؛ ریشهٔ تب اولین صفحه بعد از خانه است.
        val homeAt = stack.indexOf(ids.dashboard)
        val pages = if (homeAt >= 0) stack.subList(homeAt, stack.size) else stack
        if (pages.isEmpty() || pages.last() == ids.dashboard) {
            return if (chatOpen) Tab.CHAT else Tab.ADS
        }
        return when (pages.getOrNull(1)) {
            ids.search -> Tab.SEARCH
            ids.profile -> Tab.ME
            ids.myProperties, ids.propertyForm -> Tab.POST
            else -> Tab.ADS
        }
    }
}
