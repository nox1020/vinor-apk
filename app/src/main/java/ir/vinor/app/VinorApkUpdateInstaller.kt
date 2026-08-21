package ir.vinor.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private const val PUBLIC_D_SLASH = "https://vinor.ir/d/"
    private const val PUBLIC_D = "https://vinor.ir/d"
    private const val MAX_REDIRECTS = 5
    private const val MAX_ATTEMPTS = 3
    private const val FAIL_TOAST_COOLDOWN_MS = 10 * 60 * 1000L

    enum class Status {
        IDLE,
        DOWNLOADING,
        READY,
        FAILED,
    }

    private enum class DlOutcome {
        OK,
        TRANSIENT,
        INVALID_APK,
        UNTRUSTED,
    }

    data class DownloadState(
        val status: Status,
        val version: String = "",
        val url: String = "",
        val error: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloading = AtomicBoolean(false)
    private val lastFailToastAtMs = AtomicLong(0L)

    /** Manual redirects — never follow login-gate HTML pages as APK bytes. */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readState(context: Context): DownloadState {
        val p = prefs(context)
        val raw = p.getString(KEY_STATUS, Status.IDLE.name).orEmpty()
        val status = runCatching { Status.valueOf(raw) }.getOrDefault(Status.IDLE)
        val version = p.getString(KEY_VERSION, "").orEmpty()
        val url = p.getString(KEY_URL, "").orEmpty()
        val error = p.getString(KEY_ERROR, "").orEmpty()
        val file = if (version.isNotEmpty()) apkFileForVersion(context, version) else null
        val resolved = when {
            // بعد از kill پروسه، AtomicBoolean ریست می‌شود ولی prefs روی DOWNLOADING می‌ماند
            status == Status.DOWNLOADING && !downloading.get() -> {
                DownloadState(Status.IDLE, version = version, url = url, error = "stale_download")
            }
            status == Status.READY && (file == null || !VinorApkTrust.isTrustedUpdateApk(context, file)) -> {
                file?.delete()
                DownloadState(Status.IDLE, version = version, url = url)
            }
            else -> DownloadState(
                status = status,
                version = version,
                url = url,
                error = error,
            )
        }
        // وضعیت اصلاح‌شده را پایدار کن تا UI روی downloading گیر نکند
        if (resolved.status != status) {
            persistState(context, resolved)
        }
        return resolved
    }

    fun clearState(context: Context) {
        persistState(context, DownloadState(Status.IDLE))
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
    fun ensureDownload(
        context: Context,
        url: String,
        version: String,
        webView: WebView? = null,
        sourceUrl: String = "",
    ) {
        val absUrl = VinorDownloadBridge.resolveAbsoluteUrl(url) ?: return
        val ver = version.trim()
        if (ver.isEmpty() || !UrlUtils.isTrustedVinorHttps(absUrl)) return
        if (!UrlUtils.isApkDownloadUrl(absUrl) && !UrlUtils.isSafePublicDownloadUrl(absUrl)) return

        val existing = apkFileForVersion(context, ver)
        if (VinorApkTrust.isTrustedUpdateApk(context, existing)) {
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

                val candidates = candidateDownloadUrls(absUrl, sourceUrl)
                var outcome = DlOutcome.TRANSIENT
                var usedUrl = absUrl
                for (attempt in 0 until MAX_ATTEMPTS) {
                    for (candidate in candidates) {
                        usedUrl = candidate
                        outcome = withContext(Dispatchers.IO) {
                            downloadApk(context, candidate, ver)
                        }
                        if (outcome == DlOutcome.OK) break
                        if (outcome == DlOutcome.UNTRUSTED) break
                    }
                    if (outcome == DlOutcome.OK || outcome == DlOutcome.UNTRUSTED) break
                    if (attempt < MAX_ATTEMPTS - 1) {
                        delay(1_500L * (attempt + 1))
                    }
                }

                when (outcome) {
                    DlOutcome.OK -> {
                        persistState(context, DownloadState(Status.READY, ver, usedUrl))
                    }
                    DlOutcome.UNTRUSTED -> {
                        persistState(
                            context,
                            DownloadState(Status.FAILED, ver, usedUrl, "signature_mismatch"),
                        )
                        toastFailure(
                            context,
                            "فایل بروزرسانی با امضای نصب‌شده سازگار نیست",
                            force = true,
                        )
                    }
                    DlOutcome.INVALID_APK -> {
                        persistState(
                            context,
                            DownloadState(Status.FAILED, ver, usedUrl, "invalid_apk"),
                        )
                        toastFailure(context, "فایل دانلودشده معتبر نیست؛ بعداً دوباره تلاش می‌شود")
                    }
                    DlOutcome.TRANSIENT -> {
                        persistState(
                            context,
                            DownloadState(Status.FAILED, ver, usedUrl, "download_failed"),
                        )
                        // بدون toast روی cold-start — نوار وب وضعیت failed را نشان می‌دهد؛ بعداً دوباره تلاش می‌شود
                        Log.w(TAG, "Transient APK download failure after retries ver=$ver")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureDownload failed", e)
                persistState(
                    context,
                    DownloadState(Status.FAILED, ver, absUrl, e.message.orEmpty()),
                )
                toastFailure(context, "خطا در دانلود بروزرسانی")
            } finally {
                downloading.set(false)
                webView?.let { notifyWebView(it, readState(context)) }
            }
        }
    }

    private fun candidateDownloadUrls(primary: String, sourceUrl: String): List<String> {
        val out = linkedSetOf<String>()
        fun add(raw: String) {
            val abs = VinorDownloadBridge.resolveAbsoluteUrl(raw) ?: return
            if (!UrlUtils.isTrustedVinorHttps(abs)) return
            if (!UrlUtils.isApkDownloadUrl(abs) && !UrlUtils.isSafePublicDownloadUrl(abs)) return
            out.add(abs)
        }
        add(primary)
        // /d/ بدون 308؛ سپس /d (ممکن است 308 بخورد ولی با redirect دستی امن است)
        add(PUBLIC_D_SLASH)
        add(PUBLIC_D)
        add(sourceUrl)
        return out.toList()
    }

    private fun downloadApk(context: Context, absUrl: String, version: String): DlOutcome {
        val target = apkFileForVersion(context, version)
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            if (target.exists()) target.delete()
            if (tmp.exists()) tmp.delete()

            val clientV = VinorAppUpdateChecker.appVersionName(context)
            var currentUrl = absUrl
            for (hop in 0 until MAX_REDIRECTS) {
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("Accept", "application/vnd.android.package-archive,*/*")
                    .header("User-Agent", VinorUserAgent.forHttpRequest(context))
                    .apply {
                        if (clientV.isNotEmpty()) header("X-Vinor-App-Version", clientV)
                    }
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code in 300..399) {
                        val loc = response.header("Location").orEmpty().trim()
                        if (loc.isEmpty()) {
                            Log.w(TAG, "Redirect without Location from $currentUrl")
                            return DlOutcome.TRANSIENT
                        }
                        val next = resolveRedirectUrl(currentUrl, loc) ?: run {
                            Log.w(TAG, "Bad redirect Location=$loc from $currentUrl")
                            return DlOutcome.TRANSIENT
                        }
                        if (!UrlUtils.isTrustedVinorHttps(next)) {
                            Log.w(TAG, "Redirect left trusted host: $next")
                            return DlOutcome.TRANSIENT
                        }
                        // گیت لاگین معمولاً به /express/partner/home می‌فرستد — دنبال نکن
                        if (!UrlUtils.isApkDownloadUrl(next) && !UrlUtils.isSafePublicDownloadUrl(next)) {
                            Log.w(TAG, "Redirect is not an APK download path: $next")
                            return DlOutcome.TRANSIENT
                        }
                        currentUrl = next
                        return@use
                    }
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP $code for $currentUrl")
                        return DlOutcome.TRANSIENT
                    }
                    val ct = response.header("Content-Type").orEmpty().lowercase()
                    if (ct.contains("text/html") || ct.contains("application/json")) {
                        Log.w(TAG, "Unexpected Content-Type=$ct for $currentUrl")
                        return DlOutcome.TRANSIENT
                    }
                    val body = response.body ?: return DlOutcome.TRANSIENT
                    val expectedLen = body.contentLength()
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (expectedLen > 0L && tmp.length() != expectedLen) {
                        Log.w(
                            TAG,
                            "Incomplete download size=${tmp.length()} expected=$expectedLen",
                        )
                        tmp.delete()
                        return DlOutcome.TRANSIENT
                    }
                    return finalizeDownloadedApk(context, tmp, target)
                }
            }
            Log.w(TAG, "Too many redirects for $absUrl")
            return DlOutcome.TRANSIENT
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk error: $absUrl", e)
            tmp.delete()
            return DlOutcome.TRANSIENT
        }
    }

    private fun finalizeDownloadedApk(context: Context, tmp: File, target: File): DlOutcome {
        if (looksLikeHtml(tmp)) {
            Log.w(TAG, "Downloaded HTML instead of APK size=${tmp.length()}")
            tmp.delete()
            return DlOutcome.TRANSIENT
        }
        if (!isValidApkFile(tmp)) {
            Log.w(TAG, "Downloaded file is not a valid APK size=${tmp.length()}")
            tmp.delete()
            return DlOutcome.INVALID_APK
        }
        if (!VinorApkTrust.isTrustedUpdateApk(context, tmp)) {
            Log.w(TAG, "Downloaded APK failed package/signature trust check")
            tmp.delete()
            return DlOutcome.UNTRUSTED
        }
        if (target.exists()) target.delete()
        val ready = if (tmp.renameTo(target)) {
            VinorApkTrust.isTrustedUpdateApk(context, target)
        } else {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            VinorApkTrust.isTrustedUpdateApk(context, target)
        }
        return if (ready) DlOutcome.OK else DlOutcome.UNTRUSTED
    }

    private fun looksLikeHtml(file: File): Boolean {
        return try {
            file.inputStream().use { ins ->
                val buf = ByteArray(64)
                val n = ins.read(buf)
                if (n <= 0) return false
                val head = buf.copyOf(n).toString(Charsets.UTF_8).trimStart().lowercase()
                head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveRedirectUrl(fromUrl: String, location: String): String? {
        val loc = location.trim()
        if (loc.isEmpty()) return null
        return try {
            when {
                loc.startsWith("https://", ignoreCase = true) -> loc
                loc.startsWith("http://", ignoreCase = true) ->
                    "https://" + loc.substring(7)
                loc.startsWith("//") -> "https:$loc"
                else -> URI(fromUrl).resolve(loc).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun toastFailure(context: Context, message: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val prev = lastFailToastAtMs.get()
        if (!force && prev > 0L && now - prev < FAIL_TOAST_COOLDOWN_MS) {
            Log.i(TAG, "Suppress fail toast (cooldown): $message")
            return
        }
        lastFailToastAtMs.set(now)
        toast(context, message)
    }

    /** باز کردن نصب‌گر سیستم برای APK دانلودشده (PackageInstaller — قابل‌اعتمادتر از FileProvider). */
    fun install(context: Context): Boolean {
        val appCtx = context.applicationContext
        val state = readState(appCtx)
        if (state.status != Status.READY || state.version.isBlank()) {
            if (state.url.isNotBlank() && state.version.isNotBlank()) {
                ensureDownload(appCtx, state.url, state.version)
                toast(context, "در حال آماده‌سازی فایل نصب…")
            } else {
                toast(context, "فایل نصب آماده نیست")
            }
            return false
        }

        val file = apkFileForVersion(appCtx, state.version)
        if (!VinorApkTrust.isTrustedUpdateApk(appCtx, file)) {
            file.delete()
            persistState(appCtx, DownloadState(Status.IDLE, state.version, state.url))
            if (state.url.isNotBlank()) {
                ensureDownload(appCtx, state.url, state.version)
            }
            toast(context, "فایل نصب نامعتبر بود؛ دوباره دانلود می‌شود")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = appCtx.packageManager
            if (!pm.canRequestPackageInstalls()) {
                openUnknownSourcesSettings(context)
                return false
            }
        }

        val downgradeMsg = VinorApkTrust.downgradeOrConflictMessage(appCtx, file)
        if (downgradeMsg != null) {
            toast(context, downgradeMsg)
            return false
        }

        // کپی چندمگابایتی روی thread پل JS نباشد
        scope.launch(Dispatchers.IO) {
            try {
                commitPackageInstallerSession(appCtx, file)
            } catch (e: Exception) {
                Log.e(TAG, "PackageInstaller failed — fallback", e)
                withContext(Dispatchers.Main) {
                    try {
                        installViaFileProvider(context, file)
                    } catch (e2: Exception) {
                        Log.e(TAG, "install fallback failed", e2)
                        if (state.url.isNotBlank()) {
                            VinorDownloadBridge.enqueueDownload(
                                appCtx,
                                state.url,
                                VinorDownloadBridge.apkDownloadFilename(state.url, state.version),
                                showToast = true,
                            )
                            toast(context, "از اعلان دانلود، فایل را نصب کنید")
                        } else {
                            toast(context, "خطا در باز کردن نصب‌گر")
                        }
                    }
                }
            }
        }
        return true
    }

    private fun commitPackageInstallerSession(context: Context, apkFile: File): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        try {
            params.setAppPackageName(context.packageName)
        } catch (_: Exception) {
        }
        try {
            params.setSize(apkFile.length())
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apkFile.inputStream().use { input ->
                session.openWrite("vinor-update.apk", 0, apkFile.length()).use { out ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                    session.fsync(out)
                }
            }
            val callback = Intent(context, ApkInstallResultReceiver::class.java).apply {
                action = ApkInstallResultReceiver.ACTION
                setPackage(context.packageName)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            try {
                session.abandon()
            } catch (_: Exception) {
            }
            throw e
        } finally {
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
        Log.i(TAG, "PackageInstaller session committed id=$sessionId size=${apkFile.length()}")
        return true
    }

    private fun installViaFileProvider(context: Context, file: File): Boolean {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            // بعضی OEMها بدون ClipData مجوز URI را به نصب‌گر نمی‌دهند
            clipData = android.content.ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        grantUriToInstallers(context, intent, uri)
        context.applicationContext.startActivity(intent)
        return true
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
            val appCtx = context.applicationContext
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${appCtx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appCtx.startActivity(intent)
            toast(appCtx, "اجازهٔ نصب از این منبع را فعال کنید")
        } catch (e: Exception) {
            Log.e(TAG, "openUnknownSourcesSettings failed", e)
        }
    }

    private fun toast(context: Context, message: String) {
        val appCtx = context.applicationContext
        VinorSafe.onMain {
            Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show()
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
        VinorSafe.evalJs(webView, js)
    }
}
