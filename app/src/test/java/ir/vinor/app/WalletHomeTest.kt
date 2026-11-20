package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** تب کیف پول v2 (CommissionsFragment) — هم‌ارز tests/test_wallet_v2_entry.py وب. */
class WalletHomeTest {

    private fun apiWallet(pending: Long = 10_000_000L, enabled: Boolean = true, can: Boolean = true) = JSONObject()
        .put("ok", true)
        .put("min_amount", 1000)
        .put("wallet", JSONObject()
            .put("available", 15_000_000).put("blocked", 5_000_000).put("total", 30_000_000)
            .put("pending_withdrawal", pending)
            .put("lines", JSONArray()
                .put(JSONObject().put("key", "blocked").put("label", "بلوکه برای پورسانت").put("value", "۵٬۰۰۰٬۰۰۰ تومان"))
                .put(JSONObject().put("key", "pending_withdrawal").put("label", "در انتظار برداشت").put("value", "۱۰٬۰۰۰٬۰۰۰ تومان"))
                .put(JSONObject().put("key", "total").put("label", "کل موجودی").put("value", "۳۰٬۰۰۰٬۰۰۰ تومان"))))
        .put("groups", JSONArray().put(JSONObject().put("key", "2026-11-20").put("label", "امروز")
            .put("entries", JSONArray().put(JSONObject().put("kind", "topup").put("title", "افزایش اعتبار")
                .put("amount_label", "+۳۰٬۰۰۰٬۰۰۰").put("tone", "green")))))
        .put("withdraw", JSONObject().put("enabled", enabled).put("can_withdraw", can).put("min_amount", 10_000_000)
            .put("requests", JSONArray()))

    @Test
    fun loadedOnlyForNewWalletApi() {
        assertTrue(WalletHome.loaded(apiWallet()))
        assertFalse(WalletHome.loaded(null))
        assertFalse(WalletHome.loaded(JSONObject().put("ok", true).put("min_amount", 1000)))
        assertFalse(WalletHome.loaded(JSONObject().put("http_status", 404)))
        assertFalse(WalletHome.loaded(JSONObject().put("offline", true)))
    }

    @Test
    fun cardShowsEachBalanceOnceAndNeverTotal() {
        val o = WalletCharge.parseOptions(apiWallet())
        assertEquals(15_000_000L, o.header.available)
        assertEquals(listOf("blocked", "pending_withdrawal"), WalletHome.chips(o.header).map { it.key })
        val labels = WalletHome.balanceLabels(o)
        assertEquals(listOf("موجودی قابل استفاده", "بلوکه برای پورسانت", "در انتظار برداشت"), labels)
        assertEquals(labels.size, labels.toSet().size)
        assertFalse(labels.any { it == "کل موجودی" || it == "کل درآمد" || it == "پورسانت قابل برداشت" })
        assertEquals("امروز", o.groups.single().label)
    }

    @Test
    fun pendingWithdrawalChipHiddenWhenZero() {
        val o = WalletCharge.parseOptions(apiWallet(pending = 0L))
        assertEquals(listOf("blocked"), WalletHome.chips(o.header).map { it.key })
    }

    @Test
    fun withdrawButtonState() {
        val ok = WalletCharge.parseOptions(apiWallet())
        assertEquals(WalletHome.WithdrawButton(visible = true, dimmed = false), WalletHome.withdrawButton(ok, true))
        val low = WalletCharge.parseOptions(apiWallet(can = false))
        assertEquals(WalletHome.WithdrawButton(visible = true, dimmed = true), WalletHome.withdrawButton(low, true))
        val pending = WalletCharge.parseOptions(apiWallet(enabled = false, can = false))
        assertFalse(WalletHome.withdrawButton(pending, true).visible)
        assertFalse(WalletHome.withdrawButton(ok, hasHandler = false).visible)
    }

    @Test
    fun homeAndProfileCardsUseAvailableBalance() {
        val home = JSONObject().put("withdrawable_balance", 20_000_000).put("wallet_available", 15_000_000)
        assertEquals(15_000_000L, HomePulse.withdrawable(home))
        assertEquals(4500L, HomePulse.withdrawable(JSONObject().put("withdrawable_balance", "4500")))
    }
}
