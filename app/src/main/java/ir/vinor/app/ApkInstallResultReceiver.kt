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
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                toast(
                    context,
                    "نصب ممکن نیست: نسخهٔ فعلی با این فایل سازگار نیست " +
                        "(امضای متفاوت یا نسخهٔ پایین‌تر). اپ را حذف کنید یا APK جدیدتر بگیرید.",
                )
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                toast(context, "این نسخه با دستگاه شما سازگار نیست")
            }
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                toast(context, "فایل نصب نامعتبر است؛ دوباره دانلود کنید")
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                toast(context, "فضای ذخیره‌سازی کافی نیست")
            }
            else -> {
                val detail = humanizeInstallFailure(status, message)
                toast(context, "نصب ناموفق: $detail")
            }
        }
    }

    private fun humanizeInstallFailure(status: Int, message: String): String {
        val raw = message.trim()
        val lower = raw.lowercase()
        when {
            "update incompatible" in lower || "signatures" in lower ||
                "signature" in lower ->
                return "امضای فایل با اپ نصب‌شده یکی نیست؛ ابتدا اپ را حذف کنید"
            "downgrade" in lower || "version" in lower && "code" in lower ->
                return "نسخهٔ فایل از نسخهٔ نصب‌شده قدیمی‌تر است"
            "parse" in lower || "invalid" in lower ->
                return "فایل APK خراب یا ناقص است؛ دوباره دانلود کنید"
        }
        if (raw.isNotEmpty()) return raw
        return when (status) {
            PackageInstaller.STATUS_FAILURE -> "امکان نصب برنامه وجود ندارد"
            else -> "امکان نصب برنامه وجود ندارد (کد $status)"
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
