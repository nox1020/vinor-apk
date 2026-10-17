package ir.vinor.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import ir.vinor.app.databinding.ItemListingWeekBinding
import ir.vinor.app.databinding.ItemListingWeekEventBinding
import ir.vinor.app.databinding.ItemListingWeekLineBinding

/** بلوک هفتهٔ اول را زیر کارت ملک من می‌چیند. منطق نمایش در [ListingWeek] است. */
object ListingWeekPanel {
    fun addTo(parent: ViewGroup, land: org.json.JSONObject, onClick: (() -> Unit)? = null) {
        val week = ListingWeek.present(land)
        if (!week.available) return
        val binding = ItemListingWeekBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        bind(binding, week)
        if (onClick != null) {
            binding.root.setOnClickListener { onClick() }
            binding.root.isClickable = true
        }
        parent.addView(binding.root)
    }

    fun replace(host: ViewGroup, land: org.json.JSONObject) {
        host.removeAllViews()
        addTo(host, land)
    }

    private fun bind(binding: ItemListingWeekBinding, week: ListingWeek.ViewModel) {
        binding.weekTitle.text = week.title
        binding.weekStatus.isVisible = week.statusText.isNotBlank()
        binding.weekStatus.text = week.statusText
        binding.weekLines.isVisible = week.lines.isNotEmpty()
        binding.weekLines.removeAllViews()
        val inflater = LayoutInflater.from(binding.root.context)
        for (line in week.lines) {
            val row = ItemListingWeekLineBinding.inflate(inflater, binding.weekLines, false)
            row.weekLineLabel.text = line.label
            row.weekLineValue.text = line.value
            binding.weekLines.addView(row.root)
        }
        binding.weekHint.isVisible = week.hint.isNotBlank()
        binding.weekHint.text = week.hint
        binding.weekActivityTitle.text = ListingWeek.ACTIVITY_TITLE
        binding.weekActivityTitle.isVisible = week.activity.isNotEmpty()
        binding.weekActivity.isVisible = week.activity.isNotEmpty()
        binding.weekActivity.removeAllViews()
        for (event in week.activity) {
            val row = ItemListingWeekEventBinding.inflate(inflater, binding.weekActivity, false)
            row.weekEventText.text = event.text
            row.weekEventWhen.isVisible = event.whenLabel.isNotBlank()
            row.weekEventWhen.text = event.whenLabel
            binding.weekActivity.addView(row.root)
        }
    }
}
