package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * منطق آنبوردینگ بازاریاب وینور (نقش API: consultant) روی همان مدل Express Partner.
 * وضعیت درخواست فقط از پاسخ api/consultant می‌آید؛ اینجا فروشگاه apply دوم نیست.
 */
object ConsultantOnboarding {
    const val ROLE = "consultant"
    const val UI_LABEL = "بازاریاب وینور"
    const val MIN_NAME = 2
    const val MAX_NAME = 40
    const val MAX_DOC_BYTES = 5L * 1024L * 1024L

    const val PATH_APPLY = "/api/consultant/apply"
    const val PATH_PROFILE = "/api/consultant/profile"
    const val PATH_DOCUMENTS = "/api/consultant/documents"
    const val PATH_STATUS = "/api/consultant/status"
    const val PATH_AVAILABILITY = "/api/consultant/availability"
    const val PATH_CITIES = "/api/consultant/cities"

    val LIVE_PATHS = listOf(
        PATH_APPLY,
        PATH_PROFILE,
        PATH_DOCUMENTS,
        PATH_STATUS,
        PATH_AVAILABILITY,
        PATH_CITIES,
    )

    enum class Screen { PROFILE, DOCUMENTS, STATUS, TIPS, DASHBOARD }

    data class City(val id: String, val label: String)
    data class Tip(val title: String, val body: String)
    data class FieldError(val field: String, val message: String)

    sealed class Draft {
        data class Ok(val json: JSONObject) : Draft()
        data class Invalid(val error: FieldError) : Draft()
    }

    val tips: List<Tip> = listOf(
        Tip(
            "مشتری را دقیق بپرس",
            "قبل از پیشنهاد فایل بپرس خریدار است یا فروشنده و حدود بودجه‌اش چیست.",
        ),
        Tip(
            "فقط تهران و حومه",
            "در این مرحله رهن و اجاره خاموش است؛ فقط خرید یا فروش در شهرهای همین فهرست.",
        ),
        Tip(
            "آمادگی دریافت لید",
            "داشبورد تا رسیدن لید خالی می‌ماند. هر وقت آماده بودی کلید «آماده‌ام لید بگیرم» را روشن کن.",
        ),
    )

    fun slidesSeen(apiFlag: Boolean, localFlag: Boolean): Boolean = apiFlag || localFlag

    fun isLiveConsultantPath(path: String): Boolean {
        val raw = path.trim()
        if (raw.contains("simulate", ignoreCase = true)) return false
        if (raw.contains("dry-run", ignoreCase = true) || raw.contains("dry_run", ignoreCase = true)) return false
        val bare = raw.substringBefore('?').substringBefore('#')
        return bare in LIVE_PATHS
    }

    /** شناسه شهر فقط از آرایهٔ cities در GET /cities. فهرست محلی نداریم. */
    fun parseCities(payload: JSONObject?): List<City> {
        val arr = payload?.optJSONArray("cities") ?: return emptyList()
        val out = ArrayList<City>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = row.optString("id").trim()
            val label = row.optString("label").trim()
            if (id.isEmpty() || label.isEmpty()) continue
            if (out.any { it.id == id }) continue
            out.add(City(id, label))
        }
        return out
    }

    /**
     * apply / profile / documents / availability به CSRF نیاز ندارند.
     * نشست همان کوکی vinor_session است.
     */
    fun requiresCsrf(path: String): Boolean {
        val bare = path.trim().substringBefore('?').substringBefore('#')
        return bare !in setOf(PATH_APPLY, PATH_PROFILE, PATH_DOCUMENTS, PATH_AVAILABILITY)
    }

    fun profileBody(
        nameRaw: String,
        cityId: String,
        specialties: Collection<String>,
        cities: List<City>,
    ): Draft {
        val name = nameRaw.trim()
        if (name.length < MIN_NAME || name.length > MAX_NAME) {
            return Draft.Invalid(FieldError("name", "نام باید بین ۲ تا ۴۰ حرف باشد."))
        }
        val city = cityId.trim()
        if (cities.none { it.id == city }) {
            return Draft.Invalid(FieldError("city", "شهر را از فهرست تهران و حومه انتخاب کنید."))
        }
        val picked = LinkedHashSet<String>()
        for (raw in specialties) {
            val value = raw.trim()
            if (value.isEmpty()) continue
            if (value !in setOf("buy", "sell")) {
                return Draft.Invalid(FieldError("specialties", "در این مرحله فقط خرید و فروش فعال است."))
            }
            picked.add(value)
        }
        if (picked.isEmpty()) {
            return Draft.Invalid(FieldError("specialties", "خرید یا فروش را انتخاب کنید."))
        }
        val ordered = listOf("buy", "sell").filter { it in picked }
        val json = JSONObject()
            .put("name", name)
            .put("city", city)
            .put("specialties", JSONArray(ordered))
        return Draft.Ok(json)
    }

    /**
     * @param sizeBytes اندازهٔ فایل؛ منفی یعنی نامشخص و بررسی حجم به سرور سپرده می‌شود.
     * @return پیام خطا، یا null اگر همین اسلات قابل ارسال است. مجوز خالی خطا نیست.
     */
    fun validateDocument(slot: String, fileName: String, mime: String, sizeBytes: Long): String? {
        if (slot != "national_id" && slot != "license") return "نوع مدرک نامعتبر است."
        val name = fileName.trim()
        if (name.isEmpty()) {
            return if (slot == "national_id") "کارت ملی الزامی است." else null
        }
        if (!acceptedType(name, mime)) return "فقط jpg، png یا pdf مجاز است."
        if (sizeBytes == 0L) return "فایل خالی است."
        if (sizeBytes > MAX_DOC_BYTES) return "حجم هر فایل حداکثر ۵ مگابایت است."
        return null
    }

    /**
     * next_step صفحه را انتخاب می‌کند.
     * status تا تأیید واقعی ادمین pending می‌ماند و به‌تنهایی صفحهٔ بررسی را باز نمی‌کند.
     * نکته‌ها فقط بعد از status=approved.
     */
    fun resolveScreen(nextStep: String?, status: String?, slidesSeen: Boolean): Screen {
        val step = nextStep?.trim().orEmpty()
        val st = status?.trim().orEmpty()
        val approved = st == "approved"
        return when (step) {
            "profile" -> Screen.PROFILE
            "documents" -> Screen.DOCUMENTS
            "status" -> Screen.STATUS
            "dashboard" -> if (approved && !slidesSeen) Screen.TIPS else Screen.DASHBOARD
            else -> when {
                st == "rejected" -> Screen.STATUS
                approved && !slidesSeen -> Screen.TIPS
                approved -> Screen.DASHBOARD
                else -> Screen.PROFILE
            }
        }
    }

    fun canSetAvailability(status: String?): Boolean = status?.trim() == "approved"

    fun userMessage(payload: JSONObject): String {
        val message = payload.optString("message").trim()
        if (message.isNotEmpty()) return message
        val fields = payload.optJSONObject("fields")
        if (fields != null) {
            val keys = fields.keys()
            while (keys.hasNext()) {
                val value = fields.optString(keys.next()).trim()
                if (value.isNotEmpty()) return value
            }
        }
        if (isUnauthorized(payload)) return "برای ادامه وارد شوید."
        return "مشکلی پیش آمد؛ دوباره تلاش کنید."
    }

    fun isUnauthorized(payload: JSONObject): Boolean {
        if (payload.optInt("http_status") == 401) return true
        return payload.optString("error") == "unauthorized"
    }

    fun rejectReason(payload: JSONObject): String {
        if (payload.optString("status") != "rejected") return ""
        return payload.optString("reject_reason").trim()
    }

    fun statusHeadline(status: String): String = when (status) {
        "approved" -> "حساب بازاریاب وینور تأیید شد."
        "rejected" -> "درخواست رد شد."
        "pending" -> "درخواست در حال بررسی است."
        "pending_profile" -> "مشخصات را کامل کنید."
        "pending_documents" -> "مدارک را بفرستید."
        else -> "وضعیت درخواست"
    }

    fun stepCaption(screen: Screen, tipIndex: Int = 0): String = when (screen) {
        Screen.PROFILE -> "مشخصات"
        Screen.DOCUMENTS -> "مدارک"
        Screen.STATUS -> "وضعیت"
        Screen.TIPS -> "راهنما ${tipIndex + 1} از ${tips.size}"
        Screen.DASHBOARD -> "داشبورد"
    }

    private fun acceptedType(fileName: String, mime: String): Boolean {
        val normalized = mime.lowercase(Locale.US).substringBefore(';').trim()
        if (normalized in setOf("image/jpeg", "image/jpg", "image/png", "application/pdf")) return true
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val mimeMissing = normalized.isEmpty() || normalized == "application/octet-stream"
        return mimeMissing && ext in setOf("jpg", "jpeg", "png", "pdf")
    }
}
