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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * نصب APK آماده‌شده — دانلود در [VinorAppUpdateController] / WorkManager است.
 * نصب فقط پس از کلیک کاربر.
 */
object VinorApkUpdateInstaller {

    private const val TAG = "VinorApkUpdateDl"

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

    fun isValidApkFile(file: File?): Boolean = VinorApkUpdateFiles.isValidApkFile(file)

    fun readState(context: Context): DownloadState {
        val s = VinorAppUpdateController.read(context)
        val status = when (s.status) {
            VinorAppUpdateStatus.READY, VinorAppUpdateStatus.INSTALLING -> Status.READY
            VinorAppUpdateStatus.FAILED -> Status.FAILED
            VinorAppUpdateStatus.DOWNLOADING,
            VinorAppUpdateStatus.PAUSED,
            VinorAppUpdateStatus.VERIFYING,
            VinorAppUpdateStatus.AVAILABLE,
            VinorAppUpdateStatus.CHECKING,
            -> Status.DOWNLOADING
            else -> Status.IDLE
        }
        return DownloadState(status, s.versionName, s.url, s.error)
    }

    fun clearState(context: Context) {
        VinorAppUpdateStore.write(context, VinorAppUpdateState())
    }

    fun ensureDownload(
        context: Context,
        url: String,
        version: String,
        webView: WebView? = null,
        sourceUrl: String = "",
    ) {
        // URL از JS/فراخواننده نادیده گرفته می‌شود — فقط state نیتیو معتبر است.
        VinorAppUpdateController.attachWebView(webView)
        val state = VinorAppUpdateController.read(context)
        if (state.ready) {
            webView?.let { VinorAppUpdateController.syncToWebView(it) }
            return
        }
        if (state.show) {
            VinorAppUpdateController.retryDownload(context)
        } else {
            VinorAppUpdateController.check(context, webView, force = true)
        }
    }

    fun install(context: Context): Boolean {
        val appCtx = context.applicationContext
        val state = VinorAppUpdateController.read(appCtx)
        if (state.status != VinorAppUpdateStatus.READY || state.versionName.isBlank()) {
            toast(context, "فایل نصب آماده نیست")
            return false
        }

        val file = VinorApkUpdateFiles.apkFile(appCtx, state.versionName)
        val trust = VinorApkTrust.evaluate(appCtx, file, state.sha256, requireNewer = true)
        if (trust != VinorApkTrust.Result.OK) {
            file.delete()
            VinorAppUpdateController.markFailed(appCtx, trust.name.lowercase(), fatal = true)
            toast(context, "فایل نصب نامعتبر بود")
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

        if (!VinorAppUpdateController.beginInstall(appCtx)) {
            return false
        }

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
                        VinorAppUpdateController.abortInstallLock()
                        VinorAppUpdateController.onInstallResult(appCtx, success = false, aborted = false)
                        toast(context, "خطا در باز کردن نصب‌گر")
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
            VinorAppUpdateController.abortInstallLock()
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

    fun stateJson(context: Context): String = VinorAppUpdateController.stateJson(context)

    fun notifyWebView(webView: WebView, state: DownloadState) {
        VinorAppUpdateController.syncToWebView(webView)
    }
}
