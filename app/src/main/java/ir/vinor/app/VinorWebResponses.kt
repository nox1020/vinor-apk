package ir.vinor.app

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * پاسخ Intercept پایدار برای WebView.
 *
 * سازندهٔ ۶آرگومان (status/headers) و mapOf غیرقابل‌تغییر روی چند نسخهٔ Chromium
 * داخل shouldInterceptRequest رندرر را می‌کشند. فقط سازندهٔ ۳آرگومان.
 * encoding برای باینری باید خالی باشد، نه utf-8 و نه null.
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
        val safeEnc = VinorCachePolicy.interceptCharset(safeMime, encoding)
        return WebResourceResponse(safeMime, safeEnc, ByteArrayInputStream(bytes))
    }
}
