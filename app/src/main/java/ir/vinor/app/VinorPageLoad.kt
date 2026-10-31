package ir.vinor.app

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject

/** همان قرارداد لود «وینور من»: حافظهٔ فوری، شبکه قبل از کش، و خطا بدون پاک کردن صفحه. */
object VinorPageLoad {
    const val OFFLINE = "اتصال برقرار نشد"
    const val RETRY = "تلاش دوباره"

    fun networkFailed(wasFresh: Boolean, obj: JSONObject): Boolean {
        if (wasFresh) return false
        return obj.optBoolean("from_cache") ||
            obj.optBoolean("offline") ||
            (obj.has("http_status") && obj.optInt("http_status") !in 200..299)
    }

    fun needsLogin(obj: JSONObject): Boolean {
        return obj.optBoolean("session_expired") ||
            obj.optInt("http_status") == 401 ||
            PartnerApi.isSessionExpired(obj)
    }

    /** A cached snapshot may paint immediately only when it belongs to an active session. */
    fun canShowCached(obj: JSONObject?, requiresLogin: Boolean, loggedIn: Boolean): Boolean {
        if (obj == null || obj.length() == 0) return false
        if (requiresLogin && !loggedIn) return false
        if (obj.optBoolean("offline") || obj.optBoolean("non_json") || needsLogin(obj)) return false
        val code = if (obj.has("http_status")) obj.optInt("http_status") else 200
        return code in 200..299 && obj.optString("error") != "unauthorized"
    }

    fun canRemember(obj: JSONObject): Boolean {
        if (obj.optBoolean("from_cache") || obj.optBoolean("offline")) return false
        if (obj.optBoolean("session_expired") || obj.optBoolean("non_json")) return false
        if (needsLogin(obj) || obj.optInt("http_status") == 403) return false
        val code = if (obj.has("http_status")) obj.optInt("http_status") else 200
        return code in 200..299
    }

    fun rememberedCopy(obj: JSONObject): JSONObject {
        val copy = JSONObject(obj.toString())
        copy.remove("from_cache")
        return copy
    }

    fun showOffline(anchor: View, retry: () -> Unit) {
        val bar = Snackbar.make(anchor, OFFLINE, Snackbar.LENGTH_LONG)
        bar.setAction(RETRY) { retry() }
        val ctx = anchor.context
        bar.setActionTextColor(ContextCompat.getColor(ctx, R.color.vinor_on_primary))
        bar.view.setBackgroundColor(ContextCompat.getColor(ctx, R.color.vinor_raised))
        bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.let { text ->
            text.setTextColor(ContextCompat.getColor(ctx, R.color.vinor_text))
            text.typeface = ResourcesCompat.getFont(ctx, R.font.vazirmatn)
        }
        bar.show()
    }
}
