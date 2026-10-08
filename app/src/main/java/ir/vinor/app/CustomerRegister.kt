package ir.vinor.app

import org.json.JSONObject

/** فرم مودال «ثبت مشتری جدید» روی خانه. */
object CustomerRegister {
    const val PATH = "/express/partner/home/register-customer"
    const val REFERER = "/express/partner/home"

    private val first = PartnerCustomers.Field("customer_first_name", "نام", false, false, true)
    private val last = PartnerCustomers.Field("customer_last_name", "نام خانوادگی", false, false, false)
    private val phone = PartnerCustomers.Field("customer_phone", "موبایل", false, true, true)
    private val notes = PartnerCustomers.Field("notes", "یادداشت", true, false, false)
    private val fields = listOf(first, last, phone, notes)

    fun error(firstName: String, lastName: String, phoneRaw: String, notesRaw: String): String? {
        return PartnerCustomers.fieldError(first, firstName)
            ?: PartnerCustomers.fieldError(last, lastName)
            ?: PartnerCustomers.fieldError(phone, phoneRaw)
            ?: PartnerCustomers.fieldError(notes, notesRaw)
    }

    fun body(firstName: String, lastName: String, phoneRaw: String, notesRaw: String): JSONObject {
        return PartnerCustomers.createBody(
            mapOf(
                first.name to firstName,
                last.name to lastName,
                phone.name to phoneRaw,
                notes.name to notesRaw,
            ),
            fields,
        )
    }
}
