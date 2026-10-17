package ir.vinor.app

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** نوار فعال / بایگانی‌شده با تعداد API. */
object ArchiveChips {
    fun show(
        host: LinearLayout,
        active: Int,
        archived: Int,
        selected: String,
        onSelect: (String) -> Unit,
    ) {
        host.removeAllViews()
        val chosen = ListingArchive.normalize(selected)
        val density = host.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        for (tab in ListingArchive.tabs(active, archived)) {
            val on = tab.id == chosen
            val chip = TextView(host.context).apply {
                text = "${tab.title} ${ListingWeek.persianDigits(tab.count)}"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                minHeight = dp(36)
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (on) R.color.vinor_on_primary else R.color.vinor_text_muted,
                    ),
                )
                setBackgroundResource(if (on) R.drawable.bg_follow_tab_on else R.drawable.bg_follow_tab)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    if (tab.id != chosen) onSelect(tab.id)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8)
            host.addView(chip, lp)
        }
    }
}
