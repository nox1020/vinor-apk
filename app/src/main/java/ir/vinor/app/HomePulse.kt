package ir.vinor.app

import org.json.JSONObject

/** عددهای خانه: بازدید، معاملهٔ موفق و «موجودی قابل استفاده» (همان عدد کارت کیف پول). */
object HomePulse {
    /**
     * کارت کیف پول خانه/پروفایل: سرور جدید wallet_available (بدون بلوکه و برداشت در انتظار) می‌دهد؛
     * سرور قدیمی فقط withdrawable_balance.
     */
    fun withdrawable(obj: JSONObject): Long {
        if (obj.has("wallet_available")) return WalletWithdraw.money(obj, "wallet_available").coerceAtLeast(0L)
        return WalletWithdraw.readBalance(obj) ?: 0L
    }

    fun deals(obj: JSONObject): Long = WalletWithdraw.money(obj, "home_deals_done")

    fun visits(obj: JSONObject): Long = WalletWithdraw.money(obj, "home_visits_done")

    fun customers(obj: JSONObject): Long = WalletWithdraw.money(obj, "home_active_customers")

    fun requests(obj: JSONObject): Long = WalletWithdraw.money(obj, "home_requests_pipeline_count")
}
