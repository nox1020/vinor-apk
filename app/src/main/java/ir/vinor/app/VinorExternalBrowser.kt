package ir.vinor.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import java.util.Locale

/** http(s) URLs that must not stay in a bridged WebView. Custom Tabs, then a normal browser. */
object VinorExternalBrowser {
    private const val TAG = "VinorExternalBrowser"

    fun open(context: Context, url: String): Boolean {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrBlank()) return false
        return try {
            val tabs = CustomTabsIntent.Builder().setShowTitle(true).build()
            if (context !is Activity) {
                tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            tabs.launchUrl(context, uri)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Custom Tabs failed for host=${uri.host}", e)
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.w(TAG, "No browser for host=${uri.host}", e2)
                false
            }
        }
    }
}
