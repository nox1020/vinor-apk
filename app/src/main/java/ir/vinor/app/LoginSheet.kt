package ir.vinor.app

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * ورود درجا: همان LoginStep1/LoginStep2 داخل یک باتم‌شیت روی صفحهٔ فعلی.
 * بعد از ورود، صفحه عوض نمی‌شود و MainActivity کار معلق (deep link، چت، بازگشت) را ادامه می‌دهد.
 */
class LoginSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "vinor_login_sheet"
        private const val ARG_EXPIRED = "expired"

        fun isShowing(fm: FragmentManager): Boolean = fm.findFragmentByTag(TAG) != null

        fun show(fm: FragmentManager, expired: Boolean = false) {
            if (fm.isStateSaved) return
            if (isShowing(fm)) return
            LoginSheet().apply {
                arguments = Bundle().apply { putBoolean(ARG_EXPIRED, expired) }
            }.show(fm, TAG)
        }

        fun of(fragment: androidx.fragment.app.Fragment): LoginSheet? {
            var p = fragment.parentFragment
            while (p != null) {
                if (p is LoginSheet) return p
                p = p.parentFragment
            }
            return null
        }
    }

    private var authenticated = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext())
        dialog.setOnShowListener {
            val panel = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            panel.setBackgroundColor(Color.TRANSPARENT)
            BottomSheetBehavior.from(panel).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val h = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        return FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            minimumHeight = h
            setBackgroundResource(R.drawable.bg_login_sheet)
            clipToOutline = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val expired = arguments?.getBoolean(ARG_EXPIRED, false) == true
        InlineTabLogin.show(this, view as ViewGroup, "ورود", expired) {
            authenticated = true
            val host = activity as? MainActivity
            dismissAllowingStateLoss()
            host?.continueAfterInlineLogin()
        }
    }

    override fun onDestroy() {
        if (!authenticated) (activity as? MainActivity)?.loginSheetCancelled()
        super.onDestroy()
    }
}
