package com.aivideo.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor

class TimelineRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint().apply { color = Color.parseColor("#181B20") }
    private val trackPaint = Paint().apply { color = Color.parseColor("#2A2E35") }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#353A42")
        strokeWidth = 2f
    }
    private val recPaint = Paint().apply { color = Color.parseColor("#1E88E5") }
    private val eventPaint = Paint().apply { color = Color.parseColor("#FF9800") }
    private val tickPaint = Paint().apply {
        color = Color.parseColor("#8B95A1")
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA4B0")
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }
    private val needlePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 4f
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Zoom levels in total visible minutes across the screen width
    private val zoomWindowsMin = intArrayOf(15, 30, 60, 120, 240)
    private var zoomIndex = 2 // default: 60 min visible

    private var currentEpochSec: Long = System.currentTimeMillis() / 1000L
    private var recordings: List<RecordingItem> = emptyList()
    private var events: List<EventItem> = emptyList()

    private var isDragging = false
    private var lastTouchX = 0f

    var onScrubbing: ((Long) -> Unit)? = null
    var onScrubFinished: ((Long) -> Unit)? = null

    fun setData(recs: List<RecordingItem>, evs: List<EventItem>) {
        recordings = recs
        events = evs
        invalidate()
    }

    fun setCurrentEpochSec(epochSec: Long) {
        if (!isDragging) {
            currentEpochSec = epochSec
            invalidate()
        }
    }

    fun getCurrentEpochSec(): Long = currentEpochSec

    fun zoomIn(): Boolean {
        if (zoomIndex > 0) {
            zoomIndex--
            invalidate()
            return true
        }
        return false
    }

    fun zoomOut(): Boolean {
        if (zoomIndex < zoomWindowsMin.size - 1) {
            zoomIndex++
            invalidate()
            return true
        }
        return false
    }

    private fun secondsPerPixel(): Double {
        val w = width.coerceAtLeast(1)
        return (zoomWindowsMin[zoomIndex] * 60.0) / w.toDouble()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val trackTop = h * 0.48f
        val trackBottom = h * 0.94f
        canvas.drawRect(0f, trackTop, w, trackBottom, trackPaint)

        // Subtle diagonal hatch lines matching DS cam empty track
        canvas.save()
        canvas.clipRect(0f, trackTop, w, trackBottom)
        var hx = -h
        while (hx < w + h) {
            canvas.drawLine(hx, trackBottom, hx + (trackBottom - trackTop), trackTop, hatchPaint)
            hx += 16f
        }
        canvas.restore()

        val spp = secondsPerPixel()
        val centerX = w / 2f
        val leftSec = currentEpochSec - (centerX * spp).toLong()
        val rightSec = currentEpochSec + (centerX * spp).toLong()

        // Draw recordings in blue
        for (rec in recordings) {
            if (rec.endEpochSec < leftSec || rec.startEpochSec > rightSec) continue
            val sx = centerX + ((rec.startEpochSec - currentEpochSec) / spp).toFloat()
            val ex = centerX + ((rec.endEpochSec - currentEpochSec) / spp).toFloat()
            canvas.drawRect(
                sx.coerceAtLeast(0f),
                trackTop,
                ex.coerceAtMost(w).coerceAtLeast(sx + 3f),
                trackBottom,
                recPaint
            )
        }

        // Draw event markers in orange
        for (ev in events) {
            if (ev.epochSec < leftSec || ev.epochSec > rightSec) continue
            val ex = centerX + ((ev.epochSec - currentEpochSec) / spp).toFloat()
            canvas.drawRect(ex - 3f, trackTop, ex + 3f, trackBottom, eventPaint)
        }

        // Draw time ticks & labels
        val majorStepSec = when (zoomWindowsMin[zoomIndex]) {
            15 -> 300L    // 5 min
            30 -> 600L    // 10 min
            60 -> 1800L   // 30 min
            120 -> 3600L  // 1 hour
            else -> 7200L // 2 hours
        }
        val minorStepSec = majorStepSec / 3L

        val firstMinor = floor(leftSec.toDouble() / minorStepSec).toLong() * minorStepSec
        var t = firstMinor
        while (t <= rightSec + minorStepSec) {
            val x = centerX + ((t - currentEpochSec) / spp).toFloat()
            if (x >= -40f && x <= w + 40f) {
                val isMajor = (t % majorStepSec == 0L)
                val tickH = if (isMajor) 14f else 7f
                canvas.drawLine(x, trackTop - tickH, x, trackTop, tickPaint)
                if (isMajor) {
                    val label = synchronized(timeFormat) { timeFormat.format(Date(t * 1000L)) }
                    canvas.drawText(label, x, trackTop - 20f, textPaint)
                }
            }
            t += minorStepSec
        }

        // White center needle
        canvas.drawLine(centerX, trackTop - 8f, centerX, h, needlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                lastTouchX = event.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                val deltaSec = (-dx * secondsPerPixel()).toLong()
                if (deltaSec != 0L) {
                    currentEpochSec += deltaSec
                    val nowSec = System.currentTimeMillis() / 1000L
                    if (currentEpochSec > nowSec) currentEpochSec = nowSec
                    invalidate()
                    onScrubbing?.invoke(currentEpochSec)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onScrubFinished?.invoke(currentEpochSec)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
