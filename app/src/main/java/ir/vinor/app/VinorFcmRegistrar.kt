package ir.vinor.app

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** ثبت FCM token روی سرور بعد از لاگین WebView */
object VinorFcmRegistrar {
    private const val TAG = "VinorFcmRegistrar"
    private const val BASE = "https://vinor.ir"
    private const val API_REGISTER = "/api/fcm/register"
    private const val PREFS = "vinor_fcm"
    private const val KEY_LAST_TOKEN = "last_token"
    private const val KEY_LAST_SENT = "last_sent_ms"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private var pollJob: Job? = null
    private var lastRegisteredToken: String? = null

    fun start(context: Context) {
        if (pollJob?.isActive == true) return
        VinorFirebaseAutoConfig.bootstrap(context) { ready ->
            if (!ready) return@bootstrap
            requestTokenAndRegister(context)
            if (pollJob?.isActive == true) return@bootstrap
            pollJob = scope.launch {
                while (isActive) {
                    delay(45_000L)
                    requestTokenAndRegister(context.applicationContext)
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun requestTokenAndRegister(context: Context) {
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.d(TAG, "FCM token fetch failed", task.exception)
                        return@addOnCompleteListener
                    }
                    val token = task.result?.trim().orEmpty()
                    if (token.isNotEmpty()) {
                        registerToken(context.applicationContext, token)
                    }
                }
        }.onFailure {
            Log.d(TAG, "FCM unavailable", it)
        }
    }

    fun registerToken(context: Context, token: String) {
        val tokenTrim = token.trim()
        if (tokenTrim.isEmpty()) return

        val cookie = CookieManager.getInstance().getCookie(BASE)?.trim().orEmpty()
        if (cookie.isBlank()) {
            Log.d(TAG, "skip FCM register — not logged in")
            return
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastToken = prefs.getString(KEY_LAST_TOKEN, null)
        val lastSent = prefs.getLong(KEY_LAST_SENT, 0L)
        if (tokenTrim == lastToken && tokenTrim == lastRegisteredToken && (now - lastSent) < 6 * 60 * 60 * 1000L) {
            return
        }

        scope.launch {
            try {
                val version = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName?.trim()
                }.getOrNull().orEmpty()

                val bodyJson = JSONObject()
                    .put("token", tokenTrim)
                    .put("platform", "android")
                    .put("app_version", version)
                    .toString()

                val req = Request.Builder()
                    .url("$BASE$API_REGISTER")
                    .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .addHeader("Cookie", cookie)
                    .addHeader("Accept", "application/json")
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("User-Agent", VinorUserAgent.appSuffix(context))
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        lastRegisteredToken = tokenTrim
                        prefs.edit()
                            .putString(KEY_LAST_TOKEN, tokenTrim)
                            .putLong(KEY_LAST_SENT, now)
                            .apply()
                        Log.d(TAG, "FCM token registered")
                    } else {
                        Log.d(TAG, "FCM register HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "FCM register failed", e)
            }
        }
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
