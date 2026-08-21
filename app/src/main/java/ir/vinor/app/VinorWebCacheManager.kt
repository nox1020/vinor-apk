package ir.vinor.app

import android.content.Context
import android.os.Build
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * چرخهٔ عمر کش WebView + Service Worker پنل همکاران.
 *
 * HTML پنل `no-store` است؛ کش آفلاین واقعی:
 * SW runtime (VinorApp UA) + snapshot نیتیو + asset/API cache نسخه‌دار.
 */
object VinorWebCacheManager {

    private const val TAG = "VinorWebCache"
    private const val PREFS = "vinor_web_cache"
    private const val KEY_VERSION_CODE = "last_version_code"
    private const val KEY_PURGE_PENDING = "purge_pending"
    private const val KEY_SCHEMA = "cache_schema"

    private val WARMUP_URLS = listOf(
        "/express/partner/login",
        "/express/partner/offline",
        "/static/express-partner/skeleton-shell.html",
        "/static/express-partner/express-nav.js",
        "/static/express-partner/dashboard-chrome.js",
        "/static/express-partner/land-detail.js",
        "/static/fonts/vazirmatn.css",
        "/static/vendor/fontawesome/css/all.min.css",
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

    fun onAppStart(context: Context, webView: WebView?, isOnline: Boolean) {
        VinorOfflinePageStore.migrateLegacy(context)
        VinorOfflineAssetStore.clearPublicIfCorrupted(context)
        val current = versionCode(context)
        val pref = prefs(context)
        val stored = pref.getInt(KEY_VERSION_CODE, -1)
        val schema = pref.getInt(KEY_SCHEMA, 0)
        var shouldPurge = pref.getBoolean(KEY_PURGE_PENDING, false)
        if (stored >= 0 && stored != current) {
            shouldPurge = true
            pref.edit().putBoolean(KEY_PURGE_PENDING, true).apply()
        }
        if (schema != 0 && schema != VinorCachePolicy.SCHEMA_VERSION && isOnline) {
            shouldPurge = true
        }
        if (shouldPurge && isOnline) {
            VinorCacheLog.event("CACHE_DELETE", "stale-http-sw")
            purgeHttpDiskCache(webView)
            clearServiceWorkerCaches(webView, includePrecache = false)
            pref.edit().putBoolean(KEY_PURGE_PENDING, false).apply()
        }
        pref.edit()
            .putInt(KEY_VERSION_CODE, current)
            .putInt(KEY_SCHEMA, VinorCachePolicy.SCHEMA_VERSION)
            .apply()
    }

    fun onLogout(context: Context, webView: WebView?) {
        VinorCacheLog.event("CACHE_DELETE", "logout")
        clearServiceWorkerCaches(webView, includePrecache = true)
        purgeWebStorage(webView)
        purgeHttpDiskCache(webView)
        try {
            webView?.clearHistory()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "clearHistory failed", e)
        }
        VinorOfflinePageStore.clearUser(context)
        VinorApiCacheStore.clearUser(context)
        VinorOfflineQueue.clearUser(context)
        VinorCacheSession.clearPrefs(context)
        VinorWebOfflineSession.clear(context)
    }

    fun unregisterServiceWorkers(webView: WebView?) {
        if (webView == null) return
        val js = """
            (function () {
              try {
                if (!navigator.serviceWorker) return;
                navigator.serviceWorker.getRegistrations().then(function (rs) {
                  rs.forEach(function (r) { r.unregister(); });
                });
              } catch (_) {}
            })();
        """.trimIndent()
        VinorSafe.evalJs(webView, js)
    }

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
        VinorSafe.evalJs(webView, js)
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
        VinorSafe.evalJs(webView, js)
    }

    private fun purgeWebStorage(webView: WebView?) {
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "WebStorage purge failed", e)
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
            android.util.Log.w(TAG, "WebView clearCache failed", e)
        }
    }
}
