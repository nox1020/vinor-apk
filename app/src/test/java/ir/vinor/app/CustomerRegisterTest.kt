package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CustomerRegisterTest {
    @Test
    fun rejectsBlankNameAndBadPhone() {
        assertEquals("نام را وارد کنید.", CustomerRegister.error(" ", "رضایی", "09121234567", ""))
        assertEquals(
            "شماره موبایل را درست وارد کنید.",
            CustomerRegister.error("علی", "", "123", "ویلا"),
        )
        assertEquals("موبایل را وارد کنید.", CustomerRegister.error("علی", "", "", ""))
        assertNull(CustomerRegister.error("علی", "", "۰۹۱۲۱۲۳۴۵۶۷", ""))
    }

    @Test
    fun bodyNormalizesPhoneAndDropsEmptyNotes() {
        val body = CustomerRegister.body(" علی ", "رضایی", "+989121234567", "  ")
        assertEquals("علی", body.getString("customer_first_name"))
        assertEquals("رضایی", body.getString("customer_last_name"))
        assertEquals("09121234567", body.getString("customer_phone"))
        assertFalse(body.has("notes"))
    }
}
