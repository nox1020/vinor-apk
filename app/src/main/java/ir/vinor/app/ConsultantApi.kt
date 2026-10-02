package ir.vinor.app

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject

/**
 * کلاینت زندهٔ api/consultant با کوکی vinor_session از [PartnerApi].
 * مسیر تولید شبیه‌سازی dry-run ندارد.
 */
object ConsultantApi {
    data class Part(val uri: Uri, val fileName: String, val mime: String)

    fun apply(context: Context): JSONObject =
        PartnerApi.postJson(ConsultantOnboarding.PATH_APPLY, JSONObject(), context)

    fun updateProfile(context: Context, body: JSONObject): JSONObject =
        PartnerApi.patchJson(ConsultantOnboarding.PATH_PROFILE, body, context)

    fun status(context: Context): JSONObject =
        PartnerApi.get(ConsultantOnboarding.PATH_STATUS, context)

    fun cities(context: Context): JSONObject =
        PartnerApi.get(ConsultantOnboarding.PATH_CITIES, context)

    fun availability(context: Context, online: Boolean): JSONObject =
        PartnerApi.postJson(
            ConsultantOnboarding.PATH_AVAILABILITY,
            JSONObject().put("online", online),
            context,
        )

    fun uploadDocuments(context: Context, nationalId: Part, license: Part?): JSONObject {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        form.addFormDataPart("national_id", nationalId.fileName, fileBody(context, nationalId))
        if (license != null) {
            form.addFormDataPart("license", license.fileName, fileBody(context, license))
        }
        return PartnerApi.postMultipart(ConsultantOnboarding.PATH_DOCUMENTS, form.build(), context)
    }

    private fun fileBody(context: Context, part: Part): RequestBody {
        val resolver = context.contentResolver
        val type = part.mime.ifBlank { resolver.getType(part.uri) ?: "application/octet-stream" }
        return object : RequestBody() {
            override fun contentType() = type.toMediaTypeOrNull()
            override fun contentLength(): Long {
                return resolver.openFileDescriptor(part.uri, "r")?.use { it.statSize } ?: -1L
            }
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(part.uri)?.use { input ->
                    sink.writeAll(input.source())
                } ?: error("خواندن فایل ناموفق بود.")
            }
        }
    }
}
