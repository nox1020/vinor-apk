package ir.vinor.app

object PartnerFormat {
    fun digits(n: Int): String = toman(n)

    fun toman(value: Int): String {
        if (value == 0) return "۰"
        val grouped = value.toString().reversed().chunked(3).joinToString("،").reversed()
        return grouped.map { c -> if (c in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[c - '0'] else c }.joinToString("")
    }
}
