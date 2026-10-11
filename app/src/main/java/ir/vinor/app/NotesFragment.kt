package ir.vinor.app

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentNotesBinding
import ir.vinor.app.databinding.ItemNoteBinding
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * صفحه یادداشت‌ها - نیتیو، معادل /express/partner/notes
 * از API های:
 * - GET /express/partner/api/notes
 * - POST /express/partner/api/notes
 * - POST /express/partner/api/notes/<id>/delete
 * استفاده می‌کند.
 */
class NotesFragment : Fragment() {

    private var _binding: FragmentNotesBinding? = null
    private val binding get() = _binding!!

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "NotesFragment"
        private const val BASE = "https://vinor.ir"
        private const val API_LIST = "/express/partner/api/notes"
        private const val API_ADD = "/express/partner/api/notes"
        private const val API_DELETE = "/express/partner/api/notes/%d/delete"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.notesBack.setOnClickListener { findNavController().navigateUp() }
        binding.notesAdd.setOnClickListener { addNote() }
        loadNotes()
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun cookieHeader(): String = CookieManager.getInstance().getCookie(BASE) ?: ""

    private fun formatDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val raw = iso.trim().replace("Z", "")
        val outFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa"))
        val parsers = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        )
        for (p in parsers) {
            try {
                val d = p.parse(raw.take(19).trim())
                if (d != null) return outFmt.format(d)
            } catch (_: Exception) {
            }
        }
        return raw.take(16).replace("T", " ")
    }

    private fun loadNotes() {
        val ctx = context ?: return
        if (_binding == null) return
        binding.notesProgress.visibility = View.VISIBLE
        binding.notesEmpty.visibility = View.GONE

        scope.launch {
            try {
                val obj = PartnerApi.loadDisplay(API_LIST, ctx) { data ->
                    if (_binding == null) return@loadDisplay
                    if (!data.optBoolean("success", false) && data.optJSONArray("items") == null) return@loadDisplay
                    binding.notesProgress.visibility = View.GONE
                    val arr = data.optJSONArray("items") ?: org.json.JSONArray()
                    val items = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { items.add(it) }
                    }
                    bind(items)
                }
                if (_binding == null) return@launch
                if (PartnerApi.isSessionExpired(obj)) {
                    (activity as? MainActivity)?.openLogin(sessionExpired = true)
                    return@launch
                }
                if (!obj.optBoolean("success", false) && obj.optJSONArray("items") == null) {
                    showError()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadNotes error", e)
                if (_binding != null) showError()
            }
        }
    }

    private fun showError() {
        if (_binding == null) return
        binding.notesProgress.visibility = View.GONE
        binding.notesList.removeAllViews()
        binding.notesEmpty.visibility = View.VISIBLE
        context?.let {
            Toast.makeText(it, "خطا در بارگذاری یادداشت‌ها", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bind(items: List<JSONObject>) {
        if (_binding == null) return
        binding.notesList.removeAllViews()

        if (items.isEmpty()) {
            binding.notesEmpty.visibility = View.VISIBLE
            return
        }
        binding.notesEmpty.visibility = View.GONE

        for (i in items.indices) {
            val n = items[i]
            val itemBinding = ItemNoteBinding.inflate(layoutInflater, binding.notesList, false)
            val content = n.optString("content", "")
            val createdAt = n.optString("created_at", "")
            val id = n.optInt("id", 0)

            itemBinding.noteContent.text = content
            itemBinding.noteDate.text = formatDate(createdAt)

            itemBinding.noteDelete.setOnClickListener {
                if (id > 0) {
                    deleteNote(id)
                }
            }

            binding.notesList.addView(itemBinding.root)
        }
    }

    private fun addNote() {
        if (_binding == null) return
        val text = binding.notesInput.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            context?.let {
                Toast.makeText(it, "متن یادداشت خالی است.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        binding.notesAdd.isEnabled = false
        scope.launch {
            try {
                val json = JSONObject().put("content", text)
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("$BASE$API_ADD")
                    .post(body)
                    .addHeader("Cookie", cookieHeader())
                    .addHeader("Content-Type", "application/json")
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                withContext(Dispatchers.Main) {
                    binding.notesAdd.isEnabled = true
                    if (!resp.isSuccessful) {
                        context?.let {
                            Toast.makeText(it, "خطا در ذخیره یادداشت", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        binding.notesInput.setText("")
                        loadNotes()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "addNote error", e)
                withContext(Dispatchers.Main) {
                    binding.notesAdd.isEnabled = true
                    context?.let {
                        Toast.makeText(it, "خطا در ارتباط", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun deleteNote(id: Int) {
        if (_binding == null) return
        scope.launch {
            try {
                val url = "$BASE" + API_DELETE.format(id)
                val req = Request.Builder()
                    .url(url)
                    .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .addHeader("Cookie", cookieHeader())
                    .addHeader("Content-Type", "application/json")
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                withContext(Dispatchers.Main) {
                    if (!resp.isSuccessful) {
                        context?.let {
                            Toast.makeText(it, "خطا در حذف یادداشت", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        loadNotes()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteNote error", e)
                withContext(Dispatchers.Main) {
                    context?.let {
                        Toast.makeText(it, "خطا در ارتباط", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

