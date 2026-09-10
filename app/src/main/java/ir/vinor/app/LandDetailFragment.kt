package ir.vinor.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentLandDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LandDetailFragment : Fragment() {

    private var _binding: FragmentLandDetailBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        _binding = FragmentLandDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.landDetailBack.setOnClickListener { findNavController().navigateUp() }
        val code = arguments?.getString("code").orEmpty()
        if (code.isBlank()) {
            binding.landDetailError.isVisible = true
            binding.landDetailError.text = "کد فایل نامعتبر است."
            return
        }
        load(code)
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun load(code: String) {
        binding.landDetailProgress.isVisible = true
        scope.launch {
            try {
                val obj = withContext(Dispatchers.IO) {
                    PartnerApi.get("/api/express/$code", requireContext())
                }
                if (_binding == null) return@launch
                if (!obj.optBoolean("success")) {
                    binding.landDetailError.isVisible = true
                    binding.landDetailError.text = obj.optString("error", "فایل پیدا نشد.")
                    return@launch
                }
                val land = obj.optJSONObject("land") ?: JSONObject()
                binding.landDetailTitle.text = land.optString("title", "—")
                val price = land.optInt("price_total").takeIf { it > 0 } ?: land.optInt("price")
                binding.landDetailPrice.text = PartnerFormat.toman(price) + " تومان"
                val meta = listOf(
                    land.optString("city"),
                    land.optString("location"),
                    land.optString("size"),
                    land.optString("category"),
                    code,
                ).filter { it.isNotBlank() }.joinToString(" · ")
                binding.landDetailMeta.text = meta
                val body = land.optString("description").ifBlank { land.optString("body") }
                binding.landDetailBody.text = body
                PartnerImages.bind(scope, binding.landDetailImage, PartnerImages.coverUrl(land))
            } catch (e: Exception) {
                if (_binding != null) {
                    binding.landDetailError.isVisible = true
                    binding.landDetailError.text = e.message ?: "خطا در بارگذاری"
                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (_binding != null) binding.landDetailProgress.isVisible = false
            }
        }
    }
}
