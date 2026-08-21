package ir.vinor.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * وضعیت foreground کل اپ + خواب عمیق Doze-friendly:
 * وقتی پروسه پس‌زمینه می‌شود، تایمرهای WebView متوقف می‌شوند تا بیدار نگه داشتن شبکه/کروم قطع شود.
 */
object VinorAppForeground {
    @Volatile
    var isInForeground: Boolean = false
        private set

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isInForeground = true
                setWebViewTimersPaused(false)
            }

            override fun onStop(owner: LifecycleOwner) {
                isInForeground = false
                // قطع کار پس‌زمینهٔ غیرضروری — اجازهٔ Doze / deep sleep
                VinorFcmRegistrar.stop()
                setWebViewTimersPaused(true)
            }
        })
    }

    private fun setWebViewTimersPaused(paused: Boolean) {
        VinorSafe.onMain {
            val wv = WebViewProvider.peek() ?: return@onMain
            runCatching {
                if (paused) wv.pauseTimers() else wv.resumeTimers()
            }
        }
    }
}
