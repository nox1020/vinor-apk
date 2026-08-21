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

    private val offlineJson =
        """{"ok":false,"success":false,"offline":true}""".toByteArray(Charsets.UTF_8)

    private val silentHtmlBytes =
        """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;background:#030712;height:100%;overflow:hidden}</style></head><body></body></html>"""
            .toByteArray(Charsets.UTF_8)

    fun html(bytes: ByteArray): WebResourceResponse =
        of("text/html", "utf-8", bytes)

    fun json(bytes: ByteArray): WebResourceResponse =
        of("application/json", "utf-8", bytes)

    fun jsonOffline(): WebResourceResponse = json(offlineJson)

    fun silentHtml(): WebResourceResponse = html(silentHtmlBytes)

    /**
     * پاسخ miss آفلاین. بدنهٔ خالی برای فونت/عکس رندرر Chromium را می‌کشد → null
     * تا WebView همان منبع را fail کند، نه اینکه بایت نامعتبر سرو شود.
     */
    fun forCacheMiss(mime: String?): WebResourceResponse? {
        val safeMime = mime
            ?.substringBefore(';')
            ?.trim()
            ?.ifBlank { null }
            ?: "application/octet-stream"
        if (VinorCachePolicy.isBinaryMime(safeMime)) return null
        val lower = safeMime.lowercase()
        if (lower.contains("json")) return jsonOffline()
        if (lower.contains("html")) return silentHtml()
        return of(safeMime, VinorCachePolicy.interceptCharset(safeMime), ByteArray(0))
    }

    fun empty(mime: String?): WebResourceResponse =
        forCacheMiss(mime) ?: jsonOffline()

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
