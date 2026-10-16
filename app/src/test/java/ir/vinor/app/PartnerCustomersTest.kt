package ir.vinor.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerCustomersTest {
    @Test
    fun loginRedirect_isNotACustomerPage() {
        val html = """
            <title>Redirecting...</title>
            <a href="/express/partner/login?next=/express/partner/customers">login</a>
        """.trimIndent()
        assertTrue(PartnerCustomers.isLoginDocument("https://vinor.ir/express/partner/login?next=/express/partner/customers", html))
        assertTrue(PartnerCustomers.parse(html).rows.isEmpty())
    }

    @Test
    fun cards_readNamePhoneNoteAndSkipShell() {
        val html = """
            <div id="vinorCustomersPage" class="vn-customer-page">
              <a class="is-active" href="/express/partner/customers?tab=active">فعال</a>
              <a href="/express/partner/customers?tab=archive">بایگانی</a>
              <article class="vn-customer-card" data-customer-id="12">
                <p class="vn-customer-name">علی رضایی</p>
                <a class="vn-customer-phone" href="tel:09121234567">۰۹۱۲۱۲۳۴۵۶۷</a>
                <p class="vn-customer-note">ویلای آبسرد</p>
                <span class="vn-customer-status">فعال</span>
              </article>
              <p class="vn-customer-empty" hidden>خالی</p>
            </div>
        """.trimIndent()
        val page = PartnerCustomers.parse(html)
        assertEquals(1, page.rows.size)
        assertEquals("12", page.rows[0].id)
        assertEquals("علی رضایی", page.rows[0].title)
        assertEquals("09121234567", page.rows[0].phone)
        assertEquals("ویلای آبسرد", page.rows[0].notes)
        assertEquals("فعال", page.rows[0].status)
        assertEquals(2, page.tabs.size)
        assertTrue(page.tabs[0].selected)
        assertEquals("/express/partner/customers?tab=archive", page.tabs[1].href)
    }

    @Test
    fun form_skipsCsrfAndBuildsBody() {
        val html = """
            <form action="/express/partner/customers">
              <input name="csrf_token" value="tok">
              <label for="fn">نام</label>
              <input id="fn" name="customer_first_name" required>
              <input name="customer_last_name" placeholder="نام خانوادگی">
              <input name="customer_phone" type="tel" required>
              <textarea name="notes" placeholder="یادداشت"></textarea>
              <button type="submit">ذخیره مشتری</button>
            </form>
            <script>
              fetch('/express/partner/customers', { body: JSON.stringify({ id: selected, action: 'delete' }) });
            </script>
            <button>حذف</button>
            <article class="vn-customer-card" data-customer-id="4">
              <p class="vn-customer-name">مینا</p>
            </article>
        """.trimIndent()
        val page = PartnerCustomers.parse(html)
        assertEquals("tok", PartnerCustomers.csrfFromHtml(html))
        assertEquals(4, page.fields.size)
        assertEquals("نام", page.fields[0].label)
        assertTrue(page.fields[0].required)
        assertTrue(page.fields[2].phone)
        assertEquals("ذخیره مشتری", page.submitLabel)
        assertEquals("افزودن مشتری", PartnerCustomers.addActionLabel(page.submitLabel))
        assertEquals("افزودن مشتری", PartnerCustomers.addActionLabel("ثبت مشتری"))
        assertEquals("افزودن مشتری", PartnerCustomers.addActionLabel(""))
        assertEquals(PartnerCrm.PATH, page.actionPath)
        assertTrue(page.canDelete)
        assertEquals("id", page.deleteIdKey)
        val body = PartnerCustomers.createBody(
            mapOf(
                "customer_first_name" to " علی ",
                "customer_last_name" to "رضایی",
                "customer_phone" to "+989121234567",
                "notes" to "",
            ),
            page.fields,
        )
        assertEquals("علی", body.getString("customer_first_name"))
        assertEquals("09121234567", body.getString("customer_phone"))
        assertFalse(body.has("notes"))
        assertFalse(body.has("csrf_token"))
        val delete = PartnerCustomers.deleteBody("4", "id")
        assertEquals(4L, delete.getLong("id"))
        assertEquals("delete", delete.getString("action"))
        assertEquals("نام را وارد کنید.", PartnerCustomers.fieldError(page.fields[0], " "))
        assertEquals("شماره موبایل را درست وارد کنید.", PartnerCustomers.fieldError(page.fields[2], "123"))
        assertNull(PartnerCustomers.fieldError(page.fields[3], ""))
    }

    @Test
    fun responseWithErrorOrFieldErrorsIsNotSuccessful() {
        assertFalse(
            PartnerCustomers.isOk(
                JSONObject().put("http_status", 200).put("error", "شماره موبایل تکراری است."),
            ),
        )
        assertFalse(
            PartnerCustomers.isOk(
                JSONObject()
                    .put("http_status", 200)
                    .put("fields", JSONObject().put("customer_phone", "موبایل نامعتبر است.")),
            ),
        )
        assertTrue(PartnerCustomers.isOk(JSONObject().put("http_status", 200).put("ok", true)))
    }

    @Test
    fun scriptAndJson_supplyFieldsWhenMarkupHasNoInputs() {
        val html = """
            <script type="application/json">
              {"customers":[{"id":3,"customer_first_name":"سارا","customer_last_name":"کریمی","customer_phone":"09120000000","notes":"شمال","status":"جدید"}]}
            </script>
            <script>
              fetch('/express/partner/customers', {
                body: JSON.stringify({ customer_first_name: a, customer_last_name: b, customer_phone: c, notes: d })
              });
            </script>
        """.trimIndent()
        val page = PartnerCustomers.parse(html)
        assertEquals(1, page.rows.size)
        assertEquals("سارا کریمی", page.rows[0].title)
        assertEquals("09120000000", page.rows[0].phone)
        assertEquals("شمال", page.rows[0].notes)
        assertEquals("3", page.rows[0].id)
        assertEquals(
            listOf("موبایل", "وضعیت", "یادداشت"),
            PartnerCustomers.detailFacts(page.rows[0]).map { it.label },
        )
        assertTrue(page.fields.any { it.name == "customer_phone" && it.phone })
        assertTrue(page.fields.any { it.name == "notes" && !it.required })
        assertEquals("مشتری‌ای ثبت نشده.", PartnerCustomers.parse("<div></div>").emptyText)
    }

    @Test
    fun extraFacts_stayWithTheCustomer() {
        val html = """
            <article class="vn-customer-card" data-customer-id="8">
              <p class="vn-customer-name">رضا</p>
              <a href="tel:09120001111">09120001111</a>
              <span class="vn-customer-city">تهران</span>
              <span class="vn-customer-budget">تا ۵ میلیارد</span>
              <dl><dt>محله</dt><dd>جردن</dd></dl>
            </article>
        """.trimIndent()
        val card = PartnerCustomers.parse(html).rows.single()
        assertEquals(listOf("محله", "شهر", "بودجه"), card.facts.map { it.label })
        assertEquals("جردن", card.facts.first { it.label == "محله" }.value)
        val json = """
            <script type="application/json">
              {"customers":[{"id":9,"name":"نرگس","customer_phone":"09123334444","city":"رشت","location":"گلسار","notes":"ویلا"}]}
            </script>
        """.trimIndent()
        val row = PartnerCustomers.parse(json).rows.single()
        assertEquals(listOf("شهر", "منطقه"), row.facts.map { it.label })
        assertEquals("گلسار", row.facts.first { it.label == "منطقه" }.value)
        assertTrue(PartnerCustomers.detailFacts(row).any { it.label == "یادداشت" && it.value == "ویلا" })
    }

    @Test
    fun searchInput_isNotACreateField() {
        val html = """
            <input name="q" type="search" placeholder="جستجوی مشتری">
            <input name="customer_phone" type="tel" required>
        """.trimIndent()
        val page = PartnerCustomers.parse(html)
        assertEquals("q", page.search?.name)
        assertEquals(1, page.fields.size)
        assertEquals("customer_phone", page.fields[0].name)
    }

    @Test
    fun response_prefersServerMessage() {
        assertTrue(PartnerCustomers.isCsrfExpired(JSONObject().put("error", "csrf_expired")))
        assertFalse(
            PartnerCustomers.isOk(JSONObject().put("http_status", 401).put("error", "unauthorized")),
        )
        assertEquals(
            "شماره تکراری است",
            PartnerCustomers.userMessage(
                JSONObject().put("message", "شماره تکراری است"),
                "ثبت ناموفق بود.",
            ),
        )
        assertEquals(
            "برای دیدن مشتریان باید وارد حساب شوید.",
            PartnerCustomers.userMessage(JSONObject().put("error", "unauthorized"), "x"),
        )
    }
}
