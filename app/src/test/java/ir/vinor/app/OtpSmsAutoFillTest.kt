package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies the actual Persian/Latin OTP patterns used by sms.ir. */
class OtpSmsAutoFillTest {
    @Test
    fun extractsFiveDigitCodeFromSignedVinorMessage() {
        val text = "کد تایید:\n<#> 36423\n@vinor.ir #36423\n4R707/AKLt0"
        assertEquals("36423", OtpSmsAutoFill.extractOtp(text))
        assertEquals("ok", OtpSmsAutoFill.diagnoseSms(text))
    }

    @Test
    fun acceptsPersianAndArabicDigits() {
        assertEquals("36423", OtpSmsAutoFill.extractOtp("<#> ۳۶۴۲۳"))
        assertEquals("36423", OtpSmsAutoFill.extractOtp("@vinor.ir #٣٦٤٢٣"))
        assertEquals("36423", OtpSmsAutoFill.normalizeOtpDigits("۳٦4۲3"))
    }

    @Test
    fun ignoresUnrelatedMessagesWithoutAnOtpMarker() {
        assertNull(OtpSmsAutoFill.extractOtp("تراکنش شماره 36423 ثبت شد"))
        assertNull(OtpSmsAutoFill.extractOtp("<#> 123"))
    }
}
