package ir.vinor.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * SwipeRefreshLayout که با اسکرول دعوا نمی‌کند:
 *  - canChildScrollUp فقط فرزند مستقیم را می‌بیند؛ وقتی فرزند FrameLayout/LinearLayout است (آمار آگهی، وینور من)
 *    همیشه false بود و کشیدن به پایین وسط لیست رفرش می‌داد. اینجا هر نمای اسکرول‌پذیرِ زیر انگشت بررسی می‌شود.
 *  - قفل محور: کشیدن افقی (چیپ‌ها، کاروسل، کارت‌ها) با کمی انحراف رو به پایین دیگر رفرش را شروع نمی‌کند.
 *  - setOnChildScrollUpCallback همچنان معتبر است (super.canChildScrollUp).
 */
class VinorSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var axis = PullRefreshGesture.Axis.UNDECIDED
    private var innerScrolled = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                axis = PullRefreshGesture.Axis.UNDECIDED
                innerScrolled = scrolledUnder(this, ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (axis == PullRefreshGesture.Axis.UNDECIDED) {
                    axis = PullRefreshGesture.axis(ev.x - downX, ev.y - downY, touchSlop)
                }
                if (axis == PullRefreshGesture.Axis.OTHER) return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> axis = PullRefreshGesture.Axis.OTHER
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun canChildScrollUp(): Boolean =
        PullRefreshGesture.childCanScrollUp(super.canChildScrollUp(), innerScrolled)

    /** بالاترین فرزندِ قابل‌مشاهدهٔ زیر نقطه را تا عمق دنبال می‌کند؛ هر کدام بتواند به بالا اسکرول کند → true. */
    private fun scrolledUnder(group: ViewGroup, x: Float, y: Float): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child: View = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val lx = x + group.scrollX - child.left - child.translationX
            val ly = y + group.scrollY - child.top - child.translationY
            if (lx < 0f || ly < 0f || lx >= child.width || ly >= child.height) continue
            if (child.canScrollVertically(-1)) return true
            return child is ViewGroup && scrolledUnder(child, lx, ly)
        }
        return false
    }
}
