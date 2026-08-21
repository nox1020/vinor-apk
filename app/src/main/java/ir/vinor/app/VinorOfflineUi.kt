package ir.vinor.app

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.isVisible
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI حالت آفلاین: زمان آخرین کش، بازخورد لمسی، حالت «در حال بررسی».
 */
class VinorOfflineUi(private val container: View) {

    private val tapArea: View = container.findViewById(R.id.offlineTapArea)
    private val retryButton: AppCompatButton = container.findViewById(R.id.retryButton)
    private val retryProgress: ProgressBar = container.findViewById(R.id.retryProgress)
    private val lastSaved: TextView? = container.findViewById(R.id.offlineLastSaved)
    private val message: TextView? = container.findViewById(R.id.offlineMessage)

    fun reset() {
        retryButton.isEnabled = true
        retryProgress.isVisible = false
        retryButton.text = container.context.getString(R.string.offline_retry)
        bindLastSaved(VinorWebOfflineSession.lastCachedAt(container.context))
    }

    fun bindLastSaved(epochMs: Long) {
        val label = lastSaved ?: return
        if (epochMs <= 0L) {
            label.visibility = View.GONE
            message?.setText(R.string.offline_message)
            return
        }
        val fmt = SimpleDateFormat("HH:mm", Locale("fa", "IR"))
        val whenText = fmt.format(Date(epochMs))
        label.text = container.context.getString(R.string.offline_last_saved, whenText)
        label.visibility = View.VISIBLE
        message?.setText(R.string.offline_message_cached)
    }

    fun bindRetry(onRetry: () -> Unit) {
        val listener = View.OnClickListener { v ->
            if (!retryButton.isEnabled) return@OnClickListener
            performTap(v)
            setChecking(true)
            onRetry()
        }
        retryButton.setOnClickListener(listener)
        tapArea.setOnClickListener(listener)
    }

    fun setChecking(checking: Boolean) {
        retryButton.isEnabled = !checking
        retryProgress.isVisible = checking
        retryButton.text = container.context.getString(
            if (checking) R.string.offline_checking else R.string.offline_retry
        )
    }

    private fun performTap(view: View) {
        runCatching {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                @Suppress("DEPRECATION")
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
    }
}

object VinorTapFeedback {
    fun perform(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }
}
