package ir.vinor.app

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** ثبت FCM token روی سرور بعد از لاگین WebView — بدون poll پس‌زمینه */
object VinorFcmRegistrar {
    private const val TAG = "VinorFcmRegistrar"
    private const val BASE = "https://vinor.ir"
    private const val API_REGISTER = "/api/fcm/register"
    private const val API_UNREGISTER = "/api/fcm/unregister"
    private const val PREFS = "vinor_fcm"
    private const val KEY_LAST_TOKEN = "last_token"
    private const val KEY_LAST_SENT = "last_sent_ms"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private var lastRegisteredToken: String? = null

    /** یک‌بار bootstrap Firebase + ثبت token؛ تکرار دوره‌ای نداره (FCM onNewToken کافی است) */
    fun start(context: Context) {
        VinorFirebaseAutoConfig.bootstrap(context) { ready ->
            if (!ready) return@bootstrap
            // Application.onCreate قبل از foreground است — force تا skip نشود
            requestTokenAndRegister(context, force = true)
        }
    }

    /** سازگاری با فراخوان‌های قبلی — کار پس‌زمینه‌ای برای توقف نیست */
    fun stop() = Unit

    fun requestTokenAndRegister(context: Context, force: Boolean = false) {
        if (!VinorAppForeground.isInForeground && !force) {
            Log.d(TAG, "skip FCM token fetch — app backgrounded")
            return
        }
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.d(TAG, "FCM token fetch failed", task.exception)
                        return@addOnCompleteListener
                    }
                    val token = task.result?.trim().orEmpty()
                    if (token.isNotEmpty()) {
                        registerToken(context.applicationContext, token, force = force)
                    }
                }
        }.onFailure {
            Log.d(TAG, "FCM unavailable", it)
        }
    }

    /** بعد از لاگین — throttle را نادیده بگیر */
    fun registerAfterLogin(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_SENT).apply()
        requestTokenAndRegister(context.applicationContext, force = true)
    }

    /** قبل از پاک شدن کوکی — token این دستگاه از سرور حذف شود */
    fun unregisterCurrentToken(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_LAST_TOKEN, null)?.trim().orEmpty()
        if (token.isEmpty()) return

        VinorSafe.onMain {
            val cookie = VinorSafe.cookieOrEmpty(BASE)
            postUnregister(appCtx, prefs, token, cookie)
        }
    }

    private fun postUnregister(
        appCtx: Context,
        prefs: android.content.SharedPreferences,
        token: String,
        cookie: String,
    ) {
        scope.launch {
            try {
                if (cookie.isNotBlank()) {
                    val bodyJson = JSONObject().put("token", token).toString()
                    val req = Request.Builder()
                        .url("$BASE$API_UNREGISTER")
                        .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .addHeader("Cookie", cookie)
                        .addHeader("Accept", "application/json")
                        .addHeader("X-Requested-With", "XMLHttpRequest")
                        .addHeader("User-Agent", VinorUserAgent.appSuffix(appCtx))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        Log.d(TAG, "FCM unregister HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "FCM unregister failed", e)
            } finally {
                prefs.edit().remove(KEY_LAST_TOKEN).remove(KEY_LAST_SENT).apply()
                lastRegisteredToken = null
            }
        }
    }

    fun registerToken(context: Context, token: String, force: Boolean = false) {
        val tokenTrim = token.trim()
        if (tokenTrim.isEmpty()) return
        val appCtx = context.applicationContext
        VinorSafe.onMain {
            if (!PartnerApi.hasSession()) {
                Log.d(TAG, "skip FCM register — not logged in")
                return@onMain
            }
            val cookie = VinorSafe.cookieOrEmpty(BASE)
            if (cookie.isBlank()) {
                Log.d(TAG, "skip FCM register — not logged in")
                return@onMain
            }
            registerTokenWithCookie(appCtx, tokenTrim, cookie, force)
        }
    }

    private fun registerTokenWithCookie(
        context: Context,
        tokenTrim: String,
        cookie: String,
        force: Boolean,
    ) {

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastToken = prefs.getString(KEY_LAST_TOKEN, null)
        val lastSent = prefs.getLong(KEY_LAST_SENT, 0L)
        val tokenChanged = tokenTrim != lastToken
        if (!force && !tokenChanged &&
            tokenTrim == lastRegisteredToken &&
            (now - lastSent) < 6 * 60 * 60 * 1000L
        ) {
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
        scope.cancel()
    }
}
