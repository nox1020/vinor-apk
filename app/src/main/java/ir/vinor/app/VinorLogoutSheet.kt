package ir.vinor.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import ir.vinor.app.databinding.SheetLogoutConfirmBinding

/** باتم‌شیت تأیید خروج؛ همان سازوکار شیت فیلتر جستجو. */
object VinorLogoutSheet {

    private const val STUCK_WATCHDOG_MS = 12_000L
    private var current: BottomSheetDialog? = null

    fun show(fragment: Fragment, onConfirm: (finish: () -> Unit) -> Unit) {
        if (!fragment.isAdded) return
        current?.setOnDismissListener(null)
        current?.dismiss()
        val dialog = BottomSheetDialog(fragment.requireContext())
        val sheet = SheetLogoutConfirmBinding.inflate(fragment.layoutInflater)
        dialog.setContentView(sheet.root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setDimAmount(0.6f)
        val baseBottom = sheet.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(sheet.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, baseBottom + bars.bottom)
            insets
        }
        dialog.setOnShowListener {
            val panel = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (panel != null) {
                panel.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                ViewCompat.setAccessibilityPaneTitle(panel, sheet.logoutTitle.text)
                BottomSheetBehavior.from(panel).apply {
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
            sheet.logoutCancel.isFocusableInTouchMode = true
            sheet.logoutCancel.requestFocus()
        }
        var running = false
        fun release() {
            running = false
            sheet.logoutConfirm.isEnabled = true
            sheet.logoutConfirm.isClickable = true
            sheet.logoutConfirmProgress.isVisible = false
            sheet.logoutConfirmLabel.isVisible = true
            if (dialog.isShowing) dialog.dismiss()
        }
        sheet.logoutCancel.setOnClickListener { dialog.dismiss() }
        sheet.logoutConfirm.setOnClickListener {
            if (running) return@setOnClickListener
            running = true
            sheet.logoutConfirm.isEnabled = false
            sheet.logoutConfirm.isClickable = false
            sheet.logoutConfirmProgress.isVisible = true
            sheet.logoutConfirmLabel.isVisible = false
            onConfirm(::release)
            sheet.logoutConfirm.postDelayed({
                if (running) release()
            }, STUCK_WATCHDOG_MS)
        }
        dialog.setOnDismissListener {
            if (current === dialog) current = null
        }
        current = dialog
        dialog.show()
        ViewCompat.requestApplyInsets(sheet.root)
    }
}
