package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletWithdrawTest {
    @Test
    fun sheba_acceptsGroupedPersianAndRejectsBadChecksum() {
        assertNull(WalletWithdraw.shebaError("IR82-0540-1026-8002-0817-9090-02"))
        assertNull(WalletWithdraw.shebaError("۸۲۰۵۴۰۱۰۲۶۸۰۰۲۰۸۱۷۹۰۹۰۰۲"))
        assertEquals("شماره شبا باید با IR و ۲۴ رقم باشد.", WalletWithdraw.shebaError("IR123"))
        assertEquals("شماره شبا معتبر نیست.", WalletWithdraw.shebaError("IR580540102680020817909002"))
        assertEquals("IR82 0540 1026 8002 0817 9090 02", WalletWithdraw.formatSheba("ir820540102680020817909002"))
    }

    @Test
    fun amount_capsAtKnownBalance() {
        assertEquals("مبلغ را وارد کنید.", WalletWithdraw.amountError("", 1000))
        assertEquals("مبلغ باید بیشتر از صفر باشد.", WalletWithdraw.amountError("۰", 1000))
        assertEquals("موجودی قابل برداشت ندارید.", WalletWithdraw.amountError("۱۰", 0))
        assertEquals("مبلغ از موجودی قابل برداشت بیشتر است.", WalletWithdraw.amountError("۱٬۵۰۰", 1000))
        assertNull(WalletWithdraw.amountError("۱۰۰۰", 1000))
        assertNull(WalletWithdraw.amountError("2500", null))
        assertEquals(1500L, WalletWithdraw.parseAmount("۱،۵۰۰"))
    }

    @Test
    fun html_readsModalNamesAndScriptKeys() {
        val html = """
            <div id="vnWithdrawModal">
              <input name="csrf_token">
              <input name="amount">
              <input name="bank_account_id">
            </div>
            <div id="vnBankAddModal">
              <input name="owner_name">
              <input name="bank_name">
              <input name="sheba">
            </div>
            <script>
              fetch('/express/partner/bank-accounts/delete', {
                body: JSON.stringify({ id: selected })
              });
            </script>
        """.trimIndent()
        val fields = WalletWithdraw.fieldsFromHtml(html)
        assertEquals("amount", fields.withdrawAmount)
        assertEquals("bank_account_id", fields.withdrawAccount)
        assertEquals("sheba", fields.addSheba)
        assertEquals("owner_name", fields.addOwner)
        assertEquals("bank_name", fields.addBank)
        assertEquals("id", fields.accountId)

        val scriptOnly = """
            fetch('/express/partner/withdrawals/request', {
              body: JSON.stringify({ amount: a, account_id: id })
            });
            fetch('/express/partner/bank-accounts/add', {
              body: JSON.stringify({ iban: s, holder_name: n, bank: b })
            });
        """.trimIndent()
        val fromScript = WalletWithdraw.fieldsFromHtml(scriptOnly)
        assertEquals("account_id", fromScript.withdrawAccount)
        assertEquals("iban", fromScript.addSheba)
        assertEquals("holder_name", fromScript.addOwner)
        assertEquals("bank", fromScript.addBank)
        assertEquals("amount", WalletWithdraw.fieldsFromHtml("").withdrawAmount)
    }

    @Test
    fun accounts_ignoreCommissionRowsAndReadBalance() {
        val banks = JSONObject()
            .put(
                "accounts",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", 7)
                            .put("bank_name", "ملت")
                            .put("owner_name", "علی")
                            .put("sheba", "IR820540102680020817909002")
                            .put("is_default", 1),
                    ),
            )
            .put(
                "items",
                JSONArray().put(
                    JSONObject().put("land_code", "A1").put("commission_amount", 10),
                ),
            )
        val accounts = WalletWithdraw.parseAccounts(banks)
        assertEquals(1, accounts.size)
        assertEquals("7", accounts[0].id)
        assertTrue(accounts[0].isDefault)
        assertEquals("ملت · علی", WalletWithdraw.accountTitle(accounts[0]))

        val commissions = JSONObject()
            .put("total_commission", 9000)
            .put("withdrawable_balance", "4500")
        assertEquals(4500L, WalletWithdraw.readBalance(commissions))
        assertEquals(1200L, WalletWithdraw.readBalance(JSONObject().put("withdrawable_balance", 1200)))
        assertNull(WalletWithdraw.readBalance(JSONObject().put("total_commission", 9000)))
    }

    @Test
    fun pending_skipsPaidAndCommissionItems() {
        val payload = JSONObject().put(
            "withdrawals",
            JSONArray()
                .put(JSONObject().put("status", "paid").put("amount", 10))
                .put(JSONObject().put("status", "pending").put("amount", 2500)),
        ).put(
            "requests",
            JSONArray().put(JSONObject().put("land_code", "A1").put("status", "pending").put("amount", 9)),
        )
        val pending = WalletWithdraw.parsePending(payload)
        assertEquals(2500L, pending?.amount)
        assertEquals("در انتظار", pending?.label)
    }

    @Test
    fun bodies_useDiscoveredKeysAndNumericId() {
        val fields = WalletWithdraw.BodyFields(withdrawAccount = "account_id", addSheba = "iban")
        val withdraw = WalletWithdraw.withdrawBody(1500, "9", fields)
        assertEquals(1500L, withdraw.getLong("amount"))
        assertEquals(9L, withdraw.getLong("account_id"))
        val add = WalletWithdraw.addBody("IR820540102680020817909002", " علی ", " ملت ", fields)
        assertEquals("IR820540102680020817909002", add.getString("iban"))
        assertEquals("علی", add.getString("owner_name"))
        assertEquals("ملت", add.getString("bank_name"))
        assertEquals("abc", WalletWithdraw.idBody("abc", WalletWithdraw.BodyFields()).getString("id"))
    }

    @Test
    fun response_messagesPreferServerText() {
        assertTrue(WalletWithdraw.isCsrfExpired(JSONObject().put("error", "csrf_expired")))
        assertTrue(WalletWithdraw.isUnauthorized(JSONObject().put("http_status", 401)))
        assertFalse(WalletWithdraw.isOk(JSONObject().put("http_status", 400).put("success", false)))
        assertTrue(WalletWithdraw.isOk(JSONObject().put("http_status", 200).put("success", true)))
        assertEquals(
            "مبلغ مجاز نیست",
            WalletWithdraw.userMessage(JSONObject().put("message", "مبلغ مجاز نیست").put("error", "csrf_expired")),
        )
        assertEquals(
            "برای برداشت باید وارد حساب شوید.",
            WalletWithdraw.userMessage(JSONObject().put("error", "unauthorized")),
        )
        assertEquals(
            "شبا تکراری است",
            WalletWithdraw.userMessage(
                JSONObject().put("fields", JSONObject().put("sheba", "شبا تکراری است")),
            ),
        )
    }
}
