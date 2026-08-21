package ir.vinor.app

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * منبع حقیقت نیتیو برای Update — مستقل از WebView.
 * دانلود خودکار است؛ نصب فقط با کلیک کاربر.
 */
object VinorAppUpdateController {

    private const val TAG = "VinorAppUpdate"
    private const val BASE = "https://vinor.ir"
    private val VERSION_URLS = arrayOf(
        "$BASE/api/express/app-version",
        "$BASE/api/app/version",
    )
    private const val MIN_CHECK_INTERVAL_MS = 15 * 60 * 1000L
    private val RETRY_DELAYS_MS = longArrayOf(30_000L, 120_000L, 600_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val checking = AtomicBoolean(false)
    private val installing = AtomicBoolean(false)
    private val lastCheckAtMs = AtomicLong(0L)
    private val lastProgressEmitAt = AtomicLong(0L)
    private val webViewRef = AtomicReference<WeakReference<WebView>?>(null)

    fun bootstrap(context: Context) {
        val ctx = context.applicationContext
        val state = VinorAppUpdateStore.read(ctx)
        if (state.status == VinorAppUpdateStatus.DOWNLOADING ||
            state.status == VinorAppUpdateStatus.VERIFYING
        ) {
            if (!isWorkRunning(ctx)) {
                persist(ctx, state.copy(status = VinorAppUpdateStatus.PAUSED, show = true))
            }
        }
        if (state.status == VinorAppUpdateStatus.INSTALLING) {
            persist(ctx, state.copy(status = VinorAppUpdateStatus.READY, show = true))
        }
        reconcileReadyFile(ctx)
        cleanupIfIdle(ctx)
        check(ctx, webView = null, force = false)
    }

    fun attachWebView(webView: WebView?) {
        webViewRef.set(if (webView == null) null else WeakReference(webView))
    }

    fun check(context: Context, webView: WebView? = null, force: Boolean = false) {
        val ctx = context.applicationContext
        if (webView != null) attachWebView(webView)
        val now = System.currentTimeMillis()
        if (!force && lastCheckAtMs.get() > 0L && now - lastCheckAtMs.get() < MIN_CHECK_INTERVAL_MS) {
            maybeResumeDownload(ctx)
            emit(ctx)
            return
        }
        if (!checking.compareAndSet(false, true)) {
            emit(ctx)
            return
        }
        lastCheckAtMs.set(now)
        persist(ctx, VinorAppUpdateStore.read(ctx).copy(status = VinorAppUpdateStatus.CHECKING))
        emit(ctx)
        scope.launch {
            try {
                val installedName = VinorAppUpdateChecker.appVersionName(ctx)
                val installedCode = VinorAppUpdateChecker.appVersionCode(ctx)
                if (installedName.isEmpty() && installedCode <= 0L) {
                    persist(ctx, VinorAppUpdateState())
                    emit(ctx)
                    return@launch
                }
                val previous = VinorAppUpdateStore.read(ctx)
                val remote = withContext(Dispatchers.IO) { fetchRemote(installedName, installedCode) }
                if (remote == null) {
                    val restoredStatus = when {
                        previous.status != VinorAppUpdateStatus.CHECKING -> previous.status
                        previous.ready -> VinorAppUpdateStatus.READY
                        previous.show && previous.url.isNotBlank() -> VinorAppUpdateStatus.AVAILABLE
                        else -> VinorAppUpdateStatus.IDLE
                    }
                    persist(ctx, previous.copy(status = restoredStatus))
                    maybeResumeDownload(ctx)
                    emit(ctx)
                    return@launch
                }
                val forceUpdate = remote.minSupportedCode > 0L &&
                    installedCode > 0L &&
                    installedCode < remote.minSupportedCode
                val newer = ApkVersionUtils.isRemoteNewer(
                    remote.versionCode,
                    installedCode,
                    remote.versionName,
                    installedName,
                )
                if (!newer) {
                    VinorApkUpdateFiles.cleanup(ctx, keepVersionName = "", keepReadyFile = false)
                    persist(ctx, VinorAppUpdateState(status = VinorAppUpdateStatus.IDLE))
                    emit(ctx)
                    return@launch
                }
                val existing = VinorApkUpdateFiles.apkFile(ctx, remote.versionName)
                val trusted = VinorApkTrust.isTrustedUpdateApk(ctx, existing, remote.sha256)
                val next = VinorAppUpdateState(
                    status = if (trusted) VinorAppUpdateStatus.READY else VinorAppUpdateStatus.AVAILABLE,
                    show = true,
                    versionName = remote.versionName,
                    versionCode = remote.versionCode,
                    url = remote.url,
                    sourceUrl = remote.sourceUrl,
                    sha256 = remote.sha256,
                    sizeBytes = remote.sizeBytes,
                    downloadedBytes = if (trusted) existing.length() else 0L,
                    percent = if (trusted) 100 else 0,
                    force = forceUpdate,
                    minSupportedCode = remote.minSupportedCode,
                )
                persist(ctx, next)
                if (trusted) {
                    emit(ctx)
                    return@launch
                }
                VinorApkUpdateFiles.cleanup(ctx, keepVersionName = remote.versionName, keepReadyFile = false)
                emit(ctx)
                enqueueDownload(ctx, replace = false)
            } finally {
                checking.set(false)
            }
        }
    }

    fun retryDownload(context: Context) {
        val ctx = context.applicationContext
        VinorAppUpdateStore.clearFatalVersionCode(ctx)
        val state = VinorAppUpdateStore.read(ctx)
        if (state.url.isBlank() || state.versionName.isBlank()) {
            check(ctx, force = true)
            return
        }
        persist(
            ctx,
            state.copy(
                status = VinorAppUpdateStatus.AVAILABLE,
                show = true,
                error = "",
                fatal = false,
                retryCount = 0,
                lastFailAtMs = 0L,
            ),
        )
        emit(ctx)
        enqueueDownload(ctx, replace = true)
    }

    fun onUserInstallClicked(context: Context): Boolean {
        return VinorApkUpdateInstaller.install(context)
    }

    fun onReturnedFromUnknownSources(context: Context) {
        val ctx = context.applicationContext
        val state = VinorAppUpdateStore.read(ctx)
        if (state.status == VinorAppUpdateStatus.READY || fileIsReady(ctx, state)) {
            persist(ctx, state.copy(status = VinorAppUpdateStatus.READY, show = true))
            emit(ctx)
        }
    }

    fun onInstallResult(context: Context, success: Boolean, aborted: Boolean) {
        val ctx = context.applicationContext
        installing.set(false)
        val state = VinorAppUpdateStore.read(ctx)
        when {
            success -> {
                persist(ctx, state.copy(status = VinorAppUpdateStatus.INSTALLED, show = false))
                VinorApkUpdateFiles.cleanup(ctx, keepVersionName = "", keepReadyFile = false)
            }
            aborted -> persist(ctx, state.copy(status = VinorAppUpdateStatus.READY, show = true))
            else -> persist(ctx, state.copy(status = VinorAppUpdateStatus.READY, show = true))
        }
        emit(ctx)
    }

    fun markDownloading(context: Context) {
        val state = VinorAppUpdateStore.read(context)
        persist(context, state.copy(status = VinorAppUpdateStatus.DOWNLOADING, show = true, error = ""))
        emit(context)
    }

    fun markPaused(context: Context) {
        val state = VinorAppUpdateStore.read(context)
        val part = VinorApkUpdateFiles.partFile(context, state.versionName)
        persist(
            context,
            state.copy(
                status = VinorAppUpdateStatus.PAUSED,
                show = true,
                downloadedBytes = if (part.exists()) part.length() else state.downloadedBytes,
            ),
        )
        emit(context)
    }

    fun markProgress(context: Context, downloaded: Long, total: Long, percent: Int) {
        val state = VinorAppUpdateStore.read(context)
        val next = state.copy(
            status = VinorAppUpdateStatus.DOWNLOADING,
            show = true,
            downloadedBytes = downloaded,
            sizeBytes = if (total > 0L) total else state.sizeBytes,
            percent = percent,
        )
        persist(context, next)
        val now = System.currentTimeMillis()
        if (now - lastProgressEmitAt.get() >= 400L || percent >= 100) {
            lastProgressEmitAt.set(now)
            emit(context)
        }
    }

    fun markVerifying(context: Context) {
        persist(
            context,
            VinorAppUpdateStore.read(context).copy(status = VinorAppUpdateStatus.VERIFYING, show = true),
        )
        emit(context)
    }

    fun markFailed(context: Context, reason: String, fatal: Boolean) {
        val state = VinorAppUpdateStore.read(context)
        if (fatal && state.versionCode > 0L) {
            VinorAppUpdateStore.setFatalVersionCode(context, state.versionCode)
        }
        val part = VinorApkUpdateFiles.partFile(context, state.versionName)
        val apk = VinorApkUpdateFiles.apkFile(context, state.versionName)
        if (fatal) {
            runCatching { part.delete() }
            runCatching { apk.delete() }
        }
        persist(
            context,
            state.copy(
                status = VinorAppUpdateStatus.FAILED,
                show = true,
                error = reason,
                fatal = fatal,
                retryCount = state.retryCount + 1,
                lastFailAtMs = System.currentTimeMillis(),
            ),
        )
        emit(context)
    }

    fun finalizeDownloadedApk(context: Context): Boolean {
        val state = VinorAppUpdateStore.read(context)
        val part = VinorApkUpdateFiles.partFile(context, state.versionName)
        val target = VinorApkUpdateFiles.apkFile(context, state.versionName)
        val src = when {
            part.exists() -> part
            target.exists() -> target
            else -> {
                markFailed(context, "missing", fatal = false)
                return false
            }
        }
        val trust = VinorApkTrust.evaluate(context, src, state.sha256, requireNewer = true)
        if (trust != VinorApkTrust.Result.OK) {
            runCatching { src.delete() }
            runCatching { target.delete() }
            markFailed(context, trust.name.lowercase(), fatal = isFatalTrust(trust))
            return false
        }
        if (src.absolutePath != target.absolutePath) {
            if (target.exists()) target.delete()
            val renamed = src.renameTo(target)
            if (!renamed) {
                src.copyTo(target, overwrite = true)
                src.delete()
            }
        }
        val again = VinorApkTrust.evaluate(context, target, state.sha256, requireNewer = true)
        if (again != VinorApkTrust.Result.OK) {
            target.delete()
            markFailed(context, again.name.lowercase(), fatal = true)
            return false
        }
        persist(
            context,
            state.copy(
                status = VinorAppUpdateStatus.READY,
                show = true,
                downloadedBytes = target.length(),
                percent = 100,
                error = "",
                fatal = false,
            ),
        )
        VinorApkUpdateFiles.cleanup(context, keepVersionName = state.versionName, keepReadyFile = true)
        emit(context)
        return true
    }

    fun beginInstall(context: Context): Boolean {
        if (!installing.compareAndSet(false, true)) return false
        val state = VinorAppUpdateStore.read(context)
        persist(context, state.copy(status = VinorAppUpdateStatus.INSTALLING, show = true))
        emit(context)
        return true
    }

    fun abortInstallLock() {
        installing.set(false)
    }

    fun isInstalling(): Boolean = installing.get()

    fun syncToWebView(webView: WebView) {
        attachWebView(webView)
        emitTo(webView, VinorAppUpdateStore.read(webView.context ?: return))
    }

    fun stateJson(context: Context): String = toJson(VinorAppUpdateStore.read(context)).toString()

    fun read(context: Context): VinorAppUpdateState = VinorAppUpdateStore.read(context)

    private fun maybeResumeDownload(context: Context) {
        val state = VinorAppUpdateStore.read(context)
        if (!state.show || state.url.isBlank()) return
        if (state.status == VinorAppUpdateStatus.READY && fileIsReady(context, state)) return
        if (state.status == VinorAppUpdateStatus.FAILED && state.fatal) return
        if (state.status == VinorAppUpdateStatus.FAILED &&
            VinorAppUpdateStore.fatalVersionCode(context) == state.versionCode &&
            state.versionCode > 0L
        ) {
            return
        }
        if (state.status == VinorAppUpdateStatus.FAILED && !state.fatal) {
            val delay = RETRY_DELAYS_MS.getOrElse(state.retryCount.coerceAtLeast(1) - 1) { RETRY_DELAYS_MS.last() }
            if (state.lastFailAtMs > 0L && System.currentTimeMillis() - state.lastFailAtMs < delay) {
                return
            }
        }
        if (state.status == VinorAppUpdateStatus.DOWNLOADING && isWorkRunning(context)) return
        enqueueDownload(context, replace = false)
    }

    private fun enqueueDownload(context: Context, replace: Boolean) {
        val state = VinorAppUpdateStore.read(context)
        if (state.url.isBlank() || state.versionName.isBlank()) return
        if (!VinorAppUpdateDownloader.isAllowedApkUrl(state.url) &&
            (state.sourceUrl.isBlank() || !VinorAppUpdateDownloader.isAllowedApkUrl(state.sourceUrl))
        ) {
            markFailed(context, "untrusted_url", fatal = true)
            return
        }
        if (state.status == VinorAppUpdateStatus.READY && fileIsReady(context, state)) return
        if (state.fatal && VinorAppUpdateStore.fatalVersionCode(context) == state.versionCode) return
        if (!replace && isWorkRunning(context)) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<VinorAppUpdateWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply {
                if (BuildWorkExpedited) {
                    runCatching { setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                }
            }
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(VinorAppUpdateWorker.UNIQUE_WORK, policy, request)
        val nextStatus =
            if (state.status == VinorAppUpdateStatus.READY) state.status
            else VinorAppUpdateStatus.AVAILABLE
        persist(context, state.copy(status = nextStatus, show = true))
        emit(context)
    }

    private val BuildWorkExpedited: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    private fun isWorkRunning(context: Context): Boolean {
        return try {
            val infos = WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWork(VinorAppUpdateWorker.UNIQUE_WORK)
                .get()
            infos.any { it.state.isFinished.not() }
        } catch (e: Exception) {
            Log.w(TAG, "work status failed", e)
            false
        }
    }

    private fun fileIsReady(context: Context, state: VinorAppUpdateState): Boolean {
        if (state.versionName.isBlank()) return false
        val file = VinorApkUpdateFiles.apkFile(context, state.versionName)
        return VinorApkTrust.isTrustedUpdateApk(context, file, state.sha256)
    }

    private fun reconcileReadyFile(context: Context) {
        val state = VinorAppUpdateStore.read(context)
        if (state.versionName.isBlank()) return
        if (fileIsReady(context, state)) {
            persist(context, state.copy(status = VinorAppUpdateStatus.READY, show = true, percent = 100))
            return
        }
        if (state.status == VinorAppUpdateStatus.READY) {
            persist(context, state.copy(status = VinorAppUpdateStatus.AVAILABLE, show = true, percent = 0))
        }
    }

    private fun cleanupIfIdle(context: Context) {
        val state = VinorAppUpdateStore.read(context)
        if (state.status == VinorAppUpdateStatus.IDLE || state.status == VinorAppUpdateStatus.INSTALLED) {
            VinorApkUpdateFiles.cleanup(context, keepVersionName = "", keepReadyFile = false)
        }
    }

    private fun persist(context: Context, state: VinorAppUpdateState) {
        VinorAppUpdateStore.write(context.applicationContext, state)
    }

    private fun emit(context: Context) {
        val state = VinorAppUpdateStore.read(context)
        val wv = webViewRef.get()?.get()
        if (wv != null) emitTo(wv, state)
    }

    private fun emitTo(webView: WebView, state: VinorAppUpdateState) {
        val json = toJson(state)
        val nav = if (state.show) {
            JSONObject()
                .put("show", true)
                .put("version", state.versionName)
                .put("url", state.url)
        } else {
            JSONObject().put("show", false)
        }
        val js = """
            (function () {
              try {
                window.dispatchEvent(new CustomEvent('vinor:app-update:state', { detail: $json }));
                window.dispatchEvent(new CustomEvent('vinor:app-update:nav', { detail: $nav }));
                window.dispatchEvent(new CustomEvent('vinor:app-update:download', { detail: $json }));
              } catch (_) {}
            })();
        """.trimIndent()
        VinorSafe.evalJs(webView, js)
    }

    private fun toJson(state: VinorAppUpdateState): JSONObject {
        return JSONObject()
            .put("status", state.status.name.lowercase())
            .put("show", state.show)
            .put("version", state.versionName)
            .put("versionName", state.versionName)
            .put("versionCode", state.versionCode)
            .put("url", state.url)
            .put("sourceUrl", state.sourceUrl)
            .put("percent", state.percent)
            .put("downloadedBytes", state.downloadedBytes)
            .put("totalBytes", state.sizeBytes)
            .put("error", state.error)
            .put("fatal", state.fatal)
            .put("force", state.force)
            .put("ready", state.ready)
            .put("downloading", state.downloading)
            .put("failed", state.status == VinorAppUpdateStatus.FAILED)
    }

    private fun isFatalTrust(result: VinorApkTrust.Result): Boolean {
        return result == VinorApkTrust.Result.SHA_MISMATCH ||
            result == VinorApkTrust.Result.SIGNATURE_MISMATCH ||
            result == VinorApkTrust.Result.WRONG_PACKAGE ||
            result == VinorApkTrust.Result.INVALID_APK ||
            result == VinorApkTrust.Result.NOT_NEWER
    }

    private fun fetchRemote(clientName: String, clientCode: Long): VinorRemoteApkInfo? {
        for (urlStr in VERSION_URLS) {
            var conn: HttpURLConnection? = null
            try {
                val opened = (URL(urlStr).openConnection() as? HttpURLConnection) ?: continue
                conn = opened
                opened.requestMethod = "GET"
                opened.connectTimeout = 12_000
                opened.readTimeout = 12_000
                opened.useCaches = false
                opened.setRequestProperty("Accept", "application/json")
                if (clientName.isNotEmpty()) {
                    opened.setRequestProperty("X-Vinor-App-Version", clientName)
                }
                if (clientCode > 0L) {
                    opened.setRequestProperty("X-Vinor-App-Version-Code", clientCode.toString())
                }
                opened.setRequestProperty("User-Agent", "VinorApp/WebViewOnly/$clientName")
                if (opened.responseCode != HttpURLConnection.HTTP_OK) continue
                val body = opened.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) continue
                val name = json.optString("android_apk_version_name", "")
                    .ifBlank { json.optString("android_apk_version", "") }
                    .trim()
                val code = json.optLong("android_apk_version_code", 0L).let { v ->
                    if (v > 0L) v else json.optInt("android_apk_version_code", 0).toLong()
                }.let { v ->
                    if (v > 0L) v else ApkVersionUtils.versionCodeFromName(name)
                }
                val apkUrlRaw = json.optString("android_apk_url", "").trim()
                val apkUrl = VinorDownloadBridge.resolveAbsoluteUrl(apkUrlRaw).orEmpty()
                val sourceRaw = json.optString("android_apk_source_url", "").trim()
                val sourceUrl = VinorDownloadBridge.resolveAbsoluteUrl(sourceRaw).orEmpty()
                val sha = json.optString("android_apk_sha256", "").trim()
                val size = json.optLong("android_apk_size_bytes", 0L).let { v ->
                    if (v > 0L) v else json.optString("android_apk_size_bytes", "").toLongOrNull() ?: 0L
                }
                val minCode = json.optLong("android_apk_min_supported_version_code", 0L)
                if (name.isEmpty() || apkUrl.isEmpty()) continue
                if (!UrlUtils.isTrustedVinorHttps(apkUrl)) continue
                if (!UrlUtils.isApkDownloadUrl(apkUrl) && !UrlUtils.isSafePublicDownloadUrl(apkUrl)) continue
                return VinorRemoteApkInfo(
                    versionName = name,
                    versionCode = code,
                    url = apkUrl,
                    sourceUrl = sourceUrl,
                    sha256 = sha,
                    sizeBytes = size,
                    minSupportedCode = minCode,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Version check failed for $urlStr: ${e.message}")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return null
    }
}
