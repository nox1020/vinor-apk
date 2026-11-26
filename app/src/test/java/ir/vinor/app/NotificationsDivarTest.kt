package ir.vinor.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** «اعلان‌ها» به سبک دیوار — هم‌تراز با tests/test_notifications_divar.py وب (همان ورودی‌ها، همان خروجی). */
class NotificationsDivarTest {
    private val now = 1_795_681_800L // 2026-11-26 08:30 UTC = 12:00 Tehran

    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val d = dir ?: return@repeat
            listOf(File(d, "src/main"), File(d, "app/src/main")).firstOrNull { File(it, "AndroidManifest.xml").isFile }?.let { return it }
            dir = d.parentFile
        }
        throw AssertionError("app/src/main not found")
    }

    private fun res(path: String) = root().resolve("res/$path").readText()
    private fun kt(name: String) = root().resolve("java/ir/vinor/app/$name").readText()
    private fun j(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }

    @Test
    fun kindClassificationMatchesWeb() {
        assertEquals(NotificationFeed.Kind.VISIT, NotificationFeed.kindOf(j("type" to "visit")))
        assertEquals(NotificationFeed.Kind.VISIT, NotificationFeed.kindOf(j("type" to "info", "source" to "visit_inbox")))
        assertEquals(NotificationFeed.Kind.VISIT, NotificationFeed.kindOf(j("action_url" to "/express/partner/requests?tab=visits")))
        assertEquals(NotificationFeed.Kind.WALLET, NotificationFeed.kindOf(j("title" to "واریز پورسانت")))
        assertEquals(NotificationFeed.Kind.WALLET, NotificationFeed.kindOf(j("action_url" to "/express/partner/commissions")))
        assertEquals(NotificationFeed.Kind.LISTING, NotificationFeed.kindOf(j("title" to "فایل جدید اکسپرس", "type" to "info")))
        assertEquals(NotificationFeed.Kind.LISTING, NotificationFeed.kindOf(j("ad_id" to "1290")))
        assertEquals(NotificationFeed.Kind.GENERAL, NotificationFeed.kindOf(j("type" to "sales_coach", "title" to "نکتهٔ امروز")))
        assertEquals(NotificationFeed.Kind.GENERAL, NotificationFeed.kindOf(j("ad_id" to null)))
    }

    @Test
    fun safeLinkOnlyInternal() {
        assertEquals("/express/partner/requests?tab=visits", NotificationFeed.safeLink("/express/partner/requests?tab=visits"))
        assertEquals("/express/partner/wallet", NotificationFeed.safeLink("https://vinor.ir/express/partner/wallet"))
        assertEquals("/ai", NotificationFeed.safeLink("https://www.vinor.ir/ai"))
        assertNull(NotificationFeed.safeLink("//evil.example/x"))
        assertNull(NotificationFeed.safeLink("https://evil.example/x"))
        assertNull(NotificationFeed.safeLink("javascript:alert(1)"))
        assertNull(NotificationFeed.safeLink(null))
        assertNull(NotificationFeed.safeLink("null"))
    }

    @Test
    fun epochBucketsAndShortTimeMatchWeb() {
        assertEquals(now, NotificationFeed.epochOf(j("created_at" to now)))
        assertEquals(now, NotificationFeed.epochOf(j("created_at" to now * 1000)))
        assertEquals(now, NotificationFeed.epochOf(j("created_at" to "2026-11-26T08:30:00Z")))
        assertNull(NotificationFeed.epochOf(j("created_at" to "nope")))
        assertEquals("همین حالا", NotificationFeed.shortTime(now - 20, now))
        assertEquals("همین حالا", NotificationFeed.shortTime(now + 300, now))
        assertEquals("۹ دقیقه پیش", NotificationFeed.shortTime(now - 9 * 60, now))
        assertEquals("۳ ساعت پیش", NotificationFeed.shortTime(now - 3 * 3600, now))
        assertEquals(NotificationFeed.Day.TODAY, NotificationFeed.dayOf(now - 3 * 3600, now))
        assertEquals(NotificationFeed.Day.YESTERDAY, NotificationFeed.dayOf(now - 13 * 3600, now))
        assertEquals("دیروز", NotificationFeed.shortTime(now - 13 * 3600, now))
        assertEquals("۳ روز پیش", NotificationFeed.shortTime(now - 3 * 86400, now))
        assertEquals(NotificationFeed.Day.OLDER, NotificationFeed.dayOf(now - 3 * 86400, now))
        assertEquals("۱۵ آبان", NotificationFeed.shortTime(now - 20 * 86400, now))
        assertTrue(NotificationFeed.shortTime(now - 400 * 86400, now).endsWith("۱۴۰۴"))
        assertEquals(NotificationFeed.Day.OLDER, NotificationFeed.dayOf(null, now))
        assertEquals("", NotificationFeed.shortTime(null, now))
    }

    private fun items() = listOf(
        j("id" to "a", "title" to "درخواست بازدید", "body" to "x", "type" to "visit", "created_at" to now - 600, "is_read" to false,
            "action_url" to "/express/partner/requests?tab=visits"),
        j("id" to "b", "title" to "واریز پورسانت", "body" to "y", "type" to "info", "created_at" to now - 13 * 3600, "is_read" to true),
        j("id" to "c", "title" to "فایل جدید", "body" to "  z \n w ", "type" to "info", "created_at" to now - 9 * 86400, "is_read" to false,
            "action_url" to "https://evil.example"),
        j("id" to "d", "title" to "plus", "body" to "", "type" to "visit", "created_at" to now, "is_read" to false, "source" to "visit_plus_user"),
    )

    @Test
    fun feedGroupsCountsFiltersAndEmptyStates() {
        val feed = NotificationFeed.build(items(), now)
        assertEquals(listOf("امروز", "دیروز", "قدیمی‌تر"), feed.sections.map { it.day.label })
        assertEquals(3, feed.total)
        assertEquals(2, feed.unread)
        val older = feed.sections[2].rows[0]
        assertEquals(NotificationFeed.Kind.LISTING, older.kind)
        assertNull(older.link)
        assertEquals("z w", older.body)
        val today = feed.sections[0].rows[0]
        assertTrue(today.unread)
        assertEquals("/express/partner/requests?tab=visits", today.link)
        assertNull(NotificationFeed.emptyState(feed, NotificationFeed.Tab.ALL))

        val unread = NotificationFeed.build(items(), now, NotificationFeed.Tab.UNREAD)
        assertEquals(listOf("امروز", "قدیمی‌تر"), unread.sections.map { it.day.label })
        assertTrue(unread.sections.flatMap { it.rows }.all { it.unread })

        val allRead = items().onEach { it.put("is_read", true) }
        val readFeed = NotificationFeed.build(allRead, now, NotificationFeed.Tab.UNREAD)
        assertTrue(readFeed.sections.isEmpty())
        assertEquals(NotificationFeed.EMPTY_UNREAD, NotificationFeed.emptyState(readFeed, NotificationFeed.Tab.UNREAD))
        assertEquals("همه‌ی اعلان‌ها خوانده شدن", NotificationFeed.EMPTY_UNREAD.first)
        val none = NotificationFeed.build(emptyList(), now)
        assertEquals(NotificationFeed.EMPTY_ALL, NotificationFeed.emptyState(none, NotificationFeed.Tab.UNREAD))
        assertEquals("هیچ اعلانی نداری", NotificationFeed.EMPTY_ALL.first)
        assertEquals("۲", NotificationFeed.faDigits(2))
    }

    private fun tag(xml: String, id: String): String {
        val at = xml.indexOf("android:id=\"@+id/$id\"")
        assertTrue("missing @+id/$id", at > 0)
        return xml.substring(xml.lastIndexOf('<', at), xml.indexOf('>', at))
    }

    @Test
    fun layoutIsDivarStyleAndKeepsPullToRefresh() {
        val xml = res("layout/fragment_notifications.xml")
        val ref = res("layout/include_page_header.xml")
        // header = Customers / Tracking / Vinor-man top bar
        for (attr in listOf("android:textSize=\"17sp\"", "android:textStyle=\"bold\"", "android:fontFamily=\"@font/vazirmatn\"")) {
            assertTrue(attr, tag(xml, "notificationsTitle").contains(attr) && tag(ref, "pageTitle").contains(attr))
        }
        assertTrue(tag(xml, "notificationsTitle").contains("android:text=\"اعلان‌ها\""))
        assertTrue(xml.contains("android:background=\"@color/vinor_chrome\"") && xml.contains("android:minHeight=\"56dp\""))
        // filter pills reuse the Home header chip language
        for (id in listOf("notificationsTabAll", "notificationsTabUnread")) {
            val t = tag(xml, id)
            assertTrue(id, t.contains("@drawable/bg_search_chip_state") && t.contains("android:layout_height=\"44dp\""))
        }
        assertTrue(tag(xml, "notificationsUnreadBadge").contains("@drawable/bg_search_chip_badge"))
        val markAll = tag(xml, "notificationsMarkAll")
        assertTrue(markAll.contains("خواندن همه") && markAll.contains("android:visibility=\"gone\""))
        // header no longer holds mark-all
        assertTrue(xml.indexOf("@+id/notificationsMarkAll\"") > xml.indexOf("@+id/notificationsFilters\""))
        assertTrue(tag(xml, "notificationsRefresh").startsWith("<ir.vinor.app.VinorSwipeRefreshLayout"))
        assertTrue(tag(xml, "notificationsScroll").contains("android:scrollbars=\"none\""))
        assertTrue(xml.contains("@+id/notificationsPostchi\""))
        assertTrue(xml.contains("android:background=\"@color/notif_page_bg\""))
        assertTrue(res("values/colors.xml").contains("<color name=\"notif_page_bg\">#050A0E</color>"))
        assertFalse(xml.contains("بازنشر"))
    }

    @Test
    fun rowIsFlatWithHairlineIconDotAndTwoLineBody() {
        val row = res("layout/item_notification.xml")
        val divider = tag(row, "notificationDivider")
        assertTrue(divider.contains("android:layout_height=\"1dp\"") && divider.contains("android:layout_marginStart=\"68dp\""))
        assertTrue(divider.contains("@color/notif_line"))
        assertTrue(tag(row, "notificationIcon").contains("@drawable/bg_notif_icon"))
        assertTrue(tag(row, "notificationBody").contains("android:maxLines=\"2\""))
        assertTrue(tag(row, "notificationUnreadDot").contains("@drawable/bg_notif_dot"))
        assertTrue(tag(row, "notificationChevron").contains("@drawable/ic_chevron_left"))
        assertFalse(row.contains("notificationMarkRead") || row.contains("notificationAction") || row.contains("🔔"))
        for (k in listOf("visit", "wallet", "listing", "general")) {
            assertTrue(k, root().resolve("res/drawable/ic_notif_$k.xml").isFile)
        }
        assertTrue(res("layout/item_notification_section.xml").contains("android:textSize=\"12sp\""))
    }

    @Test
    fun fragmentWiresTabsReadAndInAppLinks() {
        val f = kt("NotificationsFragment.kt")
        for (wire in listOf(
            "binding.notificationsTabAll.setOnClickListener { selectTab(NotificationFeed.Tab.ALL) }",
            "binding.notificationsTabUnread.setOnClickListener { selectTab(NotificationFeed.Tab.UNREAD) }",
            "binding.notificationsMarkAll.setOnClickListener",
            "binding.notificationsRefresh.setOnRefreshListener { loadNotifications(pull = true) }",
            "(activity as? MainActivity)?.openPath(NotificationFeed.POSTCHI_PATH)",
            "row.link?.let { (activity as? MainActivity)?.openPath(it) }",
            "NotificationFeed.build(items, System.currentTimeMillis() / 1000, tab)",
            "markScope.launch",
            "binding.notificationsRefresh.isRefreshing = false",
            "VisitAudience.showNotification(row, me)",
            "AccountPaint.isStale",
        )) assertTrue(wire, f.contains(wire))
        // old bugs: raw epoch shown via ISO parser, relative links via ACTION_VIEW
        assertFalse(f.contains("formatDate("))
        assertFalse(f.contains("Intent.ACTION_VIEW"))
    }
}
