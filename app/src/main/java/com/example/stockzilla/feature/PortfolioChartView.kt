package com.example.stockzilla.feature

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.stockzilla.R
import com.example.stockzilla.data.PortfolioValueSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple line chart for portfolio value over time. Expects data ordered by date ascending.
 */
class PortfolioChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
    }

    private val dateFormat = SimpleDateFormat("M/d", Locale.getDefault())

    private var data: List<PortfolioValueSnapshotEntity> = emptyList()
    private var chartLeft: Float = 0f
    private var chartTop: Float = 0f
    private var chartRight: Float = 0f
    private var chartBottom: Float = 0f
    private var valueMin: Double = 0.0
    private var valueMax: Double = 0.0

    fun setData(snapshots: List<PortfolioValueSnapshotEntity>) {
        data = snapshots
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val paddingLeft = 72f
        val paddingRight = 24f
        val paddingTop = 24f
        val paddingBottom = 48f

        chartLeft = paddingLeft
        chartTop = paddingTop
        chartRight = w - paddingRight
        chartBottom = h - paddingBottom

        valueMin = data.minOf { it.value }.let { if (it <= 0) 0.0 else it * 0.95 }
        valueMax = data.maxOf { it.value }.let { m -> (m * 1.05).coerceAtLeast(valueMin + 1.0) }
        val valueRange = (valueMax - valueMin).coerceAtLeast(1.0)

        linePaint.color = ContextCompat.getColor(context, R.color.colorPrimary)
        gridPaint.color = ContextCompat.getColor(context, R.color.textHint)
        textPaint.color = ContextCompat.getColor(context, R.color.textPrimary)

        // Grid: horizontal lines
        for (i in 0..4) {
            val y = chartTop + (chartBottom - chartTop) * (1 - i / 4f)
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }

        // Line path
        val path = Path()
        val n = data.size
        for (i in data.indices) {
            val x = chartLeft + (chartRight - chartLeft) * (i.toFloat() / (n - 1).coerceAtLeast(1))
            val v = data[i].value
            val y = chartBottom - ((v - valueMin) / valueRange).toFloat() * (chartBottom - chartTop)
            if (i == 0) path.moveTo(x, y)
            else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        // Y-axis labels (left)
        textPaint.textSize = 32f
        for (i in 0..4) {
            val frac = 1 - i / 4f
            val value = valueMin + valueRange * frac
            val y = chartTop + (chartBottom - chartTop) * (1 - frac)
            val label = formatShortMoney(value)
            canvas.drawText(label, 8f, y + 10f, textPaint)
        }

        // X-axis labels (bottom) - show a few dates
        textPaint.textSize = 28f
        val step = (n - 1).coerceAtLeast(1) / 4
        for (i in 0..4) {
            val idx = (i * step).coerceAtMost(n - 1)
            val x = chartLeft + (chartRight - chartLeft) * (idx.toFloat() / (n - 1).coerceAtLeast(1))
            val label = dateFormat.format(Date(data[idx].dateMs))
            val textW = textPaint.measureText(label)
            canvas.drawText(label, x - textW / 2, chartBottom + 36f, textPaint)
        }
    }

    private fun formatShortMoney(value: Double): String {
        val suffix = when {
            value >= 1_000_000 -> "%.1fM".format(value / 1_000_000)
            value >= 1_000 -> "%.1fK".format(value / 1_000)
            else -> "%.0f".format(value)
        }
        return "$$suffix"
    }
}