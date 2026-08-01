package ir.vinor.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * بررسی نسخه APK از سرور و همگام‌سازی با فوتر وب (رویداد vinor:app-update:nav).
 */
object VinorAppUpdateChecker {

    private const val TAG = "VinorAppUpdate"
    private const val PREFS = "vinor_app_update"
    private const val KEY_SHOW = "show"
    private const val KEY_VERSION = "version"
    private const val KEY_URL = "url"
    private const val KEY_SOURCE_URL = "source_url"
    private const val BASE = "https://vinor.ir"
    private val VERSION_URLS = arrayOf(
        "$BASE/api/express/app-version",
        "$BASE/api/app/version",
    )

    private const val MIN_CHECK_INTERVAL_MS = 15 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val checking = AtomicBoolean(false)
    @Volatile
    private var lastCheckAtMs: Long = 0L

    data class UpdateState(
        val show: Boolean,
        val version: String = "",
        val url: String = "",
        val sourceUrl: String = "",
    )

    fun appVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readCached(context: Context): UpdateState {
        val p = prefs(context)
        return UpdateState(
            show = p.getBoolean(KEY_SHOW, false),
            version = p.getString(KEY_VERSION, "").orEmpty(),
            url = p.getString(KEY_URL, "").orEmpty(),
            sourceUrl = p.getString(KEY_SOURCE_URL, "").orEmpty(),
        )
    }

    private fun persist(context: Context, state: UpdateState) {
        prefs(context).edit()
            .putBoolean(KEY_SHOW, state.show)
            .putString(KEY_VERSION, state.version)
            .putString(KEY_URL, state.url)
            .putString(KEY_SOURCE_URL, state.sourceUrl)
            .apply()
    }

    /** بررسی از سرور؛ در صورت خطای شبکه وضعیت قبلی حفظ می‌شود. */
    fun check(context: Context, webView: WebView? = null, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && lastCheckAtMs > 0L && now - lastCheckAtMs < MIN_CHECK_INTERVAL_MS) {
            val cached = readCached(context)
            webView?.let { applyToWebView(it, cached) }
            if (cached.show) {
                VinorApkUpdateInstaller.ensureDownload(
                    context,
                    cached.url,
                    cached.version,
                    webView,
                    cached.sourceUrl,
                )
            }
            return
        }
        if (!checking.compareAndSet(false, true)) {
            val cached = readCached(context)
            webView?.let { applyToWebView(it, cached) }
            if (cached.show) {
                VinorApkUpdateInstaller.ensureDownload(
                    context,
                    cached.url,
                    cached.version,
                    webView,
                    cached.sourceUrl,
                )
            }
            return
        }
        lastCheckAtMs = now
        scope.launch {
            try {
                val clientV = appVersionName(context)
                if (clientV.isEmpty()) {
                    val cleared = UpdateState(false)
                    persist(context, cleared)
                    webView?.let { applyToWebView(it, cleared) }
                    return@launch
                }
                val previous = readCached(context)
                val state = withContext(Dispatchers.IO) {
                    fetchUpdateState(clientV) ?: previous
                }
                persist(context, state)
                webView?.let { applyToWebView(it, state) }
                if (state.show) {
                    VinorApkUpdateInstaller.ensureDownload(
                        context,
                        state.url,
                        state.version,
                        webView,
                        state.sourceUrl,
                    )
                } else if (previous.show) {
                    VinorApkUpdateInstaller.clearState(context)
                    webView?.let {
                        VinorApkUpdateInstaller.notifyWebView(
                            it,
                            VinorApkUpdateInstaller.DownloadState(
                                VinorApkUpdateInstaller.Status.IDLE,
                            ),
                        )
                    }
                }
            } finally {
                checking.set(false)
            }
        }
    }

    private fun fetchUpdateState(clientV: String): UpdateState? {
        for (urlStr in VERSION_URLS) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 12_000
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Vinor-App-Version", clientV)
                    setRequestProperty("User-Agent", "VinorApp/WebViewOnly/$clientV")
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) continue
                val serverV = json.optString("android_apk_version", "").trim()
                val apkUrlRaw = json.optString("android_apk_url", "").trim()
                val apkUrl = VinorDownloadBridge.resolveAbsoluteUrl(apkUrlRaw).orEmpty()
                val sourceRaw = json.optString("android_apk_source_url", "").trim()
                val sourceUrl = VinorDownloadBridge.resolveAbsoluteUrl(sourceRaw).orEmpty()
                return if (
                    serverV.isNotEmpty() &&
                    apkUrl.isNotEmpty() &&
                    UrlUtils.isTrustedVinorHttps(apkUrl) &&
                    (UrlUtils.isApkDownloadUrl(apkUrl) || UrlUtils.isSafePublicDownloadUrl(apkUrl)) &&
                    ApkVersionUtils.isServerNewer(serverV, clientV)
                ) {
                    UpdateState(
                        show = true,
                        version = serverV,
                        url = apkUrl,
                        sourceUrl = sourceUrl,
                    )
                } else {
                    UpdateState(show = false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Version check failed for $urlStr: ${e.message}")
            }
        }
        return null
    }

    fun applyToWebView(webView: WebView, state: UpdateState) {
        val detailJson = if (state.show) {
            JSONObject()
                .put("show", true)
                .put("version", state.version)
                .put("url", state.url)
                .toString()
        } else {
            JSONObject().put("show", false).toString()
        }
        val js = """
            (function () {
              try {
                window.dispatchEvent(new CustomEvent('vinor:app-update:nav', { detail: $detailJson }));
              } catch (_) {}
            })();
        """.trimIndent()
        webView.post {
            try {
                webView.evaluateJavascript(js, null)
            } catch (_: Exception) {
            }
        }
    }

    fun syncCachedToWebView(context: Context, webView: WebView) {
        val cached = readCached(context)
        applyToWebView(webView, cached)
        if (cached.show) {
            VinorApkUpdateInstaller.ensureDownload(
                context,
                cached.url,
                cached.version,
                webView,
                cached.sourceUrl,
            )
        } else {
            val dl = VinorApkUpdateInstaller.readState(context)
            if (dl.status != VinorApkUpdateInstaller.Status.IDLE) {
                VinorApkUpdateInstaller.notifyWebView(webView, dl)
            }
        }
    }
}
