package ir.vinor.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.widget.AppCompatButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight entry screen focused on fastest first frame and immediate feedback.
 */
class PreloaderActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var retry: AppCompatButton
    private lateinit var content: LinearLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(VinorLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        super.onCreate(savedInstanceState)
        SystemUi.apply(this)
        setContentView(R.layout.activity_preloader)
        SystemUi.install(findViewById(android.R.id.content))

        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)
        retry = findViewById(R.id.retryButton)
        content = findViewById(R.id.preloaderContent)

        val retryAction = {
            VinorTapFeedback.perform(retry)
            setChecking(true)
            startFlow()
        }
        retry.setOnClickListener { retryAction() }
        content.setOnClickListener {
            if (retry.isVisible) retryAction()
        }

        window.decorView.post { startFlow() }
    }

    private fun startFlow() {
        lifecycleScope.launch {
            val hasInternet = withContext(Dispatchers.IO) { isOnline() }
            if (!hasInternet) {
                showNoInternet()
                return@launch
            }
            prewarmWebView()
            goToMain()
        }
    }

    private suspend fun prewarmWebView() {
        withContext(Dispatchers.Main) {
            runCatching { WebViewProvider.prewarm(this@PreloaderActivity) }
        }
    }

    private fun goToMain() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(0, 0)
        finish()
    }

    private fun showNoInternet() {
        setChecking(false)
        progress.isVisible = false
        retry.isVisible = true
        statusText.text = getString(R.string.offline_title)
    }

    private fun setChecking(checking: Boolean) {
        retry.isEnabled = !checking
        progress.isVisible = checking
        if (checking) {
            retry.isVisible = true
            retry.text = getString(R.string.offline_checking)
            statusText.text = getString(R.string.offline_checking)
            return
        }
        if (retry.isVisible) {
            retry.text = getString(R.string.offline_retry)
            statusText.text = getString(R.string.offline_title)
            return
        }
        statusText.text = getString(R.string.preloader_connecting)
        progress.isVisible = true
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val ni = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            ni != null && ni.isConnected
        }
    }
}
