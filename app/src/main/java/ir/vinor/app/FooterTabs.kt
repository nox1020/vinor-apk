package ir.vinor.app

/**
 * تب روشن فوتر از ریشهٔ بک‌استک است، نه از صفحهٔ رویی.
 * صفحهٔ آموزش روی خانه، تب خانه را روشن نگه می‌دارد؛ همان صفحه از وینور من، تب من را.
 */
object FooterTabs {

    enum class Tab { ADS, CUSTOMERS, POST, FOLLOW, ME }

    data class Ids(
        val dashboard: Int,
        val customers: Int,
        val profile: Int,
        val myProperties: Int,
        val propertyForm: Int,
        val addHub: Int = 0,
        val follow: Int = 0,
    )

    /** تب «پیگیری» جای «AI چت» است؛ چت دیگر تبی روشن نمی‌کند. */
    fun selected(stack: List<Int>, chatOpen: Boolean, ids: Ids, followOpen: Boolean = false): Tab {
        // بک‌استک با خودِ گراف ناوبری شروع می‌شود؛ ریشهٔ تب اولین صفحه بعد از خانه است.
        val homeAt = stack.indexOf(ids.dashboard)
        val pages = if (homeAt >= 0) stack.subList(homeAt, stack.size) else stack
        if (pages.isEmpty() || pages.last() == ids.dashboard) {
            return Tab.ADS
        }
        return when (pages.getOrNull(1)) {
            ids.follow -> if (followOpen && ids.follow != 0) Tab.FOLLOW else Tab.ADS
            ids.customers -> Tab.CUSTOMERS
            ids.profile -> Tab.ME
            ids.addHub, ids.myProperties, ids.propertyForm -> Tab.POST
            else -> Tab.ADS
        }
    }
}
