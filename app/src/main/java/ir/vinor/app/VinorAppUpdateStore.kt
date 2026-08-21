package ir.vinor.app

import android.content.Context
import android.content.SharedPreferences

object VinorAppUpdateStore {

    private const val PREFS = "vinor_app_update_v2"
    private const val KEY_STATUS = "status"
    private const val KEY_SHOW = "show"
    private const val KEY_VERSION_NAME = "version_name"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_URL = "url"
    private const val KEY_SOURCE = "source_url"
    private const val KEY_SHA = "sha256"
    private const val KEY_SIZE = "size_bytes"
    private const val KEY_DOWNLOADED = "downloaded_bytes"
    private const val KEY_PERCENT = "percent"
    private const val KEY_ERROR = "error"
    private const val KEY_FATAL = "fatal"
    private const val KEY_FORCE = "force"
    private const val KEY_RETRY = "retry_count"
    private const val KEY_LAST_FAIL = "last_fail_at"
    private const val KEY_MIN_CODE = "min_supported_code"
    private const val KEY_FATAL_CODE = "fatal_version_code"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): VinorAppUpdateState {
        val p = prefs(context)
        val raw = p.getString(KEY_STATUS, VinorAppUpdateStatus.IDLE.name).orEmpty()
        val status = runCatching { VinorAppUpdateStatus.valueOf(raw) }
            .getOrDefault(VinorAppUpdateStatus.IDLE)
        return VinorAppUpdateState(
            status = status,
            show = p.getBoolean(KEY_SHOW, false),
            versionName = p.getString(KEY_VERSION_NAME, "").orEmpty(),
            versionCode = p.getLong(KEY_VERSION_CODE, 0L),
            url = p.getString(KEY_URL, "").orEmpty(),
            sourceUrl = p.getString(KEY_SOURCE, "").orEmpty(),
            sha256 = p.getString(KEY_SHA, "").orEmpty(),
            sizeBytes = p.getLong(KEY_SIZE, 0L),
            downloadedBytes = p.getLong(KEY_DOWNLOADED, 0L),
            percent = p.getInt(KEY_PERCENT, 0),
            error = p.getString(KEY_ERROR, "").orEmpty(),
            fatal = p.getBoolean(KEY_FATAL, false),
            force = p.getBoolean(KEY_FORCE, false),
            retryCount = p.getInt(KEY_RETRY, 0),
            lastFailAtMs = p.getLong(KEY_LAST_FAIL, 0L),
            minSupportedCode = p.getLong(KEY_MIN_CODE, 0L),
        )
    }

    fun write(context: Context, state: VinorAppUpdateState) {
        prefs(context).edit()
            .putString(KEY_STATUS, state.status.name)
            .putBoolean(KEY_SHOW, state.show)
            .putString(KEY_VERSION_NAME, state.versionName)
            .putLong(KEY_VERSION_CODE, state.versionCode)
            .putString(KEY_URL, state.url)
            .putString(KEY_SOURCE, state.sourceUrl)
            .putString(KEY_SHA, state.sha256)
            .putLong(KEY_SIZE, state.sizeBytes)
            .putLong(KEY_DOWNLOADED, state.downloadedBytes)
            .putInt(KEY_PERCENT, state.percent.coerceIn(0, 100))
            .putString(KEY_ERROR, state.error)
            .putBoolean(KEY_FATAL, state.fatal)
            .putBoolean(KEY_FORCE, state.force)
            .putInt(KEY_RETRY, state.retryCount)
            .putLong(KEY_LAST_FAIL, state.lastFailAtMs)
            .putLong(KEY_MIN_CODE, state.minSupportedCode)
            .apply()
    }

    fun fatalVersionCode(context: Context): Long =
        prefs(context).getLong(KEY_FATAL_CODE, 0L)

    fun setFatalVersionCode(context: Context, code: Long) {
        prefs(context).edit().putLong(KEY_FATAL_CODE, code).apply()
    }

    fun clearFatalVersionCode(context: Context) {
        prefs(context).edit().remove(KEY_FATAL_CODE).apply()
    }
}
