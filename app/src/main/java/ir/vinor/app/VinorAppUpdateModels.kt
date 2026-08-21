package ir.vinor.app

/**
 * State machine واحد بروزرسانی APK — منبع حقیقت نیتیو.
 */
enum class VinorAppUpdateStatus {
    IDLE,
    CHECKING,
    AVAILABLE,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    READY,
    INSTALLING,
    INSTALLED,
    FAILED,
}

data class VinorAppUpdateState(
    val status: VinorAppUpdateStatus = VinorAppUpdateStatus.IDLE,
    val show: Boolean = false,
    val versionName: String = "",
    val versionCode: Long = 0L,
    val url: String = "",
    val sourceUrl: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val percent: Int = 0,
    val error: String = "",
    val fatal: Boolean = false,
    val force: Boolean = false,
    val retryCount: Int = 0,
    val lastFailAtMs: Long = 0L,
    val minSupportedCode: Long = 0L,
) {
    val ready: Boolean get() = status == VinorAppUpdateStatus.READY
    val downloading: Boolean
        get() = status == VinorAppUpdateStatus.DOWNLOADING ||
            status == VinorAppUpdateStatus.PAUSED ||
            status == VinorAppUpdateStatus.VERIFYING ||
            status == VinorAppUpdateStatus.AVAILABLE
}

data class VinorRemoteApkInfo(
    val versionName: String,
    val versionCode: Long,
    val url: String,
    val sourceUrl: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val minSupportedCode: Long = 0L,
)
