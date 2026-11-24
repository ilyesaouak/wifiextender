package com.ilyes.wifiextender

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Smooth live graph of the last 60 seconds of download speed. */
class SparklineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxPoints = 60
    private val values = ArrayDeque<Float>()
    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF
        strokeWidth = 1f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePath = Path()
    private val fillPath = Path()

    init {
        val accent = context.getColor(R.color.accent)
        val accent2 = context.getColor(R.color.accent2)
        linePaint.color = accent
        dotPaint.color = accent2
    }

    fun push(v: Float) {
        values.addLast(v.coerceAtLeast(0f))
        while (values.size > maxPoints) values.removeFirst()
        invalidate()
    }

    fun clear() {
        values.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val accent = context.getColor(R.color.accent)
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            (accent and 0x00FFFFFF) or 0x55000000, accent and 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        linePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            context.getColor(R.color.accent), context.getColor(R.color.accent2),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 6f * density

        // subtle grid
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (values.size < 2) return

        val max = maxOf(values.maxOrNull() ?: 0f, 1f) * 1.2f
        val step = w / (maxPoints - 1)
        val startX = w - (values.size - 1) * step
        fun yOf(v: Float) = h - pad - (v / max) * (h - 2 * pad)

        linePath.reset()
        var px = startX
        var py = yOf(values.first())
        linePath.moveTo(px, py)
        for (i in 1 until values.size) {
            val x = startX + i * step
            val y = yOf(values[i])
            val mx = (px + x) / 2
            linePath.cubicTo(mx, py, mx, y, x, y) // smooth curve
            px = x; py = y
        }

        fillPath.set(linePath)
        fillPath.lineTo(px, h)
        fillPath.lineTo(startX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
        canvas.drawCircle(px, py, 4.5f * density, dotPaint)
    }
}
