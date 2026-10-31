package ir.vinor.app

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentLoginStep1Binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** ورود نیتیو - مرحله ۱: شماره موبایل و دریافت کد OTP. */
class LoginStep1Fragment : Fragment() {

    private var _binding: FragmentLoginStep1Binding? = null
    private val binding get() = _binding!!

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    // Capture fast OTPs arriving before the app navigates to the code screen.
    private var earlySmsOtp: String? = null

    companion object {
        private const val TAG = "LoginStep1"
        private const val API_LOGIN_REQUEST = "/express/partner/api/login-request"
        const val ARG_REASON = "auth_reason"
        const val REASON_SESSION_EXPIRED = AuthReturnStore.REASON_SESSION_EXPIRED
    }

    private fun bindReauthHint() {
        val reason = arguments?.getString(ARG_REASON).orEmpty()
        binding.loginStep1ReauthHint.visibility =
            if (reason == REASON_SESSION_EXPIRED) View.VISIBLE else View.GONE
    }

    fun showSessionExpiredHint() {
        if (_binding == null) return
        arguments = (arguments ?: android.os.Bundle()).apply {
            putString(ARG_REASON, REASON_SESSION_EXPIRED)
        }
        binding.loginStep1ReauthHint.visibility = View.VISIBLE
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoginStep1Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.loginStep1Back.setOnClickListener {
            val sheet = LoginSheet.of(this)
            if (sheet != null) sheet.dismiss()
            else if (InlineTabLogin.isInline(this)) (activity as? MainActivity)?.openPartnerHome()
            else findNavController().navigateUp()
        }
        binding.loginStep1Submit.setOnClickListener { submitPhone() }
        bindReauthHint()
        binding.loginStep1Phone.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitPhone()
                true
            } else false
        }
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.stopOtpSms(viewLifecycleOwner)
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun submitPhone() {
        val raw = binding.loginStep1Phone.text?.toString()?.trim() ?: ""
        val phone = PartnerApi.normalizePhone(raw)
        binding.loginStep1Error.visibility = View.GONE
        if (phone.length != 11 || !phone.startsWith("09")) {
            binding.loginStep1Error.text = "لطفاً یک شماره موبایل ۱۱ رقمی معتبر وارد کنید."
            binding.loginStep1Error.visibility = View.VISIBLE
            return
        }

        earlySmsOtp = null
        (activity as? MainActivity)?.startOtpSms(viewLifecycleOwner) { incoming ->
            earlySmsOtp = OtpSmsAutoFill.normalizeOtpDigits(incoming).takeIf { it.length == 5 }
        }
        binding.loginStep1Progress.visibility = View.VISIBLE
        binding.loginStep1Submit.isEnabled = false

        scope.launch {
            try {
                val body = JSONObject()
                    .put("phone", phone)
                    .put("accept_legal", true)
                val obj = withContext(Dispatchers.IO) {
                    PartnerApi.postJson(API_LOGIN_REQUEST, body, requireContext())
                }
                val success = obj.optBoolean("success", false)

                withContext(Dispatchers.Main) {
                    val b = _binding ?: return@withContext
                    b.loginStep1Progress.visibility = View.GONE
                    b.loginStep1Submit.isEnabled = true
                    if (success) {
                        if (isAdded) Toast.makeText(requireContext(), obj.optString("message", "کد تأیید ارسال شد."), Toast.LENGTH_SHORT).show()
                        val bundle = android.os.Bundle().apply {
                            putString("phone", phone)
                            if (obj.has("retry_after")) putInt("retry_after", obj.optInt("retry_after", 60))
                        }
                        view?.post {
                            if (!isAdded) return@post
                            earlySmsOtp?.let { bundle.putString("early_sms_otp", it) }
                            if (InlineTabLogin.showOtp(this@LoginStep1Fragment, bundle)) return@post
                            try {
                                findNavController().navigate(R.id.action_loginStep1_to_loginStep2, bundle)
                            } catch (e: Exception) {
                                Log.e(TAG, "navigate to step2 failed", e)
                                try {
                                    findNavController().navigate(R.id.loginStep2Fragment, bundle)
                                } catch (e2: Exception) {
                                    Log.e(TAG, "navigate by id also failed", e2)
                                }
                            }
                        }
                    } else {
                        (activity as? MainActivity)?.stopOtpSms(viewLifecycleOwner)
                        earlySmsOtp = null
                        b.loginStep1Error.text = obj.optString("error", "خطا در ارتباط. دوباره تلاش کنید.")
                        b.loginStep1Error.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "login-request error", e)
                withContext(Dispatchers.Main) {
                    val b = _binding ?: return@withContext
                    b.loginStep1Progress.visibility = View.GONE
                    b.loginStep1Submit.isEnabled = true
                    (activity as? MainActivity)?.stopOtpSms(viewLifecycleOwner)
                    earlySmsOtp = null
                    b.loginStep1Error.text = "خطا در ارتباط. دوباره تلاش کنید."
                    b.loginStep1Error.visibility = View.VISIBLE
                }
            }
        }
    }
}
