package ir.vinor.app

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * دانلود APK با Range resume به فایل .part — بدون وابستگی به Activity.
 */
object VinorAppUpdateDownloader {

    private const val TAG = "VinorAppUpdateDl"
    private const val MAX_REDIRECTS = 5
    private const val PUBLIC_D_SLASH = "https://vinor.ir/d/"
    private const val PUBLIC_D = "https://vinor.ir/d"

    sealed class Outcome {
        object Ok : Outcome()
        object Paused : Outcome()
        data class Transient(val message: String) : Outcome()
        data class Fatal(val message: String, val reason: String) : Outcome()
    }

    fun interface ProgressListener {
        fun onProgress(downloaded: Long, total: Long, percent: Int)
    }

    fun interface CancelFlag {
        fun isCancelled(): Boolean
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun candidateUrls(primary: String, sourceUrl: String): List<String> {
        val out = linkedSetOf<String>()
        fun add(raw: String) {
            val abs = VinorDownloadBridge.resolveAbsoluteUrl(raw) ?: return
            if (!isAllowedApkUrl(abs)) return
            out.add(abs)
        }
        add(primary)
        add(PUBLIC_D_SLASH)
        add(PUBLIC_D)
        add(sourceUrl)
        return out.toList()
    }

    fun isAllowedApkUrl(url: String): Boolean {
        if (!UrlUtils.isTrustedVinorHttps(url)) return false
        return UrlUtils.isApkDownloadUrl(url) || UrlUtils.isSafePublicDownloadUrl(url)
    }

    fun download(
        context: Context,
        absUrl: String,
        versionName: String,
        expectedSize: Long,
        cancel: CancelFlag,
        progress: ProgressListener,
    ): Outcome {
        if (!isAllowedApkUrl(absUrl)) {
            return Outcome.Fatal("unknown_apk_source", "untrusted_url")
        }
        val target = VinorApkUpdateFiles.apkFile(context, versionName)
        val tmp = VinorApkUpdateFiles.partFile(context, versionName)
        try {
            if (target.exists() && VinorApkUpdateFiles.isValidApkFile(target)) {
                return Outcome.Ok
            }
            tmp.parentFile?.mkdirs()

            val clientV = VinorAppUpdateChecker.appVersionName(context)
            val clientCode = VinorAppUpdateChecker.appVersionCode(context)
            var currentUrl = absUrl
            for (hop in 0 until MAX_REDIRECTS) {
                if (cancel.isCancelled()) return Outcome.Paused
                val resumeFrom = if (tmp.exists()) tmp.length().coerceAtLeast(0L) else 0L
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("Accept", "application/vnd.android.package-archive,*/*")
                    .header("User-Agent", VinorUserAgent.forHttpRequest(context))
                    .apply {
                        if (clientV.isNotEmpty()) header("X-Vinor-App-Version", clientV)
                        if (clientCode > 0L) header("X-Vinor-App-Version-Code", clientCode.toString())
                        VinorAppUpdateRange.rangeHeader(resumeFrom)?.let { header("Range", it) }
                    }
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code in 300..399) {
                        val loc = response.header("Location").orEmpty().trim()
                        if (loc.isEmpty()) return Outcome.Transient("redirect_no_location")
                        val next = resolveRedirectUrl(currentUrl, loc)
                            ?: return Outcome.Transient("bad_redirect")
                        if (!UrlUtils.isTrustedVinorHttps(next)) {
                            return Outcome.Fatal("unknown_apk_source", "untrusted_redirect")
                        }
                        if (!UrlUtils.isApkDownloadUrl(next) && !UrlUtils.isSafePublicDownloadUrl(next)) {
                            return Outcome.Transient("redirect_not_apk")
                        }
                        currentUrl = next
                        return@use
                    }

                    val body = response.body ?: return Outcome.Transient("empty_body")
                    val ct = response.header("Content-Type").orEmpty().lowercase()
                    if (ct.contains("text/html") || ct.contains("application/json")) {
                        return Outcome.Transient("unexpected_content_type")
                    }

                    val remaining = body.contentLength()
                    val action = VinorAppUpdateRange.decide(
                        resumeFrom = resumeFrom,
                        httpCode = code,
                        contentLength = remaining,
                        expectedTotal = expectedSize,
                    )
                    when (action) {
                        VinorAppUpdateRange.Action.FAIL_TRANSIENT ->
                            return Outcome.Transient("http_$code")
                        VinorAppUpdateRange.Action.COMPLETE -> {
                            if (VinorApkUpdateFiles.isValidApkFile(tmp) ||
                                VinorApkUpdateFiles.isValidApkFile(target)
                            ) {
                                return Outcome.Ok
                            }
                            runCatching { tmp.delete() }
                            return Outcome.Transient("range_complete_invalid")
                        }
                        VinorAppUpdateRange.Action.RESTART -> {
                            runCatching { tmp.delete() }
                            if (code != 200) {
                                return Outcome.Transient("restart_http_$code")
                            }
                        }
                        VinorAppUpdateRange.Action.APPEND -> Unit
                    }

                    val append = tmp.exists() && tmp.length() > 0L && action == VinorAppUpdateRange.Action.APPEND && code == 206
                    val startAt = if (append) tmp.length() else 0L
                    if (!append && tmp.exists()) tmp.delete()
                    val totalHint = when {
                        expectedSize > 0L -> expectedSize
                        code == 206 && remaining > 0L -> startAt + remaining
                        remaining > 0L -> remaining
                        else -> 0L
                    }

                    var written = startAt
                    progress.onProgress(written, totalHint, percent(written, totalHint))
                    body.byteStream().use { input ->
                        FileOutputStream(tmp, append).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancel.isCancelled()) return Outcome.Paused
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                written += n
                                progress.onProgress(written, totalHint, percent(written, totalHint))
                            }
                            output.flush()
                        }
                    }

                    if (expectedSize > 0L && tmp.length() > expectedSize) {
                        tmp.delete()
                        return Outcome.Transient("size_exceeded")
                    }
                    if (expectedSize > 0L && tmp.length() < expectedSize && remaining > 0L && !append) {
                        if (tmp.length() != remaining && tmp.length() != expectedSize) {
                            Log.w(TAG, "Incomplete size=${tmp.length()} expected=$expectedSize")
                            return Outcome.Transient("incomplete")
                        }
                    }
                    if (code == 200 && remaining > 0L && tmp.length() != remaining && expectedSize <= 0L) {
                        tmp.delete()
                        return Outcome.Transient("incomplete")
                    }
                    return Outcome.Ok
                }
            }
            return Outcome.Transient("too_many_redirects")
        } catch (e: java.io.InterruptedIOException) {
            Log.w(TAG, "download timeout/interrupt", e)
            return if (cancel.isCancelled()) Outcome.Paused else Outcome.Transient("timeout")
        } catch (e: java.io.IOException) {
            val msg = e.message.orEmpty().lowercase()
            Log.w(TAG, "download IO: $absUrl", e)
            if (cancel.isCancelled()) return Outcome.Paused
            if ("space" in msg || "enospc" in msg || "no space" in msg) {
                return Outcome.Transient("storage_full")
            }
            return Outcome.Transient(e.message ?: "network")
        } catch (e: Exception) {
            Log.e(TAG, "download error: $absUrl", e)
            return Outcome.Transient(e.message ?: "error")
        }
    }

    private fun percent(downloaded: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
    }

    private fun resolveRedirectUrl(fromUrl: String, location: String): String? {
        val loc = location.trim()
        if (loc.isEmpty()) return null
        return try {
            when {
                loc.startsWith("https://", ignoreCase = true) -> loc
                loc.startsWith("http://", ignoreCase = true) ->
                    "https://" + loc.substring(7)
                loc.startsWith("//") -> "https:$loc"
                else -> URI(fromUrl).resolve(loc).toString()
            }
        } catch (_: Exception) {
            null
        }
    }
}
