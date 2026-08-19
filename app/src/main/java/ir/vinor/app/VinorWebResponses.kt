package ir.vinor.app

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.HashMap

/**
 * پاسخ Intercept پایدار برای WebView.
 * mapOf غیرقابل‌تغییر و encoding=null روی Chromium کرش می‌دهند.
 */
object VinorWebResponses {

    fun html(bytes: ByteArray): WebResourceResponse =
        of("text/html", "utf-8", bytes)

    fun json(bytes: ByteArray): WebResourceResponse =
        of("application/json", "utf-8", bytes)

    fun of(mime: String?, encoding: String?, bytes: ByteArray): WebResourceResponse {
        val safeMime = mime
            ?.substringBefore(';')
            ?.trim()
            ?.ifBlank { null }
            ?: "application/octet-stream"
        val safeEnc = encoding?.trim().orEmpty().ifBlank { "utf-8" }
        return WebResourceResponse(
            safeMime,
            safeEnc,
            200,
            "OK",
            HashMap<String, String>(),
            ByteArrayInputStream(bytes),
        )
    }
}
