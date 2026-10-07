package com.shimonfiltser.smartwifianalyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

private const val GRID_COLOR = 0x26FFFFFF
private const val AXIS_TEXT_COLOR = 0xFF9AA3AE.toInt()

/** Common dBm Y axis (-100..-20) for the channel and time graphs. */
abstract class DbmGraphView(context: Context) : View(context) {
    protected val density = resources.displayMetrics.density
    protected val plot = RectF()

    protected val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRID_COLOR
        strokeWidth = density
    }
    protected val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_TEXT_COLOR
        textSize = sp(11f)
    }
    protected val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        isFakeBoldText = true
    }
    protected val emptyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_TEXT_COLOR
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
    }

    protected fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    protected fun yOf(dbm: Float): Float {
        val clamped = dbm.coerceIn(WifiMath.MIN_DBM.toFloat(), WifiMath.MAX_DBM.toFloat())
        return plot.bottom - (clamped - WifiMath.MIN_DBM) / (WifiMath.MAX_DBM - WifiMath.MIN_DBM) * plot.height()
    }

    protected fun layoutPlot() {
        plot.set(40 * density, 18 * density, width - 12 * density, height - 30 * density)
    }

    protected fun drawYAxis(canvas: Canvas) {
        axisText.textAlign = Paint.Align.RIGHT
        for (db in WifiMath.MIN_DBM..WifiMath.MAX_DBM step 10) {
            val y = yOf(db.toFloat())
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            canvas.drawText(db.toString(), plot.left - 6 * density, y + 4 * density, axisText)
        }
    }

    protected fun drawEmpty(canvas: Canvas, text: String) {
        canvas.drawText(text, plot.centerX(), plot.centerY(), emptyText)
    }

    protected fun drawLabel(canvas: Canvas, text: String, color: Int, x: Float, y: Float) {
        labelText.color = color
        labelText.textAlign = Paint.Align.CENTER
        val half = labelText.measureText(text) / 2
        val cx = x.coerceIn(plot.left + half, plot.right - half)
        canvas.drawText(text, cx, y.coerceAtLeast(plot.top + labelText.textSize), labelText)
    }
}

/** Networks drawn as bell shapes over the channels they occupy, like the classic analyzer view. */
class ChannelGraphView(context: Context) : DbmGraphView(context) {
    private var aps: List<AccessPoint> = emptyList()
    private var band = Band.GHZ_2_4

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun setData(aps: List<AccessPoint>, band: Band) {
        this.aps = aps
        this.band = band
        invalidate()
    }

    private fun range(): Pair<Float, Float> = when (band) {
        Band.GHZ_2_4 -> -1f to 15f
        Band.GHZ_5 -> 28f to 181f
        Band.GHZ_6 -> -5f to 239f
    }

    private fun ticks(): List<Int> = when (band) {
        Band.GHZ_2_4 -> (1..14).toList()
        Band.GHZ_5 -> listOf(36, 44, 52, 60, 100, 108, 116, 124, 132, 140, 149, 157, 165, 173)
        Band.GHZ_6 -> listOf(1, 33, 65, 97, 129, 161, 193, 225)
    }

    private fun xOf(ch: Float): Float {
        val (min, max) = range()
        return plot.left + (ch - min) / (max - min) * plot.width()
    }

    override fun onDraw(canvas: Canvas) {
        layoutPlot()
        drawYAxis(canvas)

        axisText.textAlign = Paint.Align.CENTER
        for (ch in ticks()) {
            val x = xOf(ch.toFloat())
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            canvas.drawText(ch.toString(), x, plot.bottom + 16 * density, axisText)
        }

        if (aps.isEmpty()) {
            drawEmpty(canvas, "No networks in ${band.label}")
            return
        }

        // Weakest first so the strongest networks end up on top.
        val ordered = aps.sortedBy { it.level }
        canvas.save()
        canvas.clipRect(plot)
        for (ap in ordered) {
            val xl = xOf(ap.centerPos - ap.halfWidth)
            val xr = xOf(ap.centerPos + ap.halfWidth)
            val yb = plot.bottom
            val yp = yOf(ap.level.toFloat())
            // Control height chosen so the cubic's midpoint lands exactly on the peak.
            val ctrl = (4 * yp - yb) / 3
            val inset = (xr - xl) * 0.15f
            path.reset()
            path.moveTo(xl, yb)
            path.cubicTo(xl + inset, ctrl, xr - inset, ctrl, xr, yb)

            fillPaint.color = (ap.color and 0x00FFFFFF) or 0x38000000
            canvas.drawPath(path, fillPaint)
            strokePaint.color = ap.color
            strokePaint.strokeWidth = (if (ap.connected) 3.5f else 2f) * density
            canvas.drawPath(path, strokePaint)
        }
        canvas.restore()

        for (ap in ordered) {
            val x = xOf(ap.centerPos)
            val y = yOf(ap.level.toFloat()) - 6 * density
            val name = if (ap.connected) "● ${ap.displayName}" else ap.displayName
            drawLabel(canvas, name, ap.color, x, y)
        }
    }
}

class SignalSeries(val label: String, val color: Int, val samples: List<Sample>, val connected: Boolean)

/** Signal level of every network over the last [windowMs]. */
class TimeGraphView(context: Context, private val windowMs: Long) : DbmGraphView(context) {
    private var series: List<SignalSeries> = emptyList()
    private var now = 0L
    private var bandLabel = ""

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun setData(series: List<SignalSeries>, now: Long, bandLabel: String) {
        this.series = series
        this.now = now
        this.bandLabel = bandLabel
        invalidate()
    }

    private fun xOf(t: Long): Float = plot.right - (now - t).toFloat() / windowMs * plot.width()

    override fun onDraw(canvas: Canvas) {
        layoutPlot()
        drawYAxis(canvas)

        axisText.textAlign = Paint.Align.CENTER
        for (s in 0..(windowMs / 1000) step 30) {
            val x = plot.right - s * 1000f / windowMs * plot.width()
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            canvas.drawText(if (s == 0L) "now" else "−$s s", x, plot.bottom + 16 * density, axisText)
        }

        if (series.isEmpty()) {
            drawEmpty(canvas, "No data for ${bandLabel}")
            return
        }

        canvas.save()
        canvas.clipRect(plot.left, 0f, plot.right, plot.bottom)
        for (s in series) {
            path.reset()
            s.samples.forEachIndexed { i, smp ->
                val x = xOf(smp.t)
                val y = yOf(smp.level.toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            linePaint.color = s.color
            linePaint.strokeWidth = (if (s.connected) 3.5f else 2f) * density
            canvas.drawPath(path, linePaint)
            val last = s.samples.last()
            dotPaint.color = s.color
            canvas.drawCircle(xOf(last.t), yOf(last.level.toFloat()), 3.5f * density, dotPaint)
        }
        canvas.restore()

        for (s in series) {
            val last = s.samples.last()
            val x = xOf(last.t) - labelText.measureText(s.label) / 2 - 6 * density
            drawLabel(canvas, s.label, s.color, x, yOf(last.level.toFloat()) - 6 * density)
        }
    }
}
