package ir.vinor.app

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** دریافت خودکار Firebase config از سرور — بدون google-services.json در APK */
object VinorFirebaseAutoConfig {
    private const val TAG = "VinorFirebaseAuto"
    private const val BASE = "https://vinor.ir"
    private const val API_MOBILE_CONFIG = "/api/fcm/mobile-config"
    private const val PREFS = "vinor_firebase_cfg"
    private const val KEY_JSON = "mobile_json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bootstrapped = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun bootstrap(context: Context, onReady: (Boolean) -> Unit = {}) {
        if (bootstrapped.get()) {
            onReady(true)
            return
        }
        scope.launch {
            val ok = ensureInitialized(context.applicationContext)
            bootstrapped.set(ok)
            withContext(Dispatchers.Main) { onReady(ok) }
        }
    }

    suspend fun ensureInitialized(context: Context): Boolean {
        val appCtx = context.applicationContext
        if (FirebaseApp.getApps(appCtx).isNotEmpty()) return true

        val cached = readCache(appCtx)
        if (cached != null && initOnMain(appCtx, cached)) return true

        val remote = fetchRemoteConfig(appCtx)
        if (remote != null && initOnMain(appCtx, remote)) {
            writeCache(appCtx, remote)
            return true
        }
        return false
    }

    private suspend fun initOnMain(context: Context, mobile: JSONObject): Boolean =
        withContext(Dispatchers.Main) { initWithJson(context, mobile) }

    private fun initWithJson(context: Context, mobile: JSONObject): Boolean {
        return runCatching {
            val appId = mobile.optString("application_id").trim()
            val apiKey = mobile.optString("api_key").trim()
            val projectId = mobile.optString("project_id").trim()
            val senderId = mobile.optString("gcm_sender_id").trim()
            if (appId.isEmpty() || apiKey.isEmpty() || projectId.isEmpty() || senderId.isEmpty()) {
                return false
            }
            val options = FirebaseOptions.Builder()
                .setApplicationId(appId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .setGcmSenderId(senderId)
                .build()
            FirebaseApp.initializeApp(context, options)
            Log.i(TAG, "Firebase initialized from auto-config project=$projectId")
            true
        }.getOrElse {
            Log.w(TAG, "Firebase init failed", it)
            false
        }
    }

    private fun readCache(context: Context): JSONObject? {
        return runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JSON, null)
                ?.trim()
            if (raw.isNullOrEmpty()) null else JSONObject(raw)
        }.getOrNull()
    }

    private fun writeCache(context: Context, mobile: JSONObject) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_JSON, mobile.toString())
                .apply()
        }
    }

    private fun fetchRemoteConfig(context: Context): JSONObject? {
        return runCatching {
            val req = Request.Builder()
                .url("$BASE$API_MOBILE_CONFIG")
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", VinorUserAgent.appSuffix(context))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                if (!root.optBoolean("ok", false)) return null
                root.optJSONObject("mobile")
            }
        }.getOrElse {
            Log.d(TAG, "fetch mobile-config failed", it)
            null
        }
    }
}
