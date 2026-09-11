package com.anticolision360.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Core 2.2: corredor de 2 m adaptado y estabilizado sobre el pavimento. */
class OverlayView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var risk = RiskState()
    private var road = RoadGeometry.calibratedStraight()
    private var engineReady = false

    fun update(
        newTracks: List<TrackedObject>,
        newRisk: RiskState,
        newRoad: RoadGeometry,
        ready: Boolean = true
    ) {
        @Suppress("UNUSED_VARIABLE")
        val ignored = newTracks
        risk = newRisk
        road = newRoad
        engineReady = ready
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCorridor(canvas)
        drawTopHud(canvas)
        drawSideAlerts(canvas)
        drawFrontAlert(canvas)
    }

    private fun drawCorridor(canvas: Canvas) {
        if (!road.visible) return
        if (risk.speedKmh != null && risk.speedKmh!! < 5f) return

        val d = resources.displayMetrics.density
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2.0f * d
        val alpha = (115f + road.confidence.coerceIn(0f, 1f) * 95f).toInt()
        paint.color = Color.argb(alpha, 255, 255, 255)
        paint.setShadowLayer(5f * d, 0f, 0f, Color.argb(95, 255, 255, 255))
        setLayerType(LAYER_TYPE_SOFTWARE, paint)

        canvas.drawPath(corridorPath(left = true), paint)
        canvas.drawPath(corridorPath(left = false), paint)

        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun corridorPath(left: Boolean): Path {
        val path = Path()
        val samples = 24
        for (index in 0..samples) {
            val t = index / samples.toFloat()
            val y = road.farY + (road.baseY - road.farY) * t
            val bounds = road.corridorAt(y)
            val x = if (left) bounds.first else bounds.second
            val px = x * width
            val py = y * height
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        return path
    }

    private fun drawTopHud(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val top = 11f * d
        val pad = 11f * d
        val h = 41f * d
        val brandW = min(width * 0.36f, 196f * d)
        val speedW = 78f * d

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(218, 250, 251, 252)
        canvas.drawRoundRect(pad, top, pad + brandW, top + h, 15f * d, 15f * d, paint)
        canvas.drawRoundRect(width - pad - speedW, top, width - pad, top + h, 15f * d, 15f * d, paint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.color = Color.rgb(19, 26, 33)
        textPaint.textSize = 12.4f * d
        canvas.drawText("AntiColisión 360", pad + 11f * d, top + 17f * d, textPaint)

        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        textPaint.color = Color.rgb(105, 115, 125)
        textPaint.textSize = 7.6f * d
        val state = when {
            !engineReady -> "CORE 2.2 · INICIANDO"
            else -> "CORE 2.2 · ACTIVO"
        }
        canvas.drawText(state, pad + 11f * d, top + 31f * d, textPaint)

        val sx = width - pad - speedW
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.color = Color.rgb(20, 27, 34)
        textPaint.textSize = 20f * d
        val speed = risk.speedKmh?.let { max(0, it.toInt()).toString() } ?: "--"
        canvas.drawText(speed, sx + speedW * 0.47f, top + 24f * d, textPaint)

        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        textPaint.textSize = 6.8f * d
        textPaint.color = Color.rgb(105, 115, 125)
        canvas.drawText("KM/H", sx + speedW * 0.47f, top + 34f * d, textPaint)
        textPaint.textAlign = Paint.Align.LEFT

        paint.color = if (engineReady) Color.rgb(34, 181, 115) else Color.rgb(180, 186, 192)
        canvas.drawCircle(width - pad - 8f * d, top + 8f * d, 2.7f * d, paint)
    }

    private fun blinkOn(periodMs: Long): Boolean =
        (System.currentTimeMillis() / periodMs) % 2L == 0L

    private fun drawSideAlerts(canvas: Canvas) {
        val d = resources.displayMetrics.density
        drawEdge(canvas, true, risk.left, d)
        drawEdge(canvas, false, risk.right, d)
    }

    private fun drawEdge(canvas: Canvas, left: Boolean, level: AlertLevel, d: Float) {
        if (level == AlertLevel.NONE) return
        if (!blinkOn(if (level == AlertLevel.RED) 210L else 360L)) return

        val color = levelColor(level)
        val x = if (left) 7f * d else width - 7f * d
        val y1 = height * 0.18f
        val y2 = height * 0.82f

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = if (level == AlertLevel.RED) 6.0f * d else 4.0f * d
        paint.color = color
        paint.setShadowLayer(if (level == AlertLevel.RED) 15f * d else 9f * d, 0f, 0f,
            Color.argb(160, Color.red(color), Color.green(color), Color.blue(color)))
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        canvas.drawLine(x, y1, x, y2, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun drawFrontAlert(canvas: Canvas) {
        if (risk.front == AlertLevel.NONE) return
        if (risk.front == AlertLevel.RED && !risk.frontCritical && !blinkOn(210L)) return
        if (risk.front == AlertLevel.YELLOW && !blinkOn(360L)) return

        val d = resources.displayMetrics.density
        val color = levelColor(risk.front)
        val y = height - 13f * d

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = if (risk.frontCritical) 7.0f * d else if (risk.front == AlertLevel.RED) 5.8f * d else 4.0f * d
        paint.color = color
        paint.setShadowLayer(if (risk.front == AlertLevel.RED) 16f * d else 10f * d, 0f, 0f,
            Color.argb(170, Color.red(color), Color.green(color), Color.blue(color)))
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        canvas.drawLine(width * 0.10f, y, width * 0.90f, y, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun levelColor(level: AlertLevel): Int =
        if (level == AlertLevel.RED) Color.rgb(245, 49, 70) else Color.rgb(245, 183, 0)
}
