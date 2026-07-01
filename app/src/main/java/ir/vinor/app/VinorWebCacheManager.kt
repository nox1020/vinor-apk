package ir.vinor.app

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * چرخهٔ عمر کش WebView + Service Worker پنل همکاران.
 *
 * HTML پنل `no-store` است؛ کش آفلاین واقعی از SW runtime (فقط VinorApp UA) + WebView زنده.
 */
object VinorWebCacheManager {

    private const val TAG = "VinorWebCache"
    private const val PREFS = "vinor_web_cache"
    private const val KEY_VERSION_CODE = "last_version_code"

    private val WARMUP_URLS = listOf(
        "/express/partner/login",
        "/express/partner/offline",
        "/static/express-partner/skeleton-shell.html",
        "/static/express-partner/express-nav.js",
        "/static/express-partner/dashboard-chrome.js",
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun versionCode(context: Context): Int = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else info.versionCode
    } catch (_: Exception) {
        0
    }

    /** پس از ساخت WebView — پاک‌سازی کش کهنه پس از آپدیت APK */
    fun onAppStart(context: Context, webView: WebView?) {
        val current = versionCode(context)
        val stored = prefs(context).getInt(KEY_VERSION_CODE, -1)
        if (stored >= 0 && stored != current) {
            Log.i(TAG, "APK upgraded $stored -> $current; purging stale web caches")
            purgeHttpDiskCache(webView)
            clearServiceWorkerCaches(webView, includePrecache = false)
        }
        if (stored != current) {
            prefs(context).edit().putInt(KEY_VERSION_CODE, current).apply()
        }
    }

    /** خروج از حساب — کوکی‌ها جداگانه در MainActivity پاک می‌شوند */
    fun onLogout(context: Context, webView: WebView?) {
        Log.i(TAG, "logout purge")
        clearServiceWorkerCaches(webView, includePrecache = true)
        purgeWebStorage(webView)
        purgeHttpDiskCache(webView)
        try {
            webView?.clearHistory()
        } catch (e: Exception) {
            Log.w(TAG, "clearHistory failed", e)
        }
        VinorWebOfflineSession.clear(context)
    }

    /** پس از لود موفق پنل — پر کردن precache/runtime در SW */
    fun warmupPartnerCaches(webView: WebView?) {
        if (webView == null) return
        val urlsJson = WARMUP_URLS.joinToString(",") { "\"$it\"" }
        val js = """
            (function () {
              try {
                if (!navigator.serviceWorker || !navigator.serviceWorker.controller) return;
                navigator.serviceWorker.controller.postMessage({
                  type: 'VINOR_WARMUP_APK',
                  urls: [$urlsJson]
                });
              } catch (_) {}
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }

    fun clearServiceWorkerCaches(webView: WebView?, includePrecache: Boolean) {
        if (webView == null) return
        val flag = if (includePrecache) "true" else "false"
        val js = """
            (function () {
              try {
                if (navigator.serviceWorker && navigator.serviceWorker.controller) {
                  navigator.serviceWorker.controller.postMessage({
                    type: 'CLEAR_PARTNER_ALL',
                    includePrecache: $flag
                  });
                }
                if (window.caches && window.caches.keys) {
                  window.caches.keys().then(function (keys) {
                    return Promise.all(keys.filter(function (k) {
                      return k.indexOf('express-partner-') === 0;
                    }).map(function (k) { return window.caches.delete(k); }));
                  });
                }
              } catch (_) {}
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun purgeWebStorage(webView: WebView?) {
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            Log.w(TAG, "WebStorage purge failed", e)
        }
        try {
            webView?.clearFormData()
        } catch (_: Exception) {
        }
    }

    private fun purgeHttpDiskCache(webView: WebView?) {
        try {
            webView?.clearCache(true)
        } catch (e: Exception) {
            Log.w(TAG, "WebView clearCache failed", e)
        }
    }
}
