package ir.vinor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddHubActionsTest {

    @Test
    fun rows_keepOrderAndExistingPathsOnly() {
        val rows = AddHubActions.rows()
        assertEquals(4, rows.size)
        assertEquals("ثبت آگهی", rows[0].title)
        assertEquals("/express/partner/my-property?register=1", rows[0].path)
        assertEquals("افزودن مشتری", rows[1].title)
        assertEquals(AddHubActions.CUSTOMER_MODAL, rows[1].path)
        assertTrue(AddHubActions.isCustomerModal(rows[1].path))
        assertEquals("سپردن ملک", rows[2].title)
        assertNull(rows[2].path)
        assertEquals("استعلام قیمت ملک", rows[3].title)
        assertNull(rows[3].path)
    }

    @Test
    fun missingPaths_listsUnwiredActions() {
        assertEquals(
            listOf("سپردن ملک", "استعلام قیمت ملک"),
            AddHubActions.missingPaths(),
        )
        assertEquals("افزودن", AddHubActions.FOOTER_LABEL)
        assertTrue(AddHubActions.PAGE_PATH.endsWith("/add"))
    }
}
