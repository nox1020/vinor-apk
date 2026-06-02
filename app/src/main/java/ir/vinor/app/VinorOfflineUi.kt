package ir.vinor.app

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.isVisible

/**
 * UI حالت آفلاین: بازخورد لمسی، حالت «در حال بررسی»، بازنشانی دکمه.
 */
class VinorOfflineUi(private val container: View) {

    private val tapArea: View = container.findViewById(R.id.offlineTapArea)
    private val retryButton: AppCompatButton = container.findViewById(R.id.retryButton)
    private val retryProgress: ProgressBar = container.findViewById(R.id.retryProgress)

    fun reset() {
        retryButton.isEnabled = true
        retryProgress.isVisible = false
        retryButton.text = container.context.getString(R.string.offline_retry)
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
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            @Suppress("DEPRECATION")
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
