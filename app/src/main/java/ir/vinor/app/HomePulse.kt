package ir.vinor.app

import org.json.JSONObject

/** عددهای خانه: بازدید، معاملهٔ موفق و پورسانت قابل برداشت. */
object HomePulse {
    fun withdrawable(obj: JSONObject): Long = WalletWithdraw.readBalance(obj) ?: 0L

    fun deals(obj: JSONObject): Long = WalletWithdraw.money(obj, "home_deals_done")

    fun visits(obj: JSONObject): Long = WalletWithdraw.money(obj, "home_visits_done")

    fun customers(obj: JSONObject): Long = WalletWithdraw.money(obj, "home_active_customers")

    fun requests(obj: JSONObject): Long = WalletWithdraw.money(obj, "home_requests_pipeline_count")
}
