package ir.vinor.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * نوار اسکرول در هیچ صفحه‌ای دیده نشود (هم‌ارز CSS وب v1752). اسکرول خودش کار می‌کند؛ فقط رسم نوار خاموش است.
 *
 * لایه‌ها:
 *  1. تم‌ها (Theme.Vinor، Theme.Vinor.Splash، Theme.Vinor.FullScreenDialog): android:scrollbars=none + انگشتک شفاف،
 *     پس هر ScrollView/RecyclerView/WebView که با context تم‌دار ساخته شود (XML یا کد) نوار ندارد.
 *  2. layoutها: android:scrollbars="none" صریح روی هر نمای اسکرول‌شونده (تست LayoutScrollbarsTest).
 *  3. [install]: بعد از ساخت نمای هر Fragment و هر بار resume اکتیویتی، کل درخت خاموش می‌شود (ViewPager2 داخلی، نماهای کدی).
 *  4. WebViewها: [hideWebView] + تزریق CSS برای صفحه‌هایی که خودشان پنهان نمی‌کنند.
 */
object VinorScrollbars {
    const val STYLE_ID = "vinor-apk-no-scrollbars"
    const val CSS = "html,body,*{scrollbar-width:none!important;-ms-overflow-style:none!important}" +
        "*::-webkit-scrollbar{display:none!important;width:0!important;height:0!important;background:transparent!important}"

    /** اسکریپت idempotent: یک بار <style id=vinor-apk-no-scrollbars> اضافه می‌کند. */
    val INJECT_JS: String = "(function(){try{if(document.getElementById('" + STYLE_ID + "'))return;" +
        "var s=document.createElement('style');s.id='" + STYLE_ID + "';s.textContent='" + CSS + "';" +
        "(document.head||document.documentElement).appendChild(s);}catch(e){}})();"

    /** نوار عمودی و افقی این نما و همهٔ فرزندانش را خاموش می‌کند. */
    fun hide(view: View?) {
        if (view == null) return
        if (view.isVerticalScrollBarEnabled) view.isVerticalScrollBarEnabled = false
        if (view.isHorizontalScrollBarEnabled) view.isHorizontalScrollBarEnabled = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) hide(view.getChildAt(i))
        }
    }

    fun hideWebView(webView: WebView?) {
        if (webView == null) return
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    }

    fun injectCss(webView: WebView?) {
        runCatching { webView?.evaluateJavascript(INJECT_JS, null) }
    }

    private val fragmentHook = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            hide(v)
            // محتوای بعداً اضافه‌شده (لیست‌ها، نماهای کدی) هم بعد از اولین چیدمان
            v.post { hide(v) }
        }
    }

    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                (activity as? FragmentActivity)?.supportFragmentManager
                    ?.registerFragmentLifecycleCallbacks(fragmentHook, true)
            }
            override fun onActivityResumed(activity: Activity) {
                activity.window?.decorView?.let { decor -> decor.post { hide(decor) } }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
