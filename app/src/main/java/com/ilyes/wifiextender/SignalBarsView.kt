package com.ilyes.wifiextender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Four signal bars, coloured by quality. */
class SignalBarsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var level: Int = 0
        set(value) { if (field != value) { field = value.coerceIn(0, 4); invalidate() } }

    var activeColor: Int = context.getColor(R.color.green)
        set(value) { if (field != value) { field = value; invalidate() } }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val heights = floatArrayOf(0.35f, 0.55f, 0.78f, 1f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = width * 0.1f
        val barW = (width - gap * 3) / 4f
        val radius = barW / 2.5f
        for (i in 0 until 4) {
            val left = i * (barW + gap)
            val top = height * (1 - heights[i])
            rect.set(left, top, left + barW, height.toFloat())
            paint.color = if (i < level) activeColor else 0x26FFFFFF
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
