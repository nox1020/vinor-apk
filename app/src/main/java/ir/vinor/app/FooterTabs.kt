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
        if (stack.isEmpty() || stack.last() == ids.dashboard) {
            return if (chatOpen) Tab.CHAT else Tab.ADS
        }
        val root = if (stack.first() == ids.dashboard) stack.getOrNull(1) else stack.first()
        return when (root) {
            ids.search -> Tab.SEARCH
            ids.profile -> Tab.ME
            ids.myProperties, ids.propertyForm -> Tab.POST
            else -> Tab.ADS
        }
    }
}
