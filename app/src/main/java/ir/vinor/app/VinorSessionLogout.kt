package ir.vinor.app

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * خروج محلی حتی اگر POST ناموفق باشد یا تا ۱۰ ثانیه جواب ندهد.
 * مسیر، متد و بدنهٔ درخواست همان [PartnerApi.postJson] قبلی است.
 */
object VinorSessionLogout {

    const val PATH = "/express/partner/api/logout"
    private const val TIMEOUT_MS = 10_000L
    private const val COOKIE_WATCHDOG_MS = 3_000L

    fun perform(context: Context, scope: CoroutineScope, onDone: () -> Unit) {
        val app = context.applicationContext
        VinorFcmRegistrar.unregisterCurrentToken(app)
        val finished = AtomicBoolean(false)
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            onDone()
        }
        scope.launch {
            val signal = CompletableDeferred<Unit>()
            val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    PartnerApi.postJson(PATH, JSONObject(), app)
                } catch (_: Exception) {
                    // خطای شبکه هم خروج محلی را متوقف نمی‌کند و به کاربر نشان داده نمی‌شود.
                } finally {
                    if (!signal.isCompleted) signal.complete(Unit)
                }
            }
            try {
                withTimeoutOrNull(TIMEOUT_MS) { signal.await() }
            } catch (_: Exception) {
            } finally {
                worker.cancel()
            }
            PartnerApi.clearCookies { finish() }
            delay(COOKIE_WATCHDOG_MS)
            finish()
        }
    }
}
