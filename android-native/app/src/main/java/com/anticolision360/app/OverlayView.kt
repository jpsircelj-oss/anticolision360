package com.anticolision360.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Minimal driving UI: quiet in normal driving, edge-only risk alerts. */
class OverlayView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var risk = RiskState()
    private var engineReady = false

    fun update(newTracks: List<TrackedObject>, newRisk: RiskState, ready: Boolean = true) {
        @Suppress("UNUSED_VARIABLE")
        val ignored = newTracks
        risk = newRisk
        engineReady = ready
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTopHud(canvas)
        drawSideAlerts(canvas)
        drawFrontAlert(canvas)
        if (risk.parkingMode) drawParkingMode(canvas)
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
            !engineReady -> "CORE 1.0 · INICIANDO"
            risk.parkingMode -> "CORE 1.0 · MANIOBRA"
            else -> "CORE 1.0 · ACTIVO"
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

    private fun drawSideAlerts(canvas: Canvas) {
        if ((risk.speedKmh ?: 0f) <= 30f) return
        val d = resources.displayMetrics.density
        drawEdge(canvas, true, risk.left, d)
        drawEdge(canvas, false, risk.right, d)
    }

    private fun drawEdge(canvas: Canvas, left: Boolean, level: AlertLevel, d: Float) {
        if (level == AlertLevel.NONE) return
        val color = levelColor(level)
        val x = if (left) 7f * d else width - 7f * d
        val y1 = height * 0.22f
        val y2 = height * 0.78f

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = if (level == AlertLevel.RED) 5.0f * d else 3.8f * d
        paint.color = color
        paint.setShadowLayer(if (level == AlertLevel.RED) 13f * d else 8f * d, 0f, 0f,
            Color.argb(145, Color.red(color), Color.green(color), Color.blue(color)))
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        canvas.drawLine(x, y1, x, y2, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun drawFrontAlert(canvas: Canvas) {
        if (risk.front == AlertLevel.NONE) return
        val d = resources.displayMetrics.density
        val color = levelColor(risk.front)
        val y = height - 13f * d

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = if (risk.front == AlertLevel.RED) 5.2f * d else 3.9f * d
        paint.color = color
        paint.setShadowLayer(if (risk.front == AlertLevel.RED) 14f * d else 9f * d, 0f, 0f,
            Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)))
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        canvas.drawLine(width * 0.13f, y, width * 0.87f, y, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun levelColor(level: AlertLevel): Int =
        if (level == AlertLevel.RED) Color.rgb(245, 49, 70) else Color.rgb(245, 183, 0)

    private fun drawParkingMode(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height * 0.60f
        val carW = min(62f * d, width * 0.115f)
        val carH = carW * 1.92f
        val car = RectF(cx - carW / 2f, cy - carH / 2f, cx + carW / 2f, cy + carH / 2f)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(44, 248, 249, 250)
        canvas.drawRoundRect(cx - 88f * d, cy - 126f * d, cx + 88f * d, cy + 126f * d, 27f * d, 27f * d, paint)

        paint.shader = LinearGradient(
            car.left, car.top, car.right, car.top,
            intArrayOf(Color.rgb(218, 224, 230), Color.WHITE, Color.rgb(208, 216, 222)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(car, 19f * d, 19f * d, paint)
        paint.shader = null

        val glass = RectF(car.left + 10f * d, car.top + 27f * d, car.right - 10f * d, car.bottom - 35f * d)
        paint.color = Color.rgb(29, 37, 44)
        canvas.drawRoundRect(glass, 11f * d, 11f * d, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        for (i in 0..2) {
            paint.color = Color.argb(100 - i * 22, 34, 181, 115)
            val spread = carW * (0.76f + i * 0.34f)
            val arcH = 39f * d + i * 14f * d
            canvas.drawArc(RectF(cx - spread, car.top - arcH, cx + spread, car.top + arcH * 0.30f), 205f, 130f, false, paint)
            canvas.drawArc(RectF(cx - spread, car.bottom - arcH * 0.30f, cx + spread, car.bottom + arcH), 25f, 130f, false, paint)
        }
        paint.style = Paint.Style.FILL
    }
}
