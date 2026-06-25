package ir.vinor.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** وضعیت foreground کل اپ — برای جلوگیری از نوتیف تکراری FCM وقتی WebView باز است */
object VinorAppForeground {
    @Volatile
    var isInForeground: Boolean = false
        private set

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isInForeground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                isInForeground = false
            }
        })
    }
}
