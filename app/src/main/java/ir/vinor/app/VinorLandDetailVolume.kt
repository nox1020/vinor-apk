package ir.vinor.app

import android.app.Activity
import android.media.AudioManager
import android.view.KeyEvent
import android.webkit.WebView

/**
 * جزئیات فایل — با دکمه‌های صدا (بالا/پایین) ویدیوی گالری از حالت بی‌صدا خارج می‌شود.
 */
object VinorLandDetailVolume {

    private val LAND_DETAIL_PATH =
        Regex("/express/partner/lands/[^/?#]+", RegexOption.IGNORE_CASE)

    fun isLandDetailUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return LAND_DETAIL_PATH.containsMatchIn(url)
    }

    /** روی صفحه جزئیات، دکمه صدا ولوم مدیا را تنظیم کند نه زنگ. */
    fun syncVolumeControlStream(activity: Activity, url: String?) {
        activity.volumeControlStream = if (isLandDetailUrl(url)) {
            AudioManager.STREAM_MUSIC
        } else {
            AudioManager.STREAM_NOTIFICATION
        }
    }

    /**
     * @return false — رویداد را consume نمی‌کنیم تا سطح صدا همزمان تنظیم شود.
     */
    fun onVolumeKeyDown(activity: Activity, webView: WebView, keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        val url = webView.url
        if (!isLandDetailUrl(url)) return false
        syncVolumeControlStream(activity, url)
        webView.evaluateJavascript(
            """
            (function(){
              try {
                if (window.vinorLandDetailHandleVolumeKey) {
                  return window.vinorLandDetailHandleVolumeKey();
                }
              } catch (e) {}
              return false;
            })();
            """.trimIndent(),
            null
        )
        return false
    }
}
