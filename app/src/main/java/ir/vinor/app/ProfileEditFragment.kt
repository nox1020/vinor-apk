package ir.vinor.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentProfileEditBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * صفحه ویرایش پروفایل - کاملاً نیتیو.
 * داده از GET /express/partner/profile/edit/data؛ ذخیره با POST /express/partner/api/profile/update.
 * آواتار با POST /express/partner/api/profile/avatar.
 */
class ProfileEditFragment : Fragment() {

    private var _binding: FragmentProfileEditBinding? = null
    private val binding get() = _binding!!

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mePhone = ""
    private var currentAvatarUrl: String? = null
    private var avatarUploading = false

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) uploadAvatar(uri)
    }

    companion object {
        private const val TAG = "ProfileEditFragment"
        private const val BASE = "https://vinor.ir"
        private const val PROFILE_EDIT_DATA = "/express/partner/profile/edit/data"
        private const val PROFILE_UPDATE = "/express/partner/api/profile/update"
        private const val PROFILE_AVATAR = "/express/partner/api/profile/avatar"
        private const val AVATAR_MAX_SIDE = 1200
        private const val AVATAR_JPEG_QUALITY = 82
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        clipAvatarToCircle(binding.profileEditAvatar)
        binding.profileEditBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.profileEditCancel.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.profileEditSave.setOnClickListener { save() }
        binding.profileEditAvatarTrigger.setOnClickListener {
            if (!avatarUploading) pickImageLauncher.launch("image/*")
        }
        binding.profileEditBio.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.profileEditBioCount.text = "${s?.length ?: 0}/200"
            }
        })
        binding.profileEditName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (binding.profileEditAvatar.visibility != View.VISIBLE) {
                    bindInitials(s?.toString()?.trim().orEmpty())
                }
            }
        })
        loadData()
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun cookieHeader(): String = CookieManager.getInstance().getCookie(BASE) ?: ""

    private fun clipAvatarToCircle(view: View) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
    }

    private fun initialsFor(name: String, phone: String): String = when {
        name.length >= 2 -> name.take(2)
        phone.length >= 4 -> phone.takeLast(4).take(2)
        else -> "—"
    }

    private fun bindInitials(name: String) {
        if (_binding == null) return
        binding.profileEditInitials.text = initialsFor(name, mePhone)
        if (binding.profileEditAvatar.visibility != View.VISIBLE) {
            binding.profileEditInitials.visibility = View.VISIBLE
        }
    }

    private fun loadData() {
        val ctx = context ?: return
        if (_binding == null) return
        binding.profileEditProgress.visibility = View.VISIBLE
        scope.launch {
            try {
                val obj = PartnerApi.loadDisplay(PROFILE_EDIT_DATA, ctx) { data ->
                    if (_binding == null) return@loadDisplay
                    if (!data.optBoolean("success", false) && data.optString("me_phone").isBlank()) return@loadDisplay
                    bindEditPayload(data)
                }
                if (_binding == null) return@launch
                if (PartnerApi.isSessionExpired(obj)) {
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (!obj.optBoolean("success", false) && obj.optString("me_phone").isBlank()) {
                    binding.profileEditProgress.visibility = View.GONE
                    Toast.makeText(ctx, "خطا در بارگذاری", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadData error", e)
                if (_binding != null) {
                    binding.profileEditProgress.visibility = View.GONE
                    Toast.makeText(ctx, "خطا در بارگذاری", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                }
            }
        }
    }

    private fun bindEditPayload(obj: JSONObject) {
        val meName = obj.optString("me_name", "")
        mePhone = obj.optString("me_phone", "").trim()
        val meCity = obj.optString("me_city", "")
        val meBio = obj.optString("me_bio", "")
        val avatarUrl = obj.optString("avatar_url", "").takeIf { it.isNotEmpty() }
        val citiesArr = obj.optJSONArray("cities") ?: org.json.JSONArray()
        val cities = mutableListOf<String>().apply {
            add("انتخاب شهر")
            for (i in 0 until citiesArr.length()) {
                val c = citiesArr.optString(i, "").trim()
                if (c.isNotEmpty()) add(c)
            }
        }
        binding.profileEditProgress.visibility = View.GONE
        binding.profileEditName.setText(meName)
        binding.profileEditBio.setText(meBio)
        binding.profileEditBioCount.text = "${meBio.length}/200"
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, cities).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.profileEditCity.adapter = adapter
        val cityIndex = cities.indexOf(meCity).takeIf { it >= 0 } ?: 0
        binding.profileEditCity.setSelection(cityIndex)
        bindInitials(meName)
        currentAvatarUrl = avatarUrl
        if (!avatarUrl.isNullOrEmpty()) {
            loadAvatar(if (avatarUrl.startsWith("http")) avatarUrl else "$BASE$avatarUrl")
        } else {
            binding.profileEditAvatar.visibility = View.GONE
            binding.profileEditInitials.visibility = View.VISIBLE
        }
    }

    private fun loadAvatar(fullUrl: String) {
        val ctx = context ?: return
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { PartnerApi.bytes(fullUrl, ctx) } ?: return@launch
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                if (_binding == null) return@launch
                binding.profileEditAvatar.setImageBitmap(bmp)
                binding.profileEditAvatar.visibility = View.VISIBLE
                binding.profileEditInitials.visibility = View.GONE
            } catch (_: Exception) {
            }
        }
    }

    private fun setAvatarUploading(on: Boolean) {
        avatarUploading = on
        if (_binding == null) return
        binding.profileEditAvatarUploading.visibility = if (on) View.VISIBLE else View.GONE
        binding.profileEditAvatarTrigger.isEnabled = !on
    }

    private fun compressImage(uri: Uri): ByteArray? {
        val ctx = context ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (max(w / sample, h / sample) > AVATAR_MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val bw = bmp.width
            val bh = bmp.height
            val scale = minOf(1f, AVATAR_MAX_SIDE.toFloat() / max(bw, bh))
            val scaled = if (scale < 1f) {
                val nw = (bw * scale).roundToInt().coerceAtLeast(1)
                val nh = (bh * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, nw, nh, true).also {
                    if (it !== bmp) bmp.recycle()
                }
            } else bmp
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, out)
                scaled.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "compressImage error", e)
            null
        }
    }

    private fun uploadAvatar(uri: Uri) {
        if (_binding == null) return
        val bytes = compressImage(uri) ?: run {
            context?.let { Toast.makeText(it, "خطا در خواندن تصویر", Toast.LENGTH_SHORT).show() }
            return
        }
        setAvatarUploading(true)
        scope.launch {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "avatar",
                        "avatar.jpg",
                        bytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .build()
                val req = Request.Builder()
                    .url("$BASE$PROFILE_AVATAR")
                    .post(body)
                    .addHeader("Cookie", cookieHeader())
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                val respBody = resp.body?.string().orEmpty()
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    setAvatarUploading(false)
                    if (!resp.isSuccessful) {
                        context?.let { Toast.makeText(it, "خطا در آپلود تصویر", Toast.LENGTH_SHORT).show() }
                        return@withContext
                    }
                    val ok = runCatching {
                        JSONObject(respBody).optBoolean("success", false)
                    }.getOrElse { false }
                    if (!ok) {
                        context?.let { Toast.makeText(it, "خطا در آپلود تصویر", Toast.LENGTH_SHORT).show() }
                        return@withContext
                    }
                    val newUrl = runCatching {
                        JSONObject(respBody).optString("avatar_url", "").takeIf { it.isNotEmpty() }
                    }.getOrNull()
                    currentAvatarUrl = newUrl
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        binding.profileEditAvatar.setImageBitmap(bmp)
                        binding.profileEditAvatar.visibility = View.VISIBLE
                        binding.profileEditInitials.visibility = View.GONE
                    } else if (!newUrl.isNullOrEmpty()) {
                        loadAvatar("$BASE$newUrl")
                    }
                    context?.let { Toast.makeText(it, "عکس پروفایل به‌روزرسانی شد.", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadAvatar error", e)
                withContext(Dispatchers.Main) {
                    setAvatarUploading(false)
                    context?.let { Toast.makeText(it, "خطا در ارتباط", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun save() {
        if (_binding == null) return
        val name = binding.profileEditName.text?.toString()?.trim() ?: ""
        val pos = binding.profileEditCity.selectedItemPosition
        val city = if (pos <= 0) "" else (binding.profileEditCity.adapter?.getItem(pos)?.toString()?.takeIf { it != "انتخاب شهر" } ?: "")
        val bio = (binding.profileEditBio.text?.toString() ?: "").trim().take(200)

        scope.launch {
            try {
                val json = JSONObject()
                    .put("name", name)
                    .put("city", city)
                    .put("bio", bio)
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("$BASE$PROFILE_UPDATE")
                    .post(body)
                    .addHeader("Cookie", cookieHeader())
                    .addHeader("Content-Type", "application/json")
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    if (resp.isSuccessful) {
                        val ok = runCatching {
                            JSONObject(resp.body?.string() ?: "{}").optBoolean("success", false)
                        }.getOrElse { false }
                        if (ok) {
                            context?.let { Toast.makeText(it, "مشخصات با موفقیت به‌روزرسانی شد.", Toast.LENGTH_SHORT).show() }
                            findNavController().navigateUp()
                        } else {
                            context?.let { Toast.makeText(it, "خطا در ذخیره", Toast.LENGTH_SHORT).show() }
                        }
                    } else {
                        context?.let { Toast.makeText(it, "خطا در ذخیره", Toast.LENGTH_SHORT).show() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "save error", e)
                withContext(Dispatchers.Main) {
                    context?.let { Toast.makeText(it, "خطا در ارتباط", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
}
