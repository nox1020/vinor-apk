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
        syncFav(code)
        binding.landDetailFav.setOnClickListener {
            VinorFavoritesStore.toggle(requireContext(), code)
            syncFav(code)
        }
        load(code)
    }

    private fun syncFav(code: String) {
        val on = VinorFavoritesStore.contains(requireContext(), code)
        binding.landDetailFav.imageTintList =
            android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(if (on) "#FBBF24" else "#A1A1AA"),
            )
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun load(code: String) {
        val ctx = context ?: return
        binding.landDetailProgress.isVisible = true
        scope.launch {
            try {
                val obj = PartnerApi.loadDisplay("/api/express/$code", ctx) { data ->
                    if (_binding == null) return@loadDisplay
                    bindLand(code, data)
                }
                if (_binding == null) return@launch
                if (!obj.optBoolean("success") && obj.optJSONObject("land") == null) {
                    binding.landDetailError.isVisible = true
                    binding.landDetailError.text = obj.optString("error", "فایل پیدا نشد.")
                }
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

    private fun bindLand(code: String, obj: JSONObject) {
        if (!obj.optBoolean("success") && obj.optJSONObject("land") == null) return
        val land = obj.optJSONObject("land") ?: JSONObject()
        binding.landDetailError.isVisible = false
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
    }
}
