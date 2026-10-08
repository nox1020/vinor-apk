package ir.vinor.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import ir.vinor.app.databinding.FragmentMyPropertyFormBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MyPropertyFormFragment : Fragment() {

    private var _binding: FragmentMyPropertyFormBinding? = null
    private val binding get() = _binding!!
    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.Main + job)

    private val cities = mutableListOf("انتخاب شهر")
    private val categories = mutableListOf("انتخاب دسته")
    private var specSchema = JSONObject()
    private var catKind = JSONObject()
    private var sizeLabels = JSONObject()
    private val specViews = linkedMapOf<String, View>()
    private val specSelectValues = mutableMapOf<String, List<String>>()
    private val photos = mutableListOf<MediaSlot>()
    private var video: MediaSlot? = null
    private var doc1: MediaSlot? = null
    private var doc2: MediaSlot? = null
    private var submitting = false
    private var uploading = 0
    private var mapOpen = false
    private var mapConfirmed = false
    private var publicListing = false
    private var editCode = ""
    private var editSnapshot = ""
    private val paymentMethods = mutableListOf("انتخاب کنید")
    private val installmentRows = mutableListOf<View>()

    private val pickPhotos = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val room = (MAX_PHOTOS - photos.size).coerceAtLeast(0)
        uris.take(room).forEach { upload("image", it) }
    }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) upload("video", uri) }

    private val pickDoc1 = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) upload("document_1", uri) }

    private val pickDoc2 = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) upload("document_2", uri) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (job.isCancelled) {
            job = Job()
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        _binding = FragmentMyPropertyFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        publicListing = arguments?.getBoolean(ARG_PUBLIC) == true
        editSnapshot = arguments?.getString(ARG_SNAPSHOT).orEmpty()
        editCode = arguments?.getString(ARG_CODE).orEmpty().trim()
        if (editCode.isBlank() && editSnapshot.isNotBlank()) {
            editCode = runCatching { MyPropertyManage.codeOf(JSONObject(editSnapshot)) }.getOrDefault("").trim()
        }
        if (publicListing) {
            binding.mpFormHeader.pageTitle.text = "ثبت آگهی"
            binding.mpFormSubtitle.text = "اطلاعات ملک را وارد و منتشر کنید"
            binding.mpFormSubmit.text = "ثبت و انتشار آگهی"
        } else if (editCode.isNotBlank()) {
            binding.mpFormHeader.pageTitle.text = "ویرایش"
            binding.mpFormSubmit.text = "ذخیره"
        } else {
            binding.mpFormHeader.pageTitle.text = "ثبت ملک من"
        }
        binding.mpFormHeader.pageBack.setOnClickListener {
            if (!findNavController().navigateUp()) {
                (activity as? MainActivity)?.openPartnerHome()
            }
        }
        binding.mpFormDropzone.setOnClickListener {
            if (photos.size >= MAX_PHOTOS) {
                toast("حداکثر ۱۲ تصویر")
                return@setOnClickListener
            }
            pickPhotos.launch("image/*")
        }
        binding.mpFormVideoPick.setOnClickListener {
            if (video != null) {
                video = null
                bindVideo()
            } else pickVideo.launch("video/*")
        }
        binding.mpFormDoc1Pick.setOnClickListener {
            if (doc1 != null) {
                doc1 = null
                bindDoc(1)
            } else pickDoc1.launch("*/*")
        }
        binding.mpFormDoc2Pick.setOnClickListener {
            if (doc2 != null) {
                doc2 = null
                bindDoc(2)
            } else pickDoc2.launch("*/*")
        }
        binding.mpFormMapPick.setOnClickListener { toggleMap(!mapOpen) }
        binding.mpFormMapClear.setOnClickListener {
            binding.mpFormLat.setText("")
            binding.mpFormLng.setText("")
            mapConfirmed = false
            binding.mpFormMapHint.text = "برای نمایش دقیق‌تر روی نقشه، محل تقریبی را مشخص کنید"
            binding.mpFormMapAction.text = "انتخاب"
        }
        binding.mpFormMapConfirm.setOnClickListener { confirmMap() }
        binding.mpFormSubmit.setOnClickListener { submit() }
        binding.mpFormAddInstallment.setOnClickListener { addInstallmentRow() }
        binding.mpFormPayment.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val installments = selectedPayment() == "اقساطی"
                binding.mpFormInstallments.isVisible = installments
                if (installments && installmentRows.isEmpty()) addInstallmentRow()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        attachIntFormat(binding.mpFormSize)
        attachIntFormat(binding.mpFormPrice)
        loadForm()
    }

    override fun onDestroyView() {
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun loadForm() {
        val ctx = context ?: return
        if (!PartnerApi.hasSession()) {
            toast("برای ثبت ملک وارد شوید.")
            (activity as? MainActivity)?.openDrawer()
            return
        }
        binding.mpFormProgress.isVisible = true
        scope.launch {
            try {
                val path = formPath()
                val obj = withContext(Dispatchers.IO) { PartnerApi.get(path, ctx) }
                if (_binding == null) return@launch
                binding.mpFormProgress.isVisible = false
                if (obj.optInt("http_status") == 401) {
                    toast("برای ثبت ملک وارد شوید.")
                    (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                bindMeta(obj)
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.mpFormProgress.isVisible = false
                showError(e.message ?: "خطای شبکه")
            }
        }
    }

    private fun bindMeta(obj: JSONObject) {
        val title = obj.optString("page_title").ifBlank { "ثبت ملک من" }
        binding.mpFormHeader.pageTitle.text = title
        val sub = obj.optString("page_subtitle")
        if (sub.isNotBlank()) binding.mpFormSubtitle.text = sub
        val submit = obj.optString("submit_label")
        if (submit.isNotBlank()) binding.mpFormSubmit.text = submit
        publicListing = obj.optBoolean("public_listing", publicListing)
        val hideCommission = obj.optBoolean("hide_commission", !publicListing)
        val hidePayment = obj.optBoolean("hide_payment", !publicListing)
        val hideBroadcast = obj.optBoolean("hide_broadcast", !publicListing)
        binding.mpFormSpecialBlock.isVisible = !hideCommission || !hideBroadcast
        binding.mpFormCommissionRow.isVisible = !hideCommission
        binding.mpFormBroadcast.isVisible = !hideBroadcast
        binding.mpFormBroadcastDivider.isVisible = !hideCommission && !hideBroadcast
        binding.mpFormPaymentBlock.isVisible = !hidePayment
        if (!hideCommission) {
            val d = obj.optString("default_commission_pct").ifBlank { "2" }
            if (binding.mpFormCommission.text.isNullOrBlank()) binding.mpFormCommission.setText(d)
        }
        paymentMethods.clear()
        paymentMethods.add("انتخاب کنید")
        val pmArr = obj.optJSONArray("payment_methods") ?: JSONArray()
        if (pmArr.length() == 0) {
            paymentMethods.addAll(listOf("نقدی", "اقساطی", "معاوضه", "توافقی"))
        } else {
            for (i in 0 until pmArr.length()) {
                val m = pmArr.optString(i).trim()
                if (m.isNotBlank()) paymentMethods.add(m)
            }
        }
        binding.mpFormPayment.adapter = spinnerAdapter(paymentMethods)
        specSchema = obj.optJSONObject("spec_schema") ?: JSONObject()
        catKind = obj.optJSONObject("cat_kind") ?: JSONObject()
        sizeLabels = obj.optJSONObject("size_labels") ?: JSONObject()

        cities.clear()
        cities.add("انتخاب شهر")
        val cityArr = obj.optJSONArray("cities") ?: JSONArray()
        for (i in 0 until cityArr.length()) {
            val c = cityArr.optString(i).trim()
            if (c.isNotBlank()) cities.add(c)
        }
        binding.mpFormCity.adapter = spinnerAdapter(cities)

        categories.clear()
        categories.add("انتخاب دسته")
        val catArr = obj.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until catArr.length()) {
            val c = catArr.optString(i).trim()
            if (c.isNotBlank()) categories.add(c)
        }
        binding.mpFormCategory.adapter = spinnerAdapter(categories)
        binding.mpFormCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                rebuildSpecs()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        rebuildSpecs()
        if (editCode.isNotBlank()) {
            binding.mpFormHeader.pageTitle.text = "ویرایش"
            binding.mpFormSubmit.text = "ذخیره"
            applySnapshot()
        }
    }

    private fun formPath(): String {
        val query = mutableListOf<String>()
        if (publicListing) query.add("mode=listing")
        if (editCode.isNotBlank()) query.add("code=${Uri.encode(editCode)}")
        return if (query.isEmpty()) FORM_PATH else "$FORM_PATH?" + query.joinToString("&")
    }

    private fun applySnapshot() {
        val obj = runCatching { JSONObject(editSnapshot) }.getOrNull() ?: return
        val b = _binding ?: return
        fun fill(current: CharSequence?, value: String, set: (String) -> Unit) {
            if (!current.isNullOrBlank() || value.isBlank()) return
            set(value)
        }
        fill(b.mpFormTitle.text, textOf(obj, "title")) { b.mpFormTitle.setText(it) }
        fill(b.mpFormLocation.text, textOf(obj, "location")) { b.mpFormLocation.setText(it) }
        fill(b.mpFormDesc.text, textOf(obj, "description")) { b.mpFormDesc.setText(it) }
        selectNamed(b.mpFormCity, cities, textOf(obj, "city"))
        selectNamed(b.mpFormCategory, categories, textOf(obj, "category"))
        val size = digitsOf(textOf(obj, "size"))
        fill(b.mpFormSize.text, size) { b.mpFormSize.setText(it) }
        val price = digitsOf(textOf(obj, "price_total")).ifBlank { digitsOf(textOf(obj, "price_toman")) }
        fill(b.mpFormPrice.text, price) { b.mpFormPrice.setText(it) }
        val pay = MyPropertyManage.paymentOf(obj)
        if (pay.isNotBlank()) selectNamed(b.mpFormPayment, paymentMethods, pay)
        val commission = MyPropertyManage.commissionOf(obj)
        if (commission.isNotBlank() && b.mpFormCommission.text.isNullOrBlank()) {
            b.mpFormCommission.setText(commission)
        }
    }

    private fun selectNamed(spinner: Spinner, items: MutableList<String>, value: String) {
        val name = value.trim()
        if (name.isBlank()) return
        var index = items.indexOfFirst { it == name }
        if (index < 0) {
            items.add(name)
            spinner.adapter = spinnerAdapter(items)
            index = items.lastIndex
        }
        if (spinner.selectedItemPosition != index) spinner.setSelection(index)
    }

    private fun textOf(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val raw = obj.opt(key) ?: return ""
        return when (raw) {
            is Number -> {
                val asLong = raw.toLong()
                if (asLong.toDouble() == raw.toDouble()) asLong.toString() else raw.toString()
            }
            else -> obj.optString(key).trim()
        }
    }

    private fun digitsOf(raw: String): String = PartnerApi.asciiDigits(raw)

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(requireContext(), R.layout.item_spinner_dark, items).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown_dark)
        }
    }

    private fun selectedCategory(): String {
        val pos = binding.mpFormCategory.selectedItemPosition
        return if (pos <= 0) "" else categories.getOrNull(pos).orEmpty()
    }

    private fun selectedCity(): String {
        val pos = binding.mpFormCity.selectedItemPosition
        return if (pos <= 0) "" else cities.getOrNull(pos).orEmpty()
    }

    private fun selectedPayment(): String {
        val pos = binding.mpFormPayment.selectedItemPosition
        return if (pos <= 0) "" else paymentMethods.getOrNull(pos).orEmpty()
    }

    private fun addInstallmentRow() {
        val b = _binding ?: return
        val row = layoutInflater.inflate(R.layout.item_mp_installment, b.mpFormInstallmentRows, false)
        val amount = row.findViewById<EditText>(R.id.mpInstAmount)
        attachIntFormat(amount)
        row.findViewById<View>(R.id.mpInstRemove).setOnClickListener {
            b.mpFormInstallmentRows.removeView(row)
            installmentRows.remove(row)
            if (installmentRows.isEmpty() && selectedPayment() == "اقساطی") addInstallmentRow()
        }
        b.mpFormInstallmentRows.addView(row)
        installmentRows.add(row)
    }

    private fun collectInstallments(): Pair<JSONArray, JSONArray>? {
        val months = JSONArray()
        val amounts = JSONArray()
        var valid = 0
        installmentRows.forEach { row ->
            val m = longDigits(row.findViewById<EditText>(R.id.mpInstMonths).text?.toString().orEmpty())
            val a = longDigits(row.findViewById<EditText>(R.id.mpInstAmount).text?.toString().orEmpty())
            if (m > 0L && a > 0L) {
                months.put(m.toString())
                amounts.put(a.toString())
                valid += 1
            }
        }
        return if (valid > 0) months to amounts else null
    }

    private fun kindFor(category: String): String = catKind.optString(category)

    private fun rebuildSpecs() {
        val b = _binding ?: return
        val kind = kindFor(selectedCategory())
        val sizeLabel = sizeLabels.optString(kind.ifBlank { "other" }).ifBlank { "متراژ" }
        b.mpFormSizeLabel.text = if (sizeLabel.contains("*")) sizeLabel else "$sizeLabel *"
        specViews.clear()
        specSelectValues.clear()
        b.mpFormSpecs.removeAllViews()
        val fields = specSchema.optJSONArray(kind)
        if (kind.isBlank() || fields == null || fields.length() == 0) {
            b.mpFormSpecs.isVisible = false
            return
        }
        b.mpFormSpecs.isVisible = true
        val title = TextView(requireContext())
        title.text = "مشخصات اختصاصی"
        title.setTextColor(0xFFF3F4F6.toInt())
        title.textSize = 13f
        title.setPadding(0, dp(8), 0, dp(8))
        b.mpFormSpecs.addView(title)
        for (i in 0 until fields.length()) {
            val field = fields.optJSONObject(i) ?: continue
            addSpecField(field)
        }
    }

    private fun addSpecField(field: JSONObject) {
        val key = field.optString("key")
        val label = field.optString("label")
        val type = field.optString("type")
        if (key.isBlank()) return
        val ctx = requireContext()
        when (type) {
            "checkbox" -> {
                val box = CheckBox(ctx)
                box.text = label
                box.setTextColor(0xFFECECEC.toInt())
                box.textSize = 13f
                box.setPadding(0, dp(6), 0, dp(6))
                binding.mpFormSpecs.addView(box)
                specViews[key] = box
            }
            "select" -> {
                addSpecLabel(label)
                val spinner = Spinner(ctx)
                spinner.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44),
                )
                spinner.background = null
                spinner.setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF1C1C1E.toInt()))
                val labels = mutableListOf("—")
                val values = mutableListOf("")
                val opts = field.optJSONArray("options") ?: JSONArray()
                for (i in 0 until opts.length()) {
                    val opt = opts.optJSONObject(i) ?: continue
                    values.add(opt.optString("value"))
                    labels.add(opt.optString("label").ifBlank { opt.optString("value") })
                }
                spinner.adapter = spinnerAdapter(labels)
                specSelectValues[key] = values
                binding.mpFormSpecs.addView(spinner)
                specViews[key] = spinner
            }
            else -> {
                addSpecLabel(label)
                val et = EditText(ctx)
                et.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                et.setBackgroundResource(R.drawable.bg_drawer_input)
                et.setPadding(dp(10), dp(10), dp(10), dp(10))
                et.setTextColor(0xFFF3F4F6.toInt())
                et.setHintTextColor(0xFF6B7280.toInt())
                et.textSize = 14f
                et.hint = field.optString("placeholder")
                binding.mpFormSpecs.addView(et)
                specViews[key] = et
            }
        }
    }

    private fun addSpecLabel(label: String) {
        val tv = TextView(requireContext())
        tv.text = label
        tv.setTextColor(0xFF9CA3AF.toInt())
        tv.textSize = 11f
        tv.setPadding(0, dp(8), 0, dp(4))
        binding.mpFormSpecs.addView(tv)
    }

    private fun collectSpecs(body: JSONObject) {
        specViews.forEach { (key, view) ->
            when (view) {
                is CheckBox -> if (view.isChecked) body.put(key, "1")
                is Spinner -> {
                    val values = specSelectValues[key].orEmpty()
                    val v = values.getOrNull(view.selectedItemPosition).orEmpty()
                    if (v.isNotBlank()) body.put(key, v)
                }
                is EditText -> {
                    val v = view.text?.toString()?.trim().orEmpty()
                    if (v.isNotBlank()) body.put(key, v)
                }
            }
        }
    }

    private fun upload(kind: String, uri: Uri) {
        val ctx = context ?: return
        val name = uriName(uri)
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        when (kind) {
            "video" -> if (!isVideo(mime, name)) {
                toast("فرمت ویدئو مجاز نیست.")
                return
            }
            "document_1", "document_2" -> if (!isDoc(mime, name)) {
                toast("فرمت مدرک مجاز نیست (PDF · JPG · PNG).")
                return
            }
        }
        val path = when (kind) {
            "video" -> UPLOAD_VIDEO
            "document_1", "document_2" -> UPLOAD_DOC
            else -> UPLOAD_IMAGE
        }
        val apiKind = when (kind) {
            "video" -> "video"
            "document_1", "document_2" -> "document"
            else -> "image"
        }
        uploading += 1
        syncBusy()
        scope.launch {
            try {
                val obj = withContext(Dispatchers.IO) {
                    PartnerApi.postUpload(path, uri, apiKind, name, mime, ctx)
                }
                if (_binding == null) return@launch
                if (!obj.optBoolean("ok", false)) {
                    toast(obj.optString("error").ifBlank { "آپلود ناموفق بود." })
                    return@launch
                }
                val stored = obj.optString("path")
                val url = obj.optString("url").ifBlank { stored }
                val slot = MediaSlot(stored, url, obj.optString("name").ifBlank { name })
                when (kind) {
                    "video" -> {
                        video = slot
                        bindVideo()
                    }
                    "document_1" -> {
                        doc1 = slot
                        bindDoc(1)
                    }
                    "document_2" -> {
                        doc2 = slot
                        bindDoc(2)
                    }
                    else -> {
                        if (photos.size < MAX_PHOTOS) {
                            photos.add(slot)
                            bindPhotos()
                        }
                    }
                }
            } catch (e: Exception) {
                toast(e.message ?: "آپلود ناموفق بود.")
            } finally {
                uploading = (uploading - 1).coerceAtLeast(0)
                if (_binding != null) syncBusy()
            }
        }
    }

    private fun bindPhotos() {
        val row = binding.mpFormPhotos
        row.removeAllViews()
        binding.mpFormPhotosScroll.isVisible = photos.isNotEmpty()
        binding.mpFormPhotoHint.text = "حداکثر ۱۲ تصویر · بلافاصله آپلود می‌شود" +
            if (photos.isNotEmpty()) " · ${fa(photos.size)} از ۱۲" else ""
        photos.forEach { slot ->
            val item = layoutInflater.inflate(R.layout.item_mp_photo, row, false)
            val thumb = item.findViewById<ImageView>(R.id.mpPhotoThumb)
            PartnerImages.roundClip(thumb, 8f)
            PartnerImages.bind(scope, thumb, slot.url)
            item.findViewById<View>(R.id.mpPhotoRemove).setOnClickListener {
                photos.removeAll { it.path == slot.path }
                bindPhotos()
            }
            row.addView(item)
        }
    }

    private fun bindVideo() {
        val slot = video
        if (slot == null) {
            binding.mpFormVideoHint.text = "MP4 · WebM · MOV"
            binding.mpFormVideoAction.text = "انتخاب"
        } else {
            binding.mpFormVideoHint.text = slot.name
            binding.mpFormVideoAction.text = "حذف"
        }
    }

    private fun bindDoc(which: Int) {
        val slot = if (which == 1) doc1 else doc2
        val hint = if (which == 1) binding.mpFormDoc1Hint else binding.mpFormDoc2Hint
        val action = if (which == 1) binding.mpFormDoc1Action else binding.mpFormDoc2Action
        if (slot == null) {
            hint.text = "PDF · JPG · PNG"
            action.text = "انتخاب"
        } else {
            hint.text = slot.name
            action.text = "حذف"
        }
    }

    private fun toggleMap(open: Boolean) {
        mapOpen = open
        binding.mpFormMapWrap.isVisible = open
        binding.mpFormMapAction.text = if (open) "بستن" else if (mapConfirmed) "ویرایش" else "انتخاب"
    }

    private fun confirmMap() {
        val lat = parseCoord(binding.mpFormLat.text?.toString().orEmpty())
        val lng = parseCoord(binding.mpFormLng.text?.toString().orEmpty())
        if (lat == null || lng == null) {
            toast("عرض و طول جغرافیایی را وارد کنید.")
            return
        }
        mapConfirmed = true
        binding.mpFormMapHint.text = "موقعیت ثبت شد"
        binding.mpFormMapAction.text = "ویرایش"
        toggleMap(false)
    }

    private fun submit() {
        if (submitting || uploading > 0) return
        val title = binding.mpFormTitle.text?.toString()?.trim().orEmpty()
        val city = selectedCity()
        val location = binding.mpFormLocation.text?.toString()?.trim().orEmpty()
        val size = longDigits(binding.mpFormSize.text?.toString().orEmpty())
        val price = longDigits(binding.mpFormPrice.text?.toString().orEmpty())
        val snap = runCatching { JSONObject(editSnapshot) }.getOrNull()
        val payment = MyPropertyManage.editPayment(
            snap,
            if (publicListing) selectedPayment() else "",
            publicListing,
        )
        val typedCommission = if (publicListing) commissionPct()?.toString().orEmpty() else ""
        val commission = MyPropertyManage.editCommission(snap, typedCommission, publicListing)
        when {
            title.isBlank() || city.isBlank() || location.isBlank() || size <= 0L || price <= 0L -> {
                showError("لطفاً فیلدهای اجباری را پر کنید.")
                return
            }
            title.length < 10 -> {
                showError("عنوان باید حداقل ۱۰ کاراکتر باشد.")
                return
            }
            publicListing && payment.isBlank() -> {
                showError("نحوه پرداخت را انتخاب کنید.")
                return
            }
            publicListing && payment == "اقساطی" && collectInstallments() == null -> {
                showError("برای پرداخت اقساطی حداقل یک قسط معتبر وارد کنید.")
                return
            }
            publicListing && commission.isBlank() -> {
                showError("درصد پورسانت الزامی است.")
                return
            }
        }
        val ppm = if (size > 0L) price / size else 0L
        val body = JSONObject()
            .put("title", title)
            .put("city", city)
            .put("location", location)
            .put("size", size.toString())
            .put("price_total", price.toString())
            .put("price_per_meter", ppm.toString())
            .put("category", selectedCategory())
            .put("description", binding.mpFormDesc.text?.toString()?.trim().orEmpty())
            .put("express_commission_pct", commission)
            .put("payment_method", payment)
        if (publicListing) {
            if (binding.mpFormBroadcast.isChecked) body.put("auto_send_all_partners", "1")
            if (payment == "اقساطی") {
                collectInstallments()?.let { (months, amounts) ->
                    body.put("installment_months[]", months)
                    body.put("installment_amounts[]", amounts)
                }
            }
        }
        val images = JSONArray()
        photos.forEach { images.put(it.path) }
        if (editCode.isBlank() || photos.isNotEmpty()) body.put("images", images)
        if (editCode.isNotBlank()) body.put("code", editCode)
        video?.let { body.put("video", it.path) }
        doc1?.let { body.put("document_1", it.path) }
        doc2?.let { body.put("document_2", it.path) }
        collectSpecs(body)
        val lat = parseCoord(binding.mpFormLat.text?.toString().orEmpty())
        val lng = parseCoord(binding.mpFormLng.text?.toString().orEmpty())
        if (mapConfirmed && lat != null) body.put("latitude", lat.toString())
        if (mapConfirmed && lng != null) body.put("longitude", lng.toString())

        submitting = true
        syncBusy()
        binding.mpFormError.isVisible = false
        val ctx = context ?: return
        scope.launch {
            try {
                val obj = withContext(Dispatchers.IO) {
                    PartnerApi.postJson(
                        if (publicListing) SUBMIT_LISTING else SUBMIT_PATH,
                        body,
                        ctx,
                    )
                }
                if (_binding == null) return@launch
                if (obj.optInt("http_status") == 401) {
                    toast("برای ثبت ملک وارد شوید.")
                    (activity as? MainActivity)?.openDrawer()
                    return@launch
                }
                if (obj.optBoolean("ok", false)) {
                    val saved = if (editCode.isNotBlank()) "تغییرات ذخیره شد." else "ملک شما ثبت شد."
                    toast(obj.optString("message").ifBlank { saved })
                    (activity as? MainActivity)?.openMyProperty()
                } else {
                    showError(obj.optString("error").ifBlank {
                        if (publicListing) "ثبت آگهی ناموفق بود." else "ثبت ملک ناموفق بود."
                    })
                }
            } catch (e: Exception) {
                if (_binding != null) showError(e.message ?: "خطای شبکه")
            } finally {
                submitting = false
                if (_binding != null) syncBusy()
            }
        }
    }

    private fun syncBusy() {
        val busy = submitting || uploading > 0
        binding.mpFormSubmit.isEnabled = !busy
        binding.mpFormSubmit.alpha = if (busy) 0.6f else 1f
        binding.mpFormProgress.isVisible = busy
    }

    private fun attachIntFormat(et: EditText) {
        et.addTextChangedListener(object : TextWatcher {
            private var editing = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editing) return
                editing = true
                val grouped = groupedFa(s?.toString().orEmpty())
                if (grouped != s.toString()) {
                    et.setText(grouped)
                    et.setSelection(grouped.length)
                }
                editing = false
                updatePpm()
            }
        })
    }

    private fun updatePpm() {
        val b = _binding ?: return
        val size = longDigits(b.mpFormSize.text?.toString().orEmpty())
        val price = longDigits(b.mpFormPrice.text?.toString().orEmpty())
        b.mpFormPpm.setText(if (size > 0L && price > 0L) groupedFa((price / size).toString()) else "")
    }

    private fun groupedFa(raw: String): String {
        val digits = PartnerApi.asciiDigits(raw)
        if (digits.isBlank()) return ""
        val n = digits.toLongOrNull() ?: return ""
        return PartnerFormat.toman(n)
    }

    private fun commissionPct(): Double? {
        val n = parseCoord(binding.mpFormCommission.text?.toString().orEmpty()) ?: return null
        if (n <= 0.0 || n > 100.0) return null
        return n
    }

    private fun longDigits(raw: String): Long = PartnerApi.asciiDigits(raw).toLongOrNull() ?: 0L

    private fun parseCoord(raw: String): Double? {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        val ar = "٠١٢٣٤٥٦٧٨٩"
        val sb = StringBuilder()
        var sawDot = false
        for (ch in raw.trim()) {
            when {
                ch == '٫' || ch == ',' || ch == '.' -> if (!sawDot) {
                    sb.append('.')
                    sawDot = true
                }
                ch == '-' && sb.isEmpty() -> sb.append('-')
                ch in '0'..'9' -> sb.append(ch)
                fa.indexOf(ch) >= 0 -> sb.append(fa.indexOf(ch))
                ar.indexOf(ch) >= 0 -> sb.append(ar.indexOf(ch))
            }
        }
        return sb.toString().toDoubleOrNull()
    }

    private fun uriName(uri: Uri): String {
        val cr = requireContext().contentResolver
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0).orEmpty().ifBlank { "file" }
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun isVideo(mime: String, name: String): Boolean {
        val m = mime.lowercase()
        val n = name.lowercase()
        return m.startsWith("video/") || n.endsWith(".mp4") || n.endsWith(".webm") ||
            n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".mkv")
    }

    private fun isDoc(mime: String, name: String): Boolean {
        val m = mime.lowercase()
        val n = name.lowercase()
        return m.contains("pdf") || m.startsWith("image/") ||
            n.endsWith(".pdf") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
    }

    private fun showError(msg: String) {
        binding.mpFormError.text = msg
        binding.mpFormError.isVisible = true
    }

    private fun toast(msg: String) {
        context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun fa(n: Int): String = PartnerFormat.toman(n.toLong())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private data class MediaSlot(val path: String, val url: String, val name: String)

    companion object {
        const val ARG_PUBLIC = "publicListing"
        const val ARG_CODE = "editCode"
        const val ARG_SNAPSHOT = "snapshot"
        private const val MAX_PHOTOS = 12
        private const val FORM_PATH = "/express/partner/api/my-property/form"
        private const val SUBMIT_PATH = "/express/partner/api/my-property/submit"
        private const val SUBMIT_LISTING = "/express/partner/api/listings/submit"
        private const val UPLOAD_IMAGE = "/express/partner/api/my-property/upload-image"
        private const val UPLOAD_VIDEO = "/express/partner/api/my-property/upload-video"
        private const val UPLOAD_DOC = "/express/partner/api/my-property/upload-document"
    }
}
