package ir.vinor.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class PriceHistoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val values = mutableListOf<Float>()
    private val neighborhood = mutableListOf<Float>()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF10B981.toInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt()
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6EE7B7.toInt()
        style = Paint.Style.FILL
    }
    private val linePath = Path()
    private val fillPath = Path()
    private val dashPath = Path()

    fun setSeries(valuePoints: List<Float>, neighborhoodPoints: List<Float>) {
        values.clear()
        neighborhood.clear()
        values.addAll(valuePoints)
        neighborhood.addAll(neighborhoodPoints)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return
        val pad = 18f * resources.displayMetrics.density
        val w = width.toFloat() - pad * 2
        val h = height.toFloat() - pad * 2
        if (w <= 0f || h <= 0f) return
        val all = values + neighborhood
        val min = all.minOrNull() ?: return
        val maxV = max(all.maxOrNull() ?: min + 1f, min + 1f)
        val span = max(1f, maxV - min)
        fun x(i: Int, size: Int) = pad + if (size <= 1) 0f else i * w / (size - 1)
        fun y(v: Float) = pad + h - ((v - min) / span) * h
        linePath.reset(); fillPath.reset(); dashPath.reset()
        values.forEachIndexed { i, v ->
            val px = x(i, values.size)
            val py = y(v)
            if (i == 0) {
                linePath.moveTo(px, py)
                fillPath.moveTo(px, py)
            } else {
                linePath.lineTo(px, py)
                fillPath.lineTo(px, py)
            }
        }
        fillPath.lineTo(x(values.lastIndex, values.size), pad + h)
        fillPath.lineTo(x(0, values.size), pad + h)
        fillPath.close()
        fillPaint.shader = LinearGradient(
            0f, pad, 0f, pad + h,
            0x6110B981, 0x0010B981, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(fillPath, fillPaint)
        if (neighborhood.size >= 2) {
            neighborhood.forEachIndexed { i, v ->
                val px = x(i, neighborhood.size)
                val py = y(v)
                if (i == 0) dashPath.moveTo(px, py) else dashPath.lineTo(px, py)
            }
            canvas.drawPath(dashPath, dashPaint)
        }
        canvas.drawPath(linePath, linePaint)
        canvas.drawCircle(x(values.lastIndex, values.size), y(values.last()), 7f, dotPaint)
    }
}
