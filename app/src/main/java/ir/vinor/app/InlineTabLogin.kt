package ir.vinor.app

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit

/**
 * Host the canonical LoginStep1Fragment/LoginStep2Fragment inside Customers and
 * Follow-up instead of maintaining a duplicate OTP UI and network flow.
 */
object InlineTabLogin {
    const val ARG_INLINE = "vinor_inline_tab_login"
    private const val TAG = "vinor_shared_inline_login"
    private const val AUTH_RESULT = "vinor_inline_login_authenticated"

    fun isInline(fragment: Fragment): Boolean =
        fragment.arguments?.getBoolean(ARG_INLINE, false) == true

    fun show(
        fragment: Fragment,
        container: ViewGroup,
        tab: String,
        expired: Boolean = false,
        onAuthenticated: () -> Unit,
    ) {
        fragment.childFragmentManager.setFragmentResultListener(
            AUTH_RESULT, fragment.viewLifecycleOwner,
        ) { _, _ -> onAuthenticated() }
        if (fragment.childFragmentManager.findFragmentByTag(TAG) != null && !expired) return
        val args = Bundle().apply {
            putBoolean(ARG_INLINE, true)
            if (expired) putString(
                LoginStep1Fragment.ARG_REASON,
                LoginStep1Fragment.REASON_SESSION_EXPIRED,
            )
        }
        fragment.childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(container.id, LoginStep1Fragment().apply { arguments = args }, TAG)
        }
    }

    fun showOtp(from: LoginStep1Fragment, args: Bundle): Boolean {
        if (!isInline(from) || from.id == View.NO_ID || !from.isAdded) return false
        args.putBoolean(ARG_INLINE, true)
        from.parentFragmentManager.commit {
            setReorderingAllowed(true)
            replace(from.id, LoginStep2Fragment().apply { arguments = args }, TAG)
        }
        return true
    }

    fun backToPhone(from: LoginStep2Fragment): Boolean {
        if (!isInline(from) || from.id == View.NO_ID || !from.isAdded) return false
        val args = Bundle().apply { putBoolean(ARG_INLINE, true) }
        from.parentFragmentManager.commit {
            setReorderingAllowed(true)
            replace(from.id, LoginStep1Fragment().apply { arguments = args }, TAG)
        }
        return true
    }

    fun finish(from: LoginStep2Fragment): Boolean {
        if (!isInline(from)) return false
        from.parentFragmentManager.setFragmentResult(AUTH_RESULT, Bundle.EMPTY)
        return true
    }

    fun dismiss(parent: Fragment) {
        val child = parent.childFragmentManager.findFragmentByTag(TAG) ?: return
        parent.childFragmentManager.commit {
            setReorderingAllowed(true)
            remove(child)
        }
    }
}
