package com.endiq.turtlelauncher.feature.turtle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kr.co.donghyun.turtlelauncher.R
import java.util.Locale

class WeeklyPlaytimeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dayLabels = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private var hoursPerDay = FloatArray(7)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_primary)
        style = Paint.Style.FILL
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.turtle_text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 10f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.turtle_text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 10f
    }

    private val barWidthPx = resources.displayMetrics.density * 4f
    private val barMaxHeightPx = resources.displayMetrics.density * 46f
    private val barMinHeightPx = resources.displayMetrics.density * 2f

    /** [hours] must have exactly 7 entries, oldest (Monday) first. */
    fun setData(hours: FloatArray) {
        hoursPerDay = if (hours.size == 7) hours else FloatArray(7)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val columnWidth = width / 7f
        val maxHours = (hoursPerDay.maxOrNull() ?: 0f).coerceAtLeast(0.1f)
        val labelY = height - labelPaint.fontMetrics.bottom
        val barBottom = labelY + labelPaint.fontMetrics.top - (resources.displayMetrics.density * 6f)
        val valueBaseline = valuePaint.textSize

        for (i in 0 until 7) {
            val cx = columnWidth * i + columnWidth / 2f
            val barHeight = (barMaxHeightPx * (hoursPerDay[i] / maxHours)).coerceAtLeast(barMinHeightPx)

            canvas.drawText(String.format(Locale.US, "%.1f", hoursPerDay[i]), cx, valueBaseline, valuePaint)
            canvas.drawRoundRect(
                cx - barWidthPx / 2f, barBottom - barHeight,
                cx + barWidthPx / 2f, barBottom,
                barWidthPx / 2f, barWidthPx / 2f,
                barPaint
            )
            canvas.drawText(dayLabels[i], cx, labelY, labelPaint)
        }
    }
}
