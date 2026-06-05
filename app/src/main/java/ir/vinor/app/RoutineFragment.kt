package ir.vinor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import ir.vinor.app.databinding.FragmentRoutineBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import android.webkit.CookieManager
import java.util.concurrent.TimeUnit

/**
 * تب روتین - کاملاً نیتیو و هم‌سطح با وب:
 * چیپ‌های مراحل، نوار پیشرفت رنگی، تقویم با steps و رنگ سلول، امروز با ring، وضعیت و کارت آموزش.
 */
class RoutineFragment : Fragment() {

    private var _binding: FragmentRoutineBinding? = null
    private val binding get() = _binding!!

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var currentMonth: Calendar = Calendar.getInstance(Locale.US)
    private var completedDays: List<String> = emptyList()
    private var monthSteps: Map<String, Int> = emptyMap()
    private var stepsDetail: List<String> = emptyList()
    private var todayStr: String = ""
    private var refreshRunnable: Runnable? = null
    private val refreshIntervalMs = 12_000L

    private val progressColors = intArrayOf(
        0xFF4B5563.toInt(),  // 0 خاکستری
        0xFF2563EB.toInt(),  // 1 آبی
        0xFFD97706.toInt(),  // 2 نارنجی
        0xFF16a34a.toInt()   // 3 سبز
    )

    private val chipBgRes = intArrayOf(
        R.drawable.bg_routine_chip,
        R.drawable.bg_routine_chip_blue,
        R.drawable.bg_routine_chip_amber,
        R.drawable.bg_routine_chip_emerald
    )

    companion object {
        private const val TAG = "RoutineFragment"
        private const val BASE = "https://vinor.ir"
        private const val ROUTINE_DATA = "/express/partner/routine/data"
        private const val ROUTINE_STEPS_DETAIL = "/express/partner/routine/steps/detail"
        private val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRoutineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        todayStr = todayDateString()
        setupStepsReadOnly()
        setupCalendarNav()
        loadTodayDetail()
        loadMonthData()
        binding.cardTraining.setOnClickListener { openTraining() }
    }

    override fun onDestroyView() {
        stopAutoRefresh()
        job.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun cookieHeader(): String {
        val cookie = CookieManager.getInstance().getCookie(BASE)
        return cookie ?: ""
    }

    private fun todayDateString(): String {
        val c = Calendar.getInstance(Locale.US)
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun toPersianDigits(n: Int): String {
        return n.toString().map { c -> if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c }.joinToString("")
    }

    private fun setStatus(message: String) {
        if (_binding == null) return
        binding.routineStatus.text = message
    }

    private fun updateStepChipsAndProgress() {
        if (_binding == null) return
        val step1 = binding.step1
        val step2 = binding.step2
        val step3 = binding.step3
        val n = (if (step1.isChecked) 1 else 0) + (if (step2.isChecked) 1 else 0) + (if (step3.isChecked) 1 else 0)
        val colorKey = n.coerceIn(0, 3)

        binding.routineProgressBar.progress = n
        setProgressBarTint(binding.routineProgressBar, progressColors[colorKey])
        binding.routineProgressText.text = "${toPersianDigits(n)} / ${toPersianDigits(3)}"

        listOf(
            Triple(binding.step1Chip, binding.step1Dot, step1.isChecked),
            Triple(binding.step2Chip, binding.step2Dot, step2.isChecked),
            Triple(binding.step3Chip, binding.step3Dot, step3.isChecked)
        ).forEach { (chip, dot, checked) ->
            val key = if (checked) colorKey else 0
            chip.setBackgroundResource(chipBgRes[key])
            dot.setBackgroundResource(R.drawable.bg_routine_dot)
            dot.background?.mutate()?.let { DrawableCompat.setTint(it, progressColors[key]) }
        }
    }

    private fun setupStepsReadOnly() {
        binding.step1Chip.isClickable = false
        binding.step2Chip.isClickable = false
        binding.step3Chip.isClickable = false
        binding.step1.isEnabled = false
        binding.step2.isEnabled = false
        binding.step3.isEnabled = false
        updateStepChipsAndProgress()
    }

    private fun setProgressBarTint(bar: ProgressBar, color: Int) {
        bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
        val r = Runnable {
            if (_binding == null) return@Runnable
            loadTodayDetail()
            loadMonthData()
            refreshRunnable?.let { binding.root.postDelayed(it, refreshIntervalMs) }
        }
        refreshRunnable = r
        binding.root.postDelayed(r, refreshIntervalMs)
    }

    private fun stopAutoRefresh() {
        val r = refreshRunnable ?: return
        refreshRunnable = null
        _binding?.root?.removeCallbacks(r)
    }

    private fun loadTodayDetail() {
        scope.launch {
            try {
                val url = "$BASE$ROUTINE_STEPS_DETAIL?date=${URLEncoder.encode(todayStr, "UTF-8")}"
                val req = Request.Builder().url(url).addHeader("Cookie", cookieHeader()).build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                if (!resp.isSuccessful) return@launch
                val body = resp.body?.string() ?: return@launch
                val obj = JSONObject(body)
                val arr = obj.optJSONArray("detail")
                stepsDetail = mutableListOf<String>().apply {
                    if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i, ""))
                }
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.step1.isChecked = stepsDetail.contains("1")
                    binding.step2.isChecked = stepsDetail.contains("2")
                    binding.step3.isChecked = stepsDetail.contains("3")
                    updateStepChipsAndProgress()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadTodayDetail error", e)
            }
        }
    }

    private fun loadMonthData() {
        val month = String.format(Locale.US, "%04d-%02d", currentMonth.get(Calendar.YEAR), currentMonth.get(Calendar.MONTH) + 1)
        scope.launch {
            try {
                val url = "$BASE$ROUTINE_DATA?month=${URLEncoder.encode(month, "UTF-8")}"
                val req = Request.Builder().url(url).addHeader("Cookie", cookieHeader()).build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                if (!resp.isSuccessful) return@launch
                val body = resp.body?.string() ?: return@launch
                val obj = JSONObject(body)
                val daysArr = obj.optJSONArray("days")
                completedDays = mutableListOf<String>().apply {
                    if (daysArr != null) for (i in 0 until daysArr.length()) add(daysArr.optString(i, ""))
                }
                val stepsObj = obj.optJSONObject("steps")
                monthSteps = mutableMapOf<String, Int>().apply {
                    if (stepsObj != null) {
                        stepsObj.keys().forEach { key ->
                            put(key, stepsObj.optInt(key, 0).coerceIn(0, 3))
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    setStatus("روزهای انجام‌شده از سرور بارگذاری شد.")
                    updateMonthLabel()
                    fillCalendar()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMonthData error", e)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    completedDays = emptyList()
                    monthSteps = emptyMap()
                    setStatus("")
                    updateMonthLabel()
                    fillCalendar()
                }
            }
        }
    }

    private fun updateMonthLabel() {
        if (_binding == null) return
        val monthName = try {
            java.text.SimpleDateFormat("MMMM yyyy", Locale("fa")).format(currentMonth.time)
        } catch (_: Exception) {
            String.format(Locale.US, "%04d-%02d", currentMonth.get(Calendar.YEAR), currentMonth.get(Calendar.MONTH) + 1)
        }
        binding.routineMonthLabel.text = monthName
    }

    private fun fillCalendar() {
        if (_binding == null) return
        val ctx = context ?: return
        val grid = binding.routineCalendar
        grid.removeAllViews()
        val cal = currentMonth.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val numEmpty = firstDayOfWeek - 1
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics).toInt()
        val cellSize = (resources.displayMetrics.widthPixels - 32 * dp) / 7
        val cellH = 40 * dp

        val cellBgRes = intArrayOf(
            R.drawable.bg_calendar_cell,
            R.drawable.bg_calendar_done1,
            R.drawable.bg_calendar_done2,
            R.drawable.bg_calendar_done3
        )
        val cellTextColors = intArrayOf(
            0xFF9CA3AF.toInt(),
            0xFF93C5FD.toInt(),
            0xFFFCD34D.toInt(),
            0xFF4ADE80.toInt()
        )

        var index = 0
        for (i in 0 until numEmpty) {
            val place = FrameLayout(ctx)
            val lp = GridLayout.LayoutParams(GridLayout.spec(index / 7), GridLayout.spec(index % 7))
            lp.width = cellSize
            lp.height = cellH
            lp.setMargins(2, 2, 2, 2)
            place.layoutParams = lp
            grid.addView(place)
            index++
        }
        for (day in 1..maxDay) {
            val dateStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
            val stepCount = (monthSteps[dateStr] ?: 0).coerceIn(0, 3)
            val isToday = dateStr == todayStr

            val cell = FrameLayout(ctx)
            cell.setBackgroundResource(if (isToday) R.drawable.bg_calendar_today else cellBgRes[stepCount])
            val lp = GridLayout.LayoutParams(GridLayout.spec(index / 7), GridLayout.spec(index % 7))
            lp.width = cellSize
            lp.height = cellH
            lp.setMargins(2, 2, 2, 2)
            cell.layoutParams = lp

            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val dayTv = TextView(ctx).apply {
                text = toPersianDigits(day)
                setTextColor(cellTextColors[stepCount])
                textSize = 12f
                gravity = Gravity.CENTER
            }
            inner.addView(dayTv)
            if (stepCount > 0) {
                val dotsTv = TextView(ctx).apply {
                    text = "• ".repeat(stepCount).trimEnd()
                    setTextColor(cellTextColors[stepCount])
                    textSize = 10f
                    gravity = Gravity.CENTER
                }
                inner.addView(dotsTv)
            }
            cell.addView(inner)
            grid.addView(cell)
            index++
        }
    }

    private fun setupCalendarNav() {
        binding.btnPrevMonth.setOnClickListener {
            currentMonth.add(Calendar.MONTH, -1)
            loadMonthData()
        }
        binding.btnNextMonth.setOnClickListener {
            currentMonth.add(Calendar.MONTH, 1)
            loadMonthData()
        }
    }

    private fun openTraining() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$BASE/express/partner/training")))
        } catch (e: Exception) {
            context?.let { Toast.makeText(it, "خطا در باز کردن لینک", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onResume() {
        super.onResume()
        todayStr = todayDateString()
        loadTodayDetail()
        loadMonthData()
        startAutoRefresh()
    }

    override fun onPause() {
        stopAutoRefresh()
        super.onPause()
    }
}
