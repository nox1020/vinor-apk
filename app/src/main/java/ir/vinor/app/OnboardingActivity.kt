package ir.vinor.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ir.vinor.app.databinding.ActivityOnboardingBinding
import ir.vinor.app.databinding.ItemOnboardingPageBinding

/** معرفی تمام‌صفحه به سبک تلگرام — فقط اولین اجرای اپ. */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val pages = listOf(
        Page(R.drawable.vinor_splash_mark, R.drawable.bg_onboard_icon_brand, R.string.onboard_title_1, R.string.onboard_body_1, tint = false),
        Page(R.drawable.ic_sparkle, R.drawable.bg_onboard_icon_chat, R.string.onboard_title_2, R.string.onboard_body_2),
        Page(R.drawable.ic_home, R.drawable.bg_onboard_icon_home, R.string.onboard_title_home, R.string.onboard_body_home),
        Page(R.drawable.ic_building, R.drawable.bg_onboard_icon_sale, R.string.onboard_title_3, R.string.onboard_body_3),
        Page(R.drawable.ic_wallet, R.drawable.bg_onboard_icon_wallet, R.string.onboard_title_4, R.string.onboard_body_4),
        Page(R.drawable.ic_graduation, R.drawable.bg_onboard_icon_learn, R.string.onboard_title_5, R.string.onboard_body_5),
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(VinorLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        SystemUi.apply(this)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.onboardPager.adapter = PageAdapter(pages)
        binding.onboardPager.offscreenPageLimit = 1
        buildDots()
        syncChrome(0)

        binding.onboardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                syncChrome(position)
            }
        })
        binding.onboardSkip.setOnClickListener { finishOnboarding() }
        binding.onboardNext.setOnClickListener {
            val next = binding.onboardPager.currentItem + 1
            if (next < pages.size) binding.onboardPager.currentItem = next
            else finishOnboarding()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = binding.onboardPager.currentItem
                if (current > 0) binding.onboardPager.currentItem = current - 1
                else finish()
            }
        })
    }

    private fun buildDots() {
        binding.onboardDots.removeAllViews()
        val sizeOff = dp(7)
        pages.indices.forEach { i ->
            val dot = View(this).apply {
                background = getDrawable(R.drawable.bg_onboard_dot)
                setOnClickListener { binding.onboardPager.currentItem = i }
            }
            val lp = android.widget.LinearLayout.LayoutParams(sizeOff, sizeOff)
            lp.marginStart = dp(3)
            lp.marginEnd = dp(3)
            binding.onboardDots.addView(dot, lp)
        }
    }

    private fun syncChrome(position: Int) {
        val last = position == pages.lastIndex
        binding.onboardSkip.isVisible = !last
        binding.onboardNext.text = getString(if (last) R.string.onboard_start else R.string.onboard_next)
        val sizeOnW = dp(18)
        val sizeOff = dp(7)
        for (i in 0 until binding.onboardDots.childCount) {
            val dot = binding.onboardDots.getChildAt(i)
            val on = i == position
            val lp = dot.layoutParams as android.widget.LinearLayout.LayoutParams
            lp.width = if (on) sizeOnW else sizeOff
            lp.height = sizeOff
            dot.layoutParams = lp
            dot.setBackgroundResource(if (on) R.drawable.bg_onboard_dot_on else R.drawable.bg_onboard_dot)
        }
    }

    private fun finishOnboarding() {
        VinorOnboardingStore.markDone(this)
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_FROM_ONBOARDING, true)
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private class PageAdapter(private val pages: List<Page>) :
        RecyclerView.Adapter<PageAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemOnboardingPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(pages[position])
        }

        override fun getItemCount(): Int = pages.size

        class Holder(private val binding: ItemOnboardingPageBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(page: Page) {
                binding.onboardIconBg.setBackgroundResource(page.iconBg)
                binding.onboardIcon.setImageResource(page.icon)
                if (page.tint) {
                    binding.onboardIcon.setColorFilter(Color.WHITE)
                    binding.onboardIcon.setPadding(dp(20), dp(20), dp(20), dp(20))
                } else {
                    binding.onboardIcon.clearColorFilter()
                    binding.onboardIcon.setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                binding.onboardTitle.setText(page.title)
                binding.onboardBody.setText(page.body)
            }

            private fun dp(v: Int): Int = (v * itemView.resources.displayMetrics.density).toInt()
        }
    }

    private data class Page(
        val icon: Int,
        val iconBg: Int,
        val title: Int,
        val body: Int,
        val tint: Boolean = true,
    )

    companion object {
        const val EXTRA_FROM_ONBOARDING = "from_onboarding"
    }
}
