package com.anticolision360.app

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tracks: List<TrackedObject> = emptyList()
    private var risk = RiskState()
    private var engineReady = false

    private val vehicles = setOf("car", "truck", "bus", "motorcycle")
    private val vulnerable = setOf("person", "bicycle", "motorcycle", "skateboard", "dog", "cat", "horse", "sheep", "cow", "bear")
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
        val d = resources.displayMetrics.density
        val top = 12f * d
        val left = 12f * d
        val h = 46f * d
        val brandW = min(width * 0.43f, 230f * d)
        val speedW = 94f * d

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(207, 250, 251, 252)
        paint.setShadowLayer(14f * d, 0f, 4f * d, Color.argb(44, 0, 0, 0))
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        canvas.drawRoundRect(left, top, left + brandW, top + h, 17f * d, 17f * d, paint)
        canvas.drawRoundRect(width - left - speedW, top, width - left, top + h, 17f * d, 17f * d, paint)
        paint.clearShadowLayer()

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.color = Color.rgb(18, 25, 32)
        textPaint.textSize = 13.5f * d
        canvas.drawText("AntiColisión 360", left + 12f * d, top + 19f * d, textPaint)
        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        textPaint.color = Color.rgb(99, 111, 122)
        textPaint.textSize = 9f * d
        val subtitle = when {
            !engineReady -> "Inicializando visión"
            risk.parkingMode -> "Maniobra activa"
            (risk.speedKmh ?: 0f) > 30f -> "Vigilancia dinámica"
            else -> "Visión activa"
        }
        canvas.drawText(subtitle, left + 12f * d, top + 34f * d, textPaint)

        val sx = width - left - speedW
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(17, 24, 31)
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.textSize = 23f * d
        val speedText = risk.speedKmh?.let { max(0, it.toInt()).toString() } ?: "--"
        canvas.drawText(speedText, sx + speedW * 0.48f, top + 26f * d, textPaint)
        textPaint.textSize = 7.5f * d
        textPaint.color = Color.rgb(101, 113, 126)
        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        canvas.drawText("KM/H", sx + speedW * 0.48f, top + 38f * d, textPaint)
        textPaint.textAlign = Paint.Align.LEFT

        paint.color = Color.rgb(34, 181, 115)
        canvas.drawCircle(width - left - 10f * d, top + 10f * d, 3.2f * d, paint)
    }

    private fun drawObjects(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val active = mutableSetOf<Int>()
        risk.leftTargetId?.let { active += it }
        risk.rightTargetId?.let { active += it }
        risk.frontTargetId?.let { active += it }

        tracks.forEach { t ->
            if (t.score < 0.29f || t.ageMs < 180L) return@forEach

            val isActive = t.id in active
            val isVru = t.label in vulnerable
            val frontZone = t.centerX in 0.34f..0.66f && t.box.bottom > 0.34f
            val meaningfulMotion = abs(t.closingRate) > 0.0018f
            val passive = !isActive && (
                (isVru && t.area > 0.004f && t.ageMs > 220L) ||
                (frontZone && t.label in vehicles && t.area > 0.008f && meaningfulMotion && t.ageMs > 300L)
            )
            if (!isActive && !passive) return@forEach

            val r = RectF(t.box.left * width, t.box.top * height, t.box.right * width, t.box.bottom * height)
            if (r.width() < 8f * d || r.height() < 8f * d) return@forEach

            val level = when {
                risk.frontTargetId == t.id -> risk.front
                risk.leftTargetId == t.id -> risk.left
                risk.rightTargetId == t.id -> risk.right
                else -> AlertLevel.NONE
            }
            val color = when (level) {
                AlertLevel.RED -> Color.rgb(255, 52, 77)
                AlertLevel.YELLOW -> Color.rgb(246, 184, 0)
                else -> Color.argb(150, 43, 206, 137)
            }

            drawTargetCorners(canvas, r, color, d, isActive)

            if (isActive || (isVru && t.area > 0.012f)) {
                val label = labelMap[t.label] ?: t.label.uppercase()
                val showTtc = isActive && t.ttcSeconds.isFinite() && t.ttcSeconds < 7f
                val text = if (showTtc) "$label  ${"%.1f".format(t.ttcSeconds)} s" else label
                drawObjectLabel(canvas, r, text, color, d, isActive)
            }
        }
    }

    private fun drawTargetCorners(canvas: Canvas, r: RectF, color: Int, d: Float, active: Boolean) {
        val l = min(if (active) 18f * d else 12f * d, min(r.width(), r.height()) * 0.26f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = if (active) 2.8f * d else 1.5f * d
        paint.color = color
        if (active) paint.setShadowLayer(7f * d, 0f, 0f, Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)))
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
        line(r.left, r.top, r.left + l, r.top); line(r.left, r.top, r.left, r.top + l)
        line(r.right, r.top, r.right - l, r.top); line(r.right, r.top, r.right, r.top + l)
        line(r.left, r.bottom, r.left + l, r.bottom); line(r.left, r.bottom, r.left, r.bottom - l)
        line(r.right, r.bottom, r.right - l, r.bottom); line(r.right, r.bottom, r.right, r.bottom - l)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun drawObjectLabel(canvas: Canvas, r: RectF, text: String, color: Int, d: Float, active: Boolean) {
        textPaint.typeface = Typeface.create("sans", if (active) Typeface.BOLD else Typeface.NORMAL)
        textPaint.textSize = if (active) 9f * d else 8f * d
        val tw = textPaint.measureText(text)
        val lh = if (active) 20f * d else 18f * d
        val ly = max(67f * d, r.top - 4f * d)
        val lx = r.left.coerceIn(6f * d, width - tw - 20f * d)
        paint.color = Color.argb(if (active) 225 else 190, 250, 251, 252)
        canvas.drawRoundRect(lx, ly - lh, lx + tw + 16f * d, ly, 8f * d, 8f * d, paint)
        if (active) {
            paint.color = color
            canvas.drawCircle(lx + 7f * d, ly - lh / 2f, 2.4f * d, paint)
        }
        textPaint.color = Color.rgb(24, 31, 38)
        canvas.drawText(text, lx + if (active) 12f * d else 8f * d, ly - 5.5f * d, textPaint)
    }

    private fun drawSideAlerts(canvas: Canvas) {
        if ((risk.speedKmh ?: 0f) <= 30f) return
        val d = resources.displayMetrics.density
        drawEdge(canvas, true, risk.left, d)
        drawEdge(canvas, false, risk.right, d)
    }

    private fun drawEdge(canvas: Canvas, left: Boolean, level: AlertLevel, d: Float) {
        if (level == AlertLevel.NONE) return
        val x = if (left) 7f * d else width - 7f * d
        val y1 = height * 0.22f
        val y2 = height * 0.78f
        val color = if (level == AlertLevel.RED) Color.rgb(255, 52, 77) else Color.rgb(246, 184, 0)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 4.5f * d
        paint.color = color
        paint.setShadowLayer(10f * d, 0f, 0f, Color.argb(135, Color.red(color), Color.green(color), Color.blue(color)))
        canvas.drawLine(x, y1, x, y2, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun drawFrontAlert(canvas: Canvas) {
        if (risk.front == AlertLevel.NONE) return
        val d = resources.displayMetrics.density
        val color = if (risk.front == AlertLevel.RED) Color.rgb(255, 52, 77) else Color.rgb(246, 184, 0)
        val y = height - 14f * d
        val x1 = width * 0.12f
        val x2 = width * 0.88f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 4.2f * d
        paint.color = color
        paint.setShadowLayer(11f * d, 0f, 0f, Color.argb(145, Color.red(color), Color.green(color), Color.blue(color)))
        canvas.drawLine(x1, y, x2, y, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL

        if (risk.frontText.isNotBlank()) {
            textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
            textPaint.textSize = 9.5f * d
            val tw = textPaint.measureText(risk.frontText)
            val boxW = min(width * 0.72f, tw + 34f * d)
            val bx = (width - boxW) / 2f
            val by = y - 31f * d
            paint.color = Color.argb(222, 250, 251, 252)
            canvas.drawRoundRect(bx, by - 26f * d, bx + boxW, by, 13f * d, 13f * d, paint)
            paint.color = color
            canvas.drawCircle(bx + 14f * d, by - 13f * d, 3.5f * d, paint)
            textPaint.color = Color.rgb(28, 34, 40)
            canvas.drawText(risk.frontText, bx + 25f * d, by - 9f * d, textPaint)
        }
    }

    private fun drawParkingMode(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height * 0.59f
        val carW = min(72f * d, width * 0.13f)
        val carH = carW * 1.92f
        val car = RectF(cx - carW / 2f, cy - carH / 2f, cx + carW / 2f, cy + carH / 2f)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(86, 246, 248, 250)
        canvas.drawRoundRect(cx - 105f * d, cy - 145f * d, cx + 105f * d, cy + 145f * d, 30f * d, 30f * d, paint)

        paint.shader = LinearGradient(car.left, car.top, car.right, car.top, intArrayOf(Color.rgb(218,224,230), Color.WHITE, Color.rgb(208,216,222)), null, Shader.TileMode.CLAMP)
        paint.setShadowLayer(16f * d, 0f, 7f * d, Color.argb(65, 0, 0, 0))
        canvas.drawRoundRect(car, 21f * d, 21f * d, paint)
        paint.clearShadowLayer()
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * d
        paint.color = Color.argb(115, 42, 52, 61)
        canvas.drawRoundRect(car, 21f * d, 21f * d, paint)
        paint.style = Paint.Style.FILL

        val glass = RectF(car.left + 12f * d, car.top + 31f * d, car.right - 12f * d, car.bottom - 40f * d)
        paint.color = Color.rgb(27, 35, 42)
        canvas.drawRoundRect(glass, 13f * d, 13f * d, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * d
        for (i in 0..2) {
            val alpha = 115 - i * 25
            paint.color = Color.argb(alpha, 34, 181, 115)
            val spread = carW * (0.78f + i * 0.36f)
            val arcH = 43f * d + i * 16f * d
            val frontArc = RectF(cx - spread, car.top - arcH, cx + spread, car.top + arcH * 0.30f)
            val rearArc = RectF(cx - spread, car.bottom - arcH * 0.30f, cx + spread, car.bottom + arcH)
            canvas.drawArc(frontArc, 205f, 130f, false, paint)
            canvas.drawArc(rearArc, 25f, 130f, false, paint)
        }
        paint.style = Paint.Style.FILL

        val tagW = 104f * d
        val tagY = cy - 176f * d
        paint.color = Color.argb(205, 250, 251, 252)
        canvas.drawRoundRect(cx - tagW / 2f, tagY, cx + tagW / 2f, tagY + 25f * d, 12f * d, 12f * d, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.textSize = 8.5f * d
        textPaint.color = Color.rgb(63, 73, 82)
        canvas.drawText("MODO MANIOBRA", cx, tagY + 16.5f * d, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }
}
