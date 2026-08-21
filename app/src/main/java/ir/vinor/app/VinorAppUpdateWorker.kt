package ir.vinor.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VinorAppUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val state = VinorAppUpdateStore.read(ctx)
        if (state.url.isBlank() || state.versionName.isBlank()) {
            return@withContext Result.failure()
        }
        if (state.status == VinorAppUpdateStatus.READY) {
            val ready = VinorApkUpdateFiles.apkFile(ctx, state.versionName)
            if (VinorApkTrust.isTrustedUpdateApk(ctx, ready, state.sha256)) {
                return@withContext Result.success()
            }
        }

        runCatching { setForeground(foregroundInfo(ctx, state.percent.coerceAtLeast(0))) }

        VinorAppUpdateController.markDownloading(ctx)

        val candidates = VinorAppUpdateDownloader.candidateUrls(state.url, state.sourceUrl)
        if (candidates.isEmpty()) {
            VinorAppUpdateController.markFailed(ctx, "unknown_apk_source", fatal = true)
            return@withContext Result.failure()
        }

        var last: VinorAppUpdateDownloader.Outcome = VinorAppUpdateDownloader.Outcome.Transient("no_candidate")
        for (url in candidates) {
            if (isStopped) {
                VinorAppUpdateController.markPaused(ctx)
                return@withContext Result.retry()
            }
            last = VinorAppUpdateDownloader.download(
                context = ctx,
                absUrl = url,
                versionName = state.versionName,
                expectedSize = state.sizeBytes,
                cancel = VinorAppUpdateDownloader.CancelFlag { isStopped },
                progress = VinorAppUpdateDownloader.ProgressListener { downloaded, total, percent ->
                    VinorAppUpdateController.markProgress(ctx, downloaded, total, percent)
                    runCatching {
                        setProgressAsync(
                            workDataOf(
                                KEY_DOWNLOADED to downloaded,
                                KEY_TOTAL to total,
                                KEY_PERCENT to percent,
                            ),
                        )
                    }
                    if (percent == 0 || percent % 5 == 0) {
                        runCatching { setForeground(foregroundInfo(ctx, percent)) }
                    }
                },
            )
            when (last) {
                is VinorAppUpdateDownloader.Outcome.Ok -> break
                is VinorAppUpdateDownloader.Outcome.Paused -> {
                    VinorAppUpdateController.markPaused(ctx)
                    return@withContext Result.retry()
                }
                is VinorAppUpdateDownloader.Outcome.Fatal -> {
                    VinorAppUpdateController.markFailed(ctx, last.reason, fatal = true)
                    return@withContext Result.failure()
                }
                is VinorAppUpdateDownloader.Outcome.Transient -> continue
            }
        }

        when (val outcome = last) {
            is VinorAppUpdateDownloader.Outcome.Ok -> {
                VinorAppUpdateController.markVerifying(ctx)
                val verified = VinorAppUpdateController.finalizeDownloadedApk(ctx)
                if (verified) Result.success() else Result.failure()
            }
            is VinorAppUpdateDownloader.Outcome.Paused -> {
                VinorAppUpdateController.markPaused(ctx)
                Result.retry()
            }
            is VinorAppUpdateDownloader.Outcome.Fatal -> {
                VinorAppUpdateController.markFailed(ctx, outcome.reason, fatal = true)
                Result.failure()
            }
            is VinorAppUpdateDownloader.Outcome.Transient -> {
                VinorAppUpdateController.markFailed(ctx, outcome.message, fatal = false)
                if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
        }
    }

    private fun foregroundInfo(context: Context, percent: Int): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("دانلود بروزرسانی وینور")
            .setContentText(if (percent > 0) "در حال دانلود… $percent٪" else "در حال دانلود بروزرسانی")
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK = "ir.vinor.app.APK_UPDATE_DOWNLOAD"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_PERCENT = "percent"
        private const val CHANNEL_ID = "vinor_apk_update"
        private const val NOTIFICATION_ID = 4101
        private const val MAX_ATTEMPTS = 3

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "دانلود بروزرسانی",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "پیشرفت دانلود نسخه جدید وینور"
                    setShowBadge(false)
                },
            )
        }
    }
}
