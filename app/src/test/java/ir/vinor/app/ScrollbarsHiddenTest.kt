package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * هیچ صفحه‌ای نوار اسکرول نشان ندهد (گزارش «وینور من»). هر layout، تم، دیالوگ کدی و WebView بررسی می‌شود
 * تا صفحهٔ جدید هم بدون نوار بماند.
 */
class ScrollbarsHiddenTest {
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val d = dir ?: return@repeat
            listOf(File(d, "src/main"), File(d, "app/src/main")).firstOrNull { File(it, "AndroidManifest.xml").isFile }?.let { return it }
            dir = d.parentFile
        }
        throw AssertionError("app/src/main not found")
    }

    private val scrollTag = Regex(
        "<(ScrollView|HorizontalScrollView|androidx\\.core\\.widget\\.NestedScrollView|" +
            "androidx\\.recyclerview\\.widget\\.RecyclerView|ListView|GridView|WebView|" +
            "androidx\\.viewpager2\\.widget\\.ViewPager2)(?=[\\s>/])([^>]*?)/?>",
        RegexOption.DOT_MATCHES_ALL,
    )

    @Test
    fun everyScrollableViewInLayoutsHidesScrollbars() {
        val layouts = root().resolve("res").listFiles { f -> f.isDirectory && f.name.startsWith("layout") }!!
            .flatMap { it.listFiles { f -> f.name.endsWith(".xml") }!!.toList() }
        assertTrue(layouts.size > 20)
        var seen = 0
        val missing = mutableListOf<String>()
        for (f in layouts) {
            for (m in scrollTag.findAll(f.readText())) {
                seen++
                if (!m.groupValues[2].contains("android:scrollbars=\"none\"")) missing += "${f.name}: <${m.groupValues[1]}>"
            }
        }
        assertTrue("expected scrollable views in layouts", seen >= 25)
        assertEquals("scrollable views without android:scrollbars=\"none\"", emptyList<String>(), missing)
    }

    @Test
    fun myVinorProfileScreenHasNoScrollbar() {
        val xml = root().resolve("res/layout/fragment_profile.xml").readText()
        val first = scrollTag.find(xml)!!
        assertEquals("ScrollView", first.groupValues[1])
        assertTrue(first.groupValues[2].contains("android:scrollbars=\"none\""))
        assertTrue(xml.contains("android:text=\"وینور من\""))
    }

    @Test
    fun themesHideScrollbarsGlobally() {
        val themes = root().resolve("res/values/themes.xml").readText()
        for (name in listOf("Theme.Vinor", "Theme.Vinor.FullScreenDialog", "Theme.Vinor.Splash")) {
            val body = themes.substringAfter("<style name=\"$name\"").substringBefore("</style>")
            assertTrue("$name must set android:scrollbars none", body.contains("<item name=\"android:scrollbars\">none</item>"))
        }
        val app = themes.substringAfter("<style name=\"Theme.Vinor\"").substringBefore("</style>")
        for (attr in listOf("android:scrollViewStyle", "android:horizontalScrollViewStyle", "android:listViewStyle",
            "android:webViewStyle", "recyclerViewStyle", "nestedScrollViewStyle", "android:scrollbarThumbVertical")) {
            assertTrue("Theme.Vinor missing $attr", app.contains("<item name=\"$attr\">"))
        }
        val styles = root().resolve("res/values/styles.xml").readText()
        for (w in listOf("ScrollView", "HorizontalScrollView", "ListView", "GridView", "WebView", "RecyclerView", "NestedScrollView")) {
            val body = styles.substringAfter("<style name=\"Widget.Vinor.$w\"").substringBefore("</style>")
            assertTrue("Widget.Vinor.$w", body.contains("<item name=\"android:scrollbars\">none</item>"))
        }
        val manifest = root().resolve("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:theme=\"@style/Theme.Vinor\""))
    }

    @Test
    fun codeBuiltScreensAndDialogsUseThemedContext() {
        val src = root().resolve("java/ir/vinor/app").listFiles { f -> f.name.endsWith(".kt") }!!
        for (f in src) {
            val text = f.readText()
            // دیالوگ تمام‌صفحه با تم سیستم، تم اپ (بدون نوار) را نمی‌گیرد
            assertFalse("${f.name} uses Theme_DeviceDefault for a dialog", text.contains("android.R.style.Theme_DeviceDefault"))
            // هر ScrollView/HorizontalScrollView کدی نوارش را صریحاً خاموش می‌کند
            Regex("\\b(Horizontal)?ScrollView\\((context|ctx|requireContext\\(\\))\\)\\.apply \\{([^\\n]*)").findAll(text).forEach { m ->
                assertTrue("${f.name}: ${m.value}", m.groupValues[3].contains("ScrollBarEnabled = false"))
            }
        }
    }

    @Test
    fun webViewsAndGlobalHookAreWired() {
        val dir = root().resolve("java/ir/vinor/app")
        assertTrue(dir.resolve("WebViewProvider.kt").readText().contains("VinorScrollbars.hideWebView(this)"))
        val base = dir.resolve("BaseWebViewFragment.kt").readText()
        assertTrue(base.contains("VinorScrollbars.hideWebView(webView)"))
        assertTrue(base.substringAfter("override fun onPageFinished").substringBefore("\n            }").contains("VinorScrollbars.injectCss(view)"))
        assertTrue(dir.resolve("VinorApp.kt").readText().contains("VinorScrollbars.install(this)"))
    }

    @Test
    fun injectedCssHidesWebkitAndFirefoxBarsOnce() {
        val js = VinorScrollbars.INJECT_JS
        assertTrue(js.contains("::-webkit-scrollbar{display:none!important"))
        assertTrue(js.contains("scrollbar-width:none!important"))
        assertTrue(js.contains("getElementById('${VinorScrollbars.STYLE_ID}'))return"))
        assertFalse(VinorScrollbars.CSS.contains("'"))
        assertFalse(VinorScrollbars.CSS.contains("overflow:hidden"))  // اسکرول باید کار کند
    }
}
