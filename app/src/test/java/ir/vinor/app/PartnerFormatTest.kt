package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PartnerFormatTest {
    @Test
    fun toman_groupsAndPersianDigits() {
        assertEquals("۰", PartnerFormat.toman(0))
        assertEquals("۱٬۲۰۰", PartnerFormat.toman(1200).replace("،", "٬"))
        assertEquals("۱،۲۰۰", PartnerFormat.toman(1200))
    }
}
