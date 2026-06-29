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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.webkit.CookieManager
import java.util.concurrent.TimeUnit

/**
 * ورود نیتیو - مرحله ۱: وارد کردن شماره موبایل و دریافت کد OTP.
 */
class LoginStep1Fragment : Fragment() {

    private var _binding: FragmentLoginStep1Binding? = null
    private val binding get() = _binding!!

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "LoginStep1"
        private const val BASE = "https://vinor.ir"
        private const val API_LOGIN_REQUEST = "/express/partner/api/login-request"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoginStep1Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.loginStep1Back.setOnClickListener { findNavController().navigateUp() }
        binding.loginStep1Submit.setOnClickListener { submitPhone() }
        binding.loginStep1Phone.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitPhone()
                true
            } else false
        }
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    /**
     * نرمال‌سازی مطابق بک‌اند (notifications._normalize_user_id / _normalize_phone):
     * خروجی همیشه ۱۱ رقم به صورت 09xxxxxxxxx
     */
    private fun normalizePhone(input: String): String {
        val digits = input.filter { it.isDigit() }
        return when {
            digits.startsWith("0098") && digits.length >= 14 -> "0" + digits.drop(4).take(10)
            digits.startsWith("98") && digits.length >= 12 -> "0" + digits.drop(2).take(10)
            digits.length == 11 && digits.startsWith("0") -> digits
            digits.length == 10 && digits.startsWith("9") -> "0$digits"
            else -> digits.take(11)
        }
    }

    private fun submitPhone() {
        val raw = binding.loginStep1Phone.text?.toString()?.trim() ?: ""
        val phone = normalizePhone(raw)
        binding.loginStep1Error.visibility = View.GONE
        if (phone.length != 11 || !phone.startsWith("09")) {
            binding.loginStep1Error.text = "لطفاً یک شماره موبایل ۱۱ رقمی معتبر وارد کنید."
            binding.loginStep1Error.visibility = View.VISIBLE
            return
        }

        binding.loginStep1Progress.visibility = View.VISIBLE
        binding.loginStep1Submit.isEnabled = false

        scope.launch {
            try {
                val json = JSONObject()
                    .put("phone", phone)
                    .put("accept_legal", true)
                    .toString()
                val req = Request.Builder()
                    .url("$BASE$API_LOGIN_REQUEST")
                    .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                saveCookiesFromResponse(resp)
                val body = resp.body?.string() ?: ""
                val code = resp.code
                val obj = try { JSONObject(body) } catch (_: Exception) { null }
                val success = resp.isSuccessful && (obj?.optBoolean("success", false) == true)

                if (!success && code != 200) {
                    Log.w(TAG, "login-request failed: code=$code body=${body.take(300)}")
                }

                withContext(Dispatchers.Main) {
                    binding.loginStep1Progress.visibility = View.GONE
                    binding.loginStep1Submit.isEnabled = true
                    if (_binding == null) return@withContext
                    if (success) {
                        if (isAdded) Toast.makeText(requireContext(), obj?.optString("message", "کد تأیید ارسال شد."), Toast.LENGTH_SHORT).show()
                        val bundle = android.os.Bundle().apply { putString("phone", phone) }
                        view?.post {
                            if (!isAdded) return@post
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
                        binding.loginStep1Error.text = obj?.optString("error", "خطا در ارتباط. دوباره تلاش کنید.")
                        binding.loginStep1Error.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "login-request error", e)
                withContext(Dispatchers.Main) {
                    binding.loginStep1Progress.visibility = View.GONE
                    binding.loginStep1Submit.isEnabled = true
                    if (_binding != null) {
                        binding.loginStep1Error.text = "خطا در ارتباط. دوباره تلاش کنید."
                        binding.loginStep1Error.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun saveCookiesFromResponse(resp: okhttp3.Response) {
        try {
            val setCookies = resp.headers("Set-Cookie")
            setCookies.forEach { value ->
                CookieManager.getInstance().setCookie(BASE, value)
            }
            CookieManager.getInstance().flush()
            if (setCookies.isEmpty()) Log.w(TAG, "login-request: no Set-Cookie in response")
        } catch (_: Exception) { }
    }
}
