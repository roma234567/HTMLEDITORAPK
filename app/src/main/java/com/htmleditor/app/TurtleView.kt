package com.htmleditor.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Холст для Python turtle. Потокобезопасен: интерпретатор добавляет
 * сегменты из фонового потока, отрисовка — по кадру через invalidate.
 */
class TurtleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Segment(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val color: Int, val width: Float
    )

    private val segments = ArrayList<Segment>()
    private var markerX = 0f
    private var markerY = 0f
    private var markerHeading = 0.0
    private var markerVisible = true
    private var markerColor = Color.DKGRAY
    private var bgColor = Color.WHITE

    private val lock = Any()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bgPaint = Paint()

    private var invalidatePending = false

    // ------------------------------------------------------------ вызовы из GUI

    fun resetAll() {
        synchronized(lock) {
            segments.clear()
            markerX = 0f
            markerY = 0f
            markerHeading = 0.0
            markerVisible = true
            markerColor = Color.DKGRAY
            bgColor = Color.WHITE
        }
        postInvalidateOnAnimation()
    }

    fun clearDrawing() {
        synchronized(lock) { segments.clear() }
        postInvalidateOnAnimation()
    }

    fun addSegment(x1: Double, y1: Double, x2: Double, y2: Double, color: Int, width: Double) {
        synchronized(lock) {
            segments.add(
                Segment(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), color, width.toFloat().coerceAtLeast(1f))
            )
        }
        requestDraw()
    }

    fun setMarker(x: Double, y: Double, heading: Double, visible: Boolean, color: Int) {
        synchronized(lock) {
            markerX = x.toFloat()
            markerY = y.toFloat()
            markerHeading = heading
            markerVisible = visible
            markerColor = color
        }
        requestDraw()
    }

    fun setCanvasColor(color: Int) {
        synchronized(lock) { bgColor = color }
        postInvalidateOnAnimation()
    }

    private fun requestDraw() {
        // Один invalidate на кадр — не заваливаем UI-поток при циклах
        if (!invalidatePending) {
            invalidatePending = true
            postOnAnimation {
                invalidatePending = false
                invalidate()
            }
        }
    }

    // --------------------------------------------------------------- отрисовка

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val segs: List<Segment>
        val mx: Float
        val my: Float
        val mh: Double
        val mv: Boolean
        val mc: Int
        val bg: Int
        synchronized(lock) {
            segs = ArrayList(segments)
            mx = markerX; my = markerY; mh = markerHeading; mv = markerVisible; mc = markerColor
            bg = bgColor
        }

        canvas.drawColor(bg)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Границы содержимого (+ маркер + начало координат)
        var minX = 0f
        var maxX = 0f
        var minY = 0f
        var maxY = 0f
        for (s in segs) {
            minX = minOf(minX, s.x1, s.x2); maxX = maxOf(maxX, s.x1, s.x2)
            minY = minOf(minY, s.y1, s.y2); maxY = maxOf(maxY, s.y1, s.y2)
        }
        minX = minOf(minX, mx); maxX = maxOf(maxX, mx)
        minY = minOf(minY, my); maxY = maxOf(maxY, my)

        val pad = 28f
        val bw = maxOf(maxX - minX, 40f)
        val bh = maxOf(maxY - minY, 40f)
        val scale = minOf((w - pad * 2) / bw, (h - pad * 2) / bh, 8f)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val ox = w / 2f
        val oy = h / 2f

        fun sx(x: Float): Float = ox + (x - cx) * scale
        fun sy(y: Float): Float = oy - (y - cy) * scale   // ось Y вверх

        for (s in segs) {
            linePaint.color = s.color
            linePaint.strokeWidth = (s.width * scale).coerceIn(1f, 40f)
            canvas.drawLine(sx(s.x1), sy(s.y1), sx(s.x2), sy(s.y2), linePaint)
        }

        if (mv) {
            // Стрелка-черепаха: направление heading (0 — вверх, по часовой)
            val rad = Math.toRadians(mh)
            val dx = sin(rad).toFloat()
            val dy = cos(rad).toFloat()
            val size = 11f
            val px = sx(mx)
            val py = sy(my)
            val path = Path()
            path.moveTo(px + dx * size, py - dy * size)
            path.lineTo(px - dx * size * 0.6f - dy * size * 0.55f, py + dy * size * 0.6f - dx * size * 0.55f)
            path.lineTo(px - dx * size * 0.6f + dy * size * 0.55f, py + dy * size * 0.6f + dx * size * 0.55f)
            path.close()
            markerPaint.color = mc
            canvas.drawPath(path, markerPaint)
        }
    }
}
