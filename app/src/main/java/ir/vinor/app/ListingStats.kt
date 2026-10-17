package ir.vinor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * صفحهٔ آمار یک آگهی. داده از همان [ListingWeek] و [ListingRepository] می‌آید.
 */
object ListingStats {
    const val TITLE = "آمار"
    const val ARG_SNAPSHOT = "snapshot"
    const val EMPTY = "آمار این آگهی هنوز از سرور نیامده است."
    const val PATH = MyPropertyManage.PORTFOLIO_PATH

    enum class Phase {
        LOADING,
        CONTENT,
        EMPTY,
        OFFLINE,
    }

    fun pack(snapshot: JSONObject): String = MyPropertyManage.commitSnapshot(snapshot)

    fun read(raw: String?): JSONObject = MyPropertyManage.readSnapshot(raw)

    /** کارت ناوبری را با نسخهٔ مخزن همین آگهی یکی می‌کند. */
    fun resolve(passed: JSONObject): JSONObject {
        val out = JSONObject(passed.toString())
        val stored = ListingRepository.resolve(MyPropertyManage.codeOf(out)) ?: return out
        ListingWeek.copyOnto(out, stored)
        if (Listings.hasPublishSignal(stored)) {
            Listings.adoptCode(out, stored)
            Listings.align(out, Listings.published(stored))
            ListingWeek.copyOnto(out, stored)
        }
        return out
    }

    /**
     * توقف محلی پرچم‌ها را خصوصی می‌کند ولی تاریخ انتشار را پاک نمی‌کند.
     * تا رسیدن پاسخ سرور، صفحه همان حالت منتشرنشده را نشان می‌دهد.
     */
    fun forScreen(passed: JSONObject): JSONObject {
        val out = resolve(passed)
        if (Listings.hasPublishSignal(out) && !Listings.published(out)) {
            out.put("published_at", JSONObject.NULL)
        }
        return out
    }

    fun display(passed: JSONObject): ListingWeek.ViewModel = ListingWeek.present(forScreen(passed))

    fun phase(ready: Boolean, finished: Boolean, failed: Boolean): Phase {
        if (ready) return Phase.CONTENT
        if (!finished) return Phase.LOADING
        if (failed) return Phase.OFFLINE
        return Phase.EMPTY
    }

    /** ردیف همین آگهی در پاسخ ملک من، با کد فعلی یا کد قبلی. */
    fun find(portfolio: JSONObject, code: String): JSONObject? {
        val want = ListingRepository.currentCode(code).ifBlank { Listings.canonicalCode(code) }
        if (want.isBlank()) return null
        val items = portfolio.optJSONArray("items") ?: portfolio.optJSONArray("listings")
        val row = firstMatch(items, want)
        if (row != null) return row
        val property = portfolio.optJSONObject("property")
        if (property != null && matches(property, want)) return property
        if (matches(portfolio, want)) return portfolio
        return null
    }

    /** پاسخ تازهٔ ملک من روی کارت و مخزن می‌نشیند. پاسخ نامعتبر همان کارت را برمی‌گرداند. */
    fun applyPortfolio(current: JSONObject, portfolio: JSONObject): JSONObject {
        if (!VinorPageLoad.canRemember(portfolio)) return current
        ListingRepository.ingestTree(VinorPageLoad.rememberedCopy(portfolio))
        val row = find(portfolio, MyPropertyManage.codeOf(current)) ?: return resolve(current)
        val out = JSONObject(current.toString())
        Listings.adoptCode(out, row)
        ListingWeek.copyOnto(out, row)
        if (Listings.hasPublishSignal(row) || row.has("published_for_sale")) {
            Listings.align(out, Listings.published(row))
            ListingWeek.copyOnto(out, row)
        }
        if (Listings.codeOf(out).isNotBlank()) ListingRepository.adopt(out)
        return out
    }

    private fun firstMatch(items: JSONArray?, want: String): JSONObject? {
        if (items == null) return null
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            if (matches(row, want)) return row
        }
        return null
    }

    private fun matches(row: JSONObject, want: String): Boolean {
        val code = Listings.codeOf(row)
        if (code.isNotBlank() && (code == want || ListingRepository.currentCode(code) == want)) return true
        val previous = Listings.previousCode(row)
        return previous.isNotBlank() && (previous == want || ListingRepository.currentCode(previous) == want)
    }
}
