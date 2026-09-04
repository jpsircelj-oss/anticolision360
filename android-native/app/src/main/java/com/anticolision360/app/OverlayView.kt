package com.anticolision360.app

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tracks: List<TrackedObject> = emptyList()
    private var risk = RiskState()
    private var engineReady = false

    private val labelMap = mapOf(
        "person" to "PEATÓN", "bicycle" to "BICI", "car" to "AUTO",
        "motorcycle" to "MOTO", "bus" to "BUS", "truck" to "CAMIÓN",
        "skateboard" to "PATINETA", "dog" to "PERRO", "cat" to "GATO",
        "horse" to "CABALLO", "sheep" to "ANIMAL", "cow" to "ANIMAL", "bear" to "ANIMAL"
    )

    fun update(newTracks: List<TrackedObject>, newRisk: RiskState, ready: Boolean = true) {
        tracks = newTracks
        risk = newRisk
        engineReady = ready
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTopHud(canvas)
        drawObjects(canvas)
        drawSideAlerts(canvas)
        drawFrontAlert(canvas)
        if (risk.parkingMode) drawParkingMode(canvas)
    }

    private fun drawTopHud(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val pad = 14f * density
        val top = 14f * density
        val left = 14f * density
        val h = 50f * density
        val brandW = min(width * 0.52f, 260f * density)
        val speedW = 112f * density

        paint.color = Color.argb(218, 249, 250, 251)
        paint.setShadowLayer(18f * density, 0f, 5f * density, Color.argb(55, 0, 0, 0))
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        canvas.drawRoundRect(left, top, left + brandW, top + h, 18f * density, 18f * density, paint)
        canvas.drawRoundRect(width - left - speedW, top, width - left, top + h, 18f * density, 18f * density, paint)
        paint.clearShadowLayer()

        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.color = Color.rgb(18, 25, 32)
        textPaint.textSize = 14f * density
        canvas.drawText("AntiColisión 360", left + pad, top + 21f * density, textPaint)
        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        textPaint.color = Color.rgb(101, 113, 126)
        textPaint.textSize = 9.5f * density
        val subtitle = when {
            !engineReady -> "Inicializando IA nativa"
            risk.parkingMode -> "Asistencia de maniobra"
            (risk.speedKmh ?: 0f) > 30f -> "Vigilancia dinámica 360"
            else -> "Visión activa"
        }
        canvas.drawText(subtitle, left + pad, top + 38f * density, textPaint)

        val sx = width - left - speedW
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(17, 24, 31)
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.textSize = 24f * density
        val speedText = risk.speedKmh?.let { max(0, it.toInt()).toString() } ?: "--"
        canvas.drawText(speedText, sx + speedW * 0.48f, top + 29f * density, textPaint)
        textPaint.textSize = 8f * density
        textPaint.color = Color.rgb(101, 113, 126)
        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        canvas.drawText("KM/H", sx + speedW * 0.48f, top + 42f * density, textPaint)
        textPaint.textAlign = Paint.Align.LEFT

        paint.color = Color.rgb(34, 181, 115)
        canvas.drawCircle(width - left - 13f * density, top + 13f * density, 4f * density, paint)
    }

    private fun drawObjects(canvas: Canvas) {
        val d = resources.displayMetrics.density
        tracks.forEach { t ->
            if (t.score < 0.28f || t.ageMs < 120L) return@forEach
            val r = RectF(t.box.left * width, t.box.top * height, t.box.right * width, t.box.bottom * height)
            val side = t.centerX < 0.37f || t.centerX > 0.63f
            val level = when {
                t.ttcSeconds < 2.7f || (side && t.label in setOf("person", "bicycle", "motorcycle", "skateboard")) -> AlertLevel.RED
                t.ttcSeconds < 5.4f || t.closingRate > 0.008f -> AlertLevel.YELLOW
                else -> AlertLevel.NONE
            }
            val color = when (level) {
                AlertLevel.RED -> Color.rgb(255, 52, 77)
                AlertLevel.YELLOW -> Color.rgb(246, 184, 0)
                else -> Color.argb(205, 48, 218, 145)
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (level == AlertLevel.NONE) 1.5f * d else 2.1f * d
            paint.color = color
            canvas.drawRoundRect(r, 11f * d, 11f * d, paint)
            drawCorners(canvas, r, color, d)
            paint.style = Paint.Style.FILL

            val label = labelMap[t.label] ?: t.label.uppercase()
            val text = if (t.ttcSeconds.isFinite() && t.ttcSeconds < 8f) "$label  ${"%.1f".format(t.ttcSeconds)} s" else label
            textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
            textPaint.textSize = 9f * d
            val tw = textPaint.measureText(text)
            val lh = 21f * d
            val ly = max(72f * d, r.top - 5f * d)
            val lx = r.left.coerceIn(6f * d, width - tw - 22f * d)
            paint.color = Color.argb(220, 250, 251, 252)
            canvas.drawRoundRect(lx, ly - lh, lx + tw + 18f * d, ly, 8f * d, 8f * d, paint)
            textPaint.color = Color.rgb(24, 31, 38)
            canvas.drawText(text, lx + 9f * d, ly - 6f * d, textPaint)
        }
    }

    private fun drawCorners(canvas: Canvas, r: RectF, color: Int, d: Float) {
        val l = min(16f * d, min(r.width(), r.height()) * 0.22f)
        paint.strokeWidth = 3f * d
        paint.color = color
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
        line(r.left, r.top, r.left + l, r.top); line(r.left, r.top, r.left, r.top + l)
        line(r.right, r.top, r.right - l, r.top); line(r.right, r.top, r.right, r.top + l)
        line(r.left, r.bottom, r.left + l, r.bottom); line(r.left, r.bottom, r.left, r.bottom - l)
        line(r.right, r.bottom, r.right - l, r.bottom); line(r.right, r.bottom, r.right, r.bottom - l)
    }

    private fun drawSideAlerts(canvas: Canvas) {
        if ((risk.speedKmh ?: 0f) <= 30f) return
        val d = resources.displayMetrics.density
        drawEdge(canvas, true, risk.left, d)
        drawEdge(canvas, false, risk.right, d)
    }

    private fun drawEdge(canvas: Canvas, left: Boolean, level: AlertLevel, d: Float) {
        if (level == AlertLevel.NONE) return
        val x = if (left) 8f * d else width - 8f * d
        val y1 = height * 0.20f
        val y2 = height * 0.80f
        val color = if (level == AlertLevel.RED) Color.rgb(255, 52, 77) else Color.rgb(246, 184, 0)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 6f * d
        paint.color = color
        paint.setShadowLayer(12f * d, 0f, 0f, Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)))
        canvas.drawLine(x, y1, x, y2, paint)
        paint.clearShadowLayer()
    }

    private fun drawFrontAlert(canvas: Canvas) {
        if (risk.front == AlertLevel.NONE) return
        val d = resources.displayMetrics.density
        val color = if (risk.front == AlertLevel.RED) Color.rgb(255, 52, 77) else Color.rgb(246, 184, 0)
        val y = height - 16f * d
        val x1 = width * 0.08f
        val x2 = width * 0.92f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 6f * d
        paint.color = color
        paint.setShadowLayer(14f * d, 0f, 0f, Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)))
        canvas.drawLine(x1, y, x2, y, paint)
        paint.clearShadowLayer()

        if (risk.frontText.isNotBlank()) {
            textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
            textPaint.textSize = 10f * d
            val tw = textPaint.measureText(risk.frontText)
            val boxW = min(width * 0.82f, tw + 34f * d)
            val bx = (width - boxW) / 2f
            val by = y - 40f * d
            paint.color = Color.argb(232, 250, 251, 252)
            canvas.drawRoundRect(bx, by - 30f * d, bx + boxW, by, 14f * d, 14f * d, paint)
            paint.color = color
            canvas.drawCircle(bx + 15f * d, by - 15f * d, 4f * d, paint)
            textPaint.color = Color.rgb(28, 34, 40)
            canvas.drawText(risk.frontText, bx + 27f * d, by - 11f * d, textPaint)
        }
    }

    private fun drawParkingMode(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val panelW = min(width * 0.55f, 310f * d)
        val panelH = min(height * 0.58f, 440f * d)
        val l = (width - panelW) / 2f
        val t = (height - panelH) / 2f + 18f * d
        val r = l + panelW
        val b = t + panelH

        paint.color = Color.argb(225, 249, 250, 251)
        paint.setShadowLayer(24f * d, 0f, 10f * d, Color.argb(70, 0, 0, 0))
        canvas.drawRoundRect(l, t, r, b, 28f * d, 28f * d, paint)
        paint.clearShadowLayer()

        textPaint.color = Color.rgb(27, 34, 41)
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.textSize = 13f * d
        canvas.drawText("Asistencia de maniobra", l + 18f * d, t + 30f * d, textPaint)
        textPaint.color = Color.rgb(102, 113, 124)
        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        textPaint.textSize = 9f * d
        canvas.drawText("Auto centrado · entorno en movimiento", l + 18f * d, t + 47f * d, textPaint)

        val carW = min(82f * d, panelW * 0.28f)
        val carH = carW * 1.92f
        val cx = width / 2f
        val cy = t + panelH * 0.56f
        val car = RectF(cx - carW / 2f, cy - carH / 2f, cx + carW / 2f, cy + carH / 2f)
        paint.shader = LinearGradient(car.left, car.top, car.right, car.top, intArrayOf(Color.rgb(218,224,230), Color.WHITE, Color.rgb(208,216,222)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(car, 24f * d, 24f * d, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.color = Color.argb(120, 42, 52, 61)
        canvas.drawRoundRect(car, 24f * d, 24f * d, paint)
        paint.style = Paint.Style.FILL
        val glass = RectF(car.left + 14f * d, car.top + 36f * d, car.right - 14f * d, car.bottom - 47f * d)
        paint.color = Color.rgb(26, 34, 41)
        canvas.drawRoundRect(glass, 15f * d, 15f * d, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * d
        for (i in 0..2) {
            val alpha = 90 - i * 20
            paint.color = Color.argb(alpha, 34, 181, 115)
            val spread = carW * (0.80f + i * 0.33f)
            val arcH = 48f * d + i * 18f * d
            val frontArc = RectF(cx - spread, car.top - arcH, cx + spread, car.top + arcH * 0.30f)
            val rearArc = RectF(cx - spread, car.bottom - arcH * 0.30f, cx + spread, car.bottom + arcH)
            canvas.drawArc(frontArc, 205f, 130f, false, paint)
            canvas.drawArc(rearArc, 25f, 130f, false, paint)
        }
        paint.style = Paint.Style.FILL

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.textSize = 9f * d
        textPaint.color = Color.rgb(95, 106, 116)
        canvas.drawText("MODO MANIOBRA · ≤ 5 KM/H", cx, b - 20f * d, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }
}
