package ir.vinor.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * دانلود خودکار APK بروزرسانی در پس‌زمینه و نصب با درخواست کاربر.
 */
object VinorApkUpdateInstaller {

    private const val TAG = "VinorApkUpdateDl"
    private const val PREFS = "vinor_apk_update_download"
    private const val KEY_STATUS = "status"
    private const val KEY_VERSION = "version"
    private const val KEY_URL = "url"
    private const val KEY_ERROR = "error"
    private const val APK_DIR = "apk-updates"
    private const val MIN_APK_BYTES = 50_000L

    enum class Status {
        IDLE,
        DOWNLOADING,
        READY,
        FAILED,
    }

    data class DownloadState(
        val status: Status,
        val version: String = "",
        val url: String = "",
        val error: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloading = AtomicBoolean(false)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readState(context: Context): DownloadState {
        val p = prefs(context)
        val raw = p.getString(KEY_STATUS, Status.IDLE.name).orEmpty()
        val status = runCatching { Status.valueOf(raw) }.getOrDefault(Status.IDLE)
        val version = p.getString(KEY_VERSION, "").orEmpty()
        val url = p.getString(KEY_URL, "").orEmpty()
        val file = if (version.isNotEmpty()) apkFileForVersion(context, version) else null
        val resolved = when {
            status == Status.READY && (file == null || !isValidApkFile(file)) -> {
                file?.delete()
                DownloadState(Status.IDLE, version = version, url = url)
            }
            else -> DownloadState(
                status = status,
                version = version,
                url = url,
                error = p.getString(KEY_ERROR, "").orEmpty(),
            )
        }
        return resolved
    }

    private fun persistState(context: Context, state: DownloadState) {
        prefs(context).edit()
            .putString(KEY_STATUS, state.status.name)
            .putString(KEY_VERSION, state.version)
            .putString(KEY_URL, state.url)
            .putString(KEY_ERROR, state.error)
            .apply()
    }

    private fun updatesDir(context: Context): File {
        val dir = File(context.filesDir, APK_DIR)
        if (!dir.exists()) dir.mkdirs()
        // مهاجرت از cache قدیمی در صورت وجود
        try {
            val legacy = File(context.cacheDir, APK_DIR)
            if (legacy.isDirectory) {
                legacy.listFiles()?.forEach { src ->
                    val dest = File(dir, src.name)
                    if (!dest.exists() && src.isFile) {
                        src.copyTo(dest, overwrite = false)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return dir
    }

    private fun apkFileForVersion(context: Context, version: String): File {
        val safe = VinorDownloadBridge.sanitizeFilename("vinor-$version.apk")
        return File(updatesDir(context), safe)
    }

    /** بررسی ZIP + وجود AndroidManifest.xml — جلوگیری از نصب HTML/خطا به‌جای APK. */
    fun isValidApkFile(file: File?): Boolean {
        if (file == null || !file.isFile || file.length() < MIN_APK_BYTES) return false
        return try {
            file.inputStream().use { ins ->
                val magic = ByteArray(4)
                if (ins.read(magic) != 4) return false
                if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) return false
            }
            ZipFile(file).use { zip ->
                zip.getEntry("AndroidManifest.xml") != null
            }
        } catch (e: Exception) {
            Log.w(TAG, "isValidApkFile failed: ${file.name}", e)
            false
        }
    }

    /** دانلود خودکار — اگر فایل همان نسخه موجود باشد، وضعیت READY می‌شود. */
    fun ensureDownload(context: Context, url: String, version: String, webView: WebView? = null) {
        val absUrl = VinorDownloadBridge.resolveAbsoluteUrl(url) ?: return
        val ver = version.trim()
        if (ver.isEmpty() || !UrlUtils.isVinorDomain(absUrl)) return

        val existing = apkFileForVersion(context, ver)
        if (isValidApkFile(existing)) {
            persistState(context, DownloadState(Status.READY, ver, absUrl))
            webView?.let { notifyWebView(it, readState(context)) }
            return
        }
        if (existing.exists()) existing.delete()

        if (!downloading.compareAndSet(false, true)) {
            webView?.let { notifyWebView(it, readState(context)) }
            return
        }

        scope.launch {
            try {
                persistState(context, DownloadState(Status.DOWNLOADING, ver, absUrl))
                webView?.let { notifyWebView(it, readState(context)) }

                val ok = withContext(Dispatchers.IO) {
                    downloadApk(context, absUrl, ver)
                }
                if (ok) {
                    persistState(context, DownloadState(Status.READY, ver, absUrl))
                } else {
                    persistState(
                        context,
                        DownloadState(Status.FAILED, ver, absUrl, "download_failed"),
                    )
                    toast(context, "دانلود نسخه جدید ناموفق بود")
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureDownload failed", e)
                persistState(
                    context,
                    DownloadState(Status.FAILED, ver, absUrl, e.message.orEmpty()),
                )
                toast(context, "خطا در دانلود بروزرسانی")
            } finally {
                downloading.set(false)
                webView?.let { notifyWebView(it, readState(context)) }
            }
        }
    }

    private fun downloadApk(context: Context, absUrl: String, version: String): Boolean {
        val target = apkFileForVersion(context, version)
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            if (target.exists()) target.delete()
            if (tmp.exists()) tmp.delete()

            val clientV = VinorAppUpdateChecker.appVersionName(context)
            val request = Request.Builder()
                .url(absUrl)
                .header("Accept", "application/vnd.android.package-archive,*/*")
                .header("User-Agent", VinorUserAgent.forHttpRequest(context))
                .apply {
                    if (clientV.isNotEmpty()) header("X-Vinor-App-Version", clientV)
                }
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for $absUrl")
                    return false
                }
                val ct = response.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html") || ct.contains("text/plain") || ct.contains("application/json")) {
                    Log.w(TAG, "Unexpected Content-Type=$ct for $absUrl")
                    return false
                }
                val body = response.body ?: return false
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (!isValidApkFile(tmp)) {
                Log.w(TAG, "Downloaded file is not a valid APK size=${tmp.length()}")
                tmp.delete()
                return false
            }
            if (target.exists()) target.delete()
            if (tmp.renameTo(target)) return isValidApkFile(target)
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            return isValidApkFile(target)
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk error: $absUrl", e)
            tmp.delete()
            return false
        }
    }

    /** باز کردن نصب‌گر سیستم برای APK دانلودشده. */
    fun install(context: Context): Boolean {
        val state = readState(context)
        if (state.status != Status.READY || state.version.isBlank()) {
            if (state.url.isNotBlank() && state.version.isNotBlank()) {
                ensureDownload(context, state.url, state.version)
                toast(context, "در حال آماده‌سازی فایل نصب…")
            } else {
                toast(context, "فایل نصب آماده نیست")
            }
            return false
        }

        val file = apkFileForVersion(context, state.version)
        if (!isValidApkFile(file)) {
            file.delete()
            persistState(context, DownloadState(Status.IDLE, state.version, state.url))
            if (state.url.isNotBlank()) {
                ensureDownload(context, state.url, state.version)
            }
            toast(context, "فایل نصب نامعتبر بود؛ دوباره دانلود می‌شود")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = context.packageManager
            if (!pm.canRequestPackageInstalls()) {
                openUnknownSourcesSettings(context)
                return false
            }
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            grantUriToInstallers(context, intent, uri)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            toast(context, "خطا در باز کردن نصب‌گر")
            false
        }
    }

    private fun grantUriToInstallers(context: Context, intent: Intent, uri: Uri) {
        try {
            val matches = context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            for (ri in matches) {
                val pkg = ri.activityInfo?.packageName ?: continue
                context.grantUriPermission(
                    pkg,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "grantUriToInstallers failed", e)
        }
    }

    private fun openUnknownSourcesSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            toast(context, "اجازهٔ نصب از این منبع را فعال کنید")
        } catch (e: Exception) {
            Log.e(TAG, "openUnknownSourcesSettings failed", e)
        }
    }

    private fun toast(context: Context, message: String) {
        val appCtx = context.applicationContext
        if (context is Activity) {
            context.runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        } else {
            android.os.Handler(appCtx.mainLooper).post {
                Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun stateJson(context: Context): String {
        val s = readState(context)
        return JSONObject()
            .put("status", s.status.name.lowercase())
            .put("version", s.version)
            .put("url", s.url)
            .put("error", s.error)
            .put("ready", s.status == Status.READY)
            .put("downloading", s.status == Status.DOWNLOADING)
            .toString()
    }

    fun notifyWebView(webView: WebView, state: DownloadState) {
        val detail = JSONObject()
            .put("status", state.status.name.lowercase())
            .put("version", state.version)
            .put("url", state.url)
            .put("error", state.error)
            .put("ready", state.status == Status.READY)
            .put("downloading", state.status == Status.DOWNLOADING)
        val js = """
            (function () {
              try {
                window.dispatchEvent(new CustomEvent('vinor:app-update:download', { detail: $detail }));
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
}
