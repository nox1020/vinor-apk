package ir.vinor.app

import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * وضعیت foreground کل اپ + خواب عمیق Doze-friendly:
 * وقتی پروسه پس‌زمینه می‌شود، تایمرهای WebView متوقف می‌شوند تا بیدار نگه داشتن شبکه/کروم قطع شود.
 */
object VinorAppForeground {
    private const val TAG = "VinorAppForeground"

    @Volatile
    var isInForeground: Boolean = false
        private set

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isInForeground = true
                runCatching { WebView.resumeTimers() }
                    .onFailure { Log.d(TAG, "resumeTimers failed", it) }
            }

            override fun onStop(owner: LifecycleOwner) {
                isInForeground = false
                // قطع کار پس‌زمینهٔ غیرضروری — اجازهٔ Doze / deep sleep
                VinorFcmRegistrar.stop()
                runCatching { WebView.pauseTimers() }
                    .onFailure { Log.d(TAG, "pauseTimers failed", it) }
            }
        })
    }
}
