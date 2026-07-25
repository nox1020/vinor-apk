package ir.vinor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * نتیجهٔ نشست PackageInstaller — برای نصب درون‌برنامه‌ای APK.
 * در STATUS_PENDING_USER_ACTION دیالوگ تأیید سیستم را باز می‌کند.
 */
class ApkInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        Log.i(TAG, "install status=$status message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = extrasConfirmIntent(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open install confirmation", e)
                    toast(context, "نمی‌توان صفحهٔ نصب را باز کرد")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                toast(context, "نصب با موفقیت انجام شد")
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // کاربر لغو کرد — بی‌صدا
            }
            else -> {
                val detail = message.trim().ifEmpty { "امکان نصب وجود ندارد" }
                toast(context, "نصب ناموفق: $detail")
            }
        }
    }

    private fun extrasConfirmIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    private fun toast(context: Context, text: String) {
        val app = context.applicationContext
        android.os.Handler(app.mainLooper).post {
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION = "ir.vinor.app.ACTION_APK_INSTALL_RESULT"
        private const val TAG = "ApkInstallResult"
    }
}
