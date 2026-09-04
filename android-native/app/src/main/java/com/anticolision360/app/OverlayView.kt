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
        drawActiveTargets(canvas)
        drawSideAlerts(canvas)
        drawFrontAlert(canvas)
        if (risk.parkingMode) drawParkingMode(canvas)
    }

    private fun drawTopHud(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val top = 12f * d
        val left = 12f * d
        val h = 44f * d
        val brandW = min(width * 0.39f, 222f * d)
        val speedW = 88f * d

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(202, 249, 250, 251)
        paint.setShadowLayer(12f * d, 0f, 4f * d, Color.argb(38, 0, 0, 0))
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        canvas.drawRoundRect(left, top, left + brandW, top + h, 16f * d, 16f * d, paint)
        canvas.drawRoundRect(width - left - speedW, top, width - left, top + h, 16f * d, 16f * d, paint)
        paint.clearShadowLayer()

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.color = Color.rgb(18, 25, 32)
        textPaint.textSize = 13f * d
        canvas.drawText("AntiColisión 360", left + 12f * d, top + 18f * d, textPaint)

        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        textPaint.color = Color.rgb(97, 108, 119)
        textPaint.textSize = 8.5f * d
        val stateText = when {
            !engineReady -> "V6.2 · Inicializando visión"
            risk.parkingMode -> "V6.2 · Maniobra activa"
            (risk.speedKmh ?: 0f) > 31.5f -> "V6.2 · Vigilancia dinámica"
            else -> "V6.2 · Visión activa"
        }
        canvas.drawText(stateText, left + 12f * d, top + 32f * d, textPaint)

        val sx = width - left - speedW
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(17, 24, 31)
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.textSize = 22f * d
        val speedText = risk.speedKmh?.let { max(0, it.toInt()).toString() } ?: "--"
        canvas.drawText(speedText, sx + speedW * 0.47f, top + 25f * d, textPaint)
        textPaint.textSize = 7.2f * d
        textPaint.color = Color.rgb(101, 113, 126)
        textPaint.typeface = Typeface.create("sans", Typeface.NORMAL)
        canvas.drawText("KM/H", sx + speedW * 0.47f, top + 36f * d, textPaint)
        textPaint.textAlign = Paint.Align.LEFT

        paint.color = Color.rgb(34, 181, 115)
        canvas.drawCircle(width - left - 9f * d, top + 9f * d, 3f * d, paint)
    }

    private fun drawActiveTargets(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val active = linkedSetOf<Int>()
        risk.frontTargetId?.let { active += it }
        risk.leftTargetId?.let { active += it }
        risk.rightTargetId?.let { active += it }
        if (active.isEmpty()) return

        tracks.filter { it.id in active }.forEach { t ->
            val r = RectF(t.box.left * width, t.box.top * height, t.box.right * width, t.box.bottom * height)
            if (r.width() < 8f * d || r.height() < 8f * d) return@forEach

            val level = when (t.id) {
                risk.frontTargetId -> risk.front
                risk.leftTargetId -> risk.left
                risk.rightTargetId -> risk.right
                else -> AlertLevel.NONE
            }
            if (level == AlertLevel.NONE) return@forEach
            val color = if (level == AlertLevel.RED) Color.rgb(255, 52, 77) else Color.rgb(246, 184, 0)
            drawTargetCorners(canvas, r, color, d)

            val label = labelMap[t.label] ?: t.label.uppercase()
            val text = if (t.ttcSeconds.isFinite() && t.ttcSeconds < 7f) {
                "$label  ${"%.1f".format(t.ttcSeconds)} s"
            } else label
            drawTargetLabel(canvas, r, text, color, d)
        }
    }

    private fun drawTargetCorners(canvas: Canvas, r: RectF, color: Int, d: Float) {
        val l = min(18f * d, min(r.width(), r.height()) * 0.25f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2.6f * d
        paint.color = color
        paint.setShadowLayer(7f * d, 0f, 0f, Color.argb(92, Color.red(color), Color.green(color), Color.blue(color)))
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
        line(r.left, r.top, r.left + l, r.top); line(r.left, r.top, r.left, r.top + l)
        line(r.right, r.top, r.right - l, r.top); line(r.right, r.top, r.right, r.top + l)
        line(r.left, r.bottom, r.left + l, r.bottom); line(r.left, r.bottom, r.left, r.bottom - l)
        line(r.right, r.bottom, r.right - l, r.bottom); line(r.right, r.bottom, r.right, r.bottom - l)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun drawTargetLabel(canvas: Canvas, r: RectF, text: String, color: Int, d: Float) {
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.textSize = 8.7f * d
        val tw = textPaint.measureText(text)
        val lh = 19f * d
        val ly = max(64f * d, r.top - 5f * d)
        val lx = r.left.coerceIn(6f * d, width - tw - 22f * d)
        paint.color = Color.argb(220, 250, 251, 252)
        canvas.drawRoundRect(lx, ly - lh, lx + tw + 19f * d, ly, 8f * d, 8f * d, paint)
        paint.color = color
        canvas.drawCircle(lx + 7f * d, ly - lh / 2f, 2.5f * d, paint)
        textPaint.color = Color.rgb(24, 31, 38)
        canvas.drawText(text, lx + 12f * d, ly - 5.2f * d, textPaint)
    }

    private fun drawSideAlerts(canvas: Canvas) {
        if ((risk.speedKmh ?: 0f) <= 31.5f) return
        val d = resources.displayMetrics.density
        drawEdge(canvas, true, risk.left, d)
        drawEdge(canvas, false, risk.right, d)
    }

    private fun drawEdge(canvas: Canvas, left: Boolean, level: AlertLevel, d: Float) {
        if (level == AlertLevel.NONE) return
        val x = if (left) 7f * d else width - 7f * d
        val y1 = height * 0.24f
        val y2 = height * 0.76f
        val color = if (level == AlertLevel.RED) Color.rgb(255, 52, 77) else Color.rgb(246, 184, 0)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 4.2f * d
        paint.color = color
        paint.setShadowLayer(9f * d, 0f, 0f, Color.argb(125, Color.red(color), Color.green(color), Color.blue(color)))
        canvas.drawLine(x, y1, x, y2, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun drawFrontAlert(canvas: Canvas) {
        if (risk.front == AlertLevel.NONE) return
        val d = resources.displayMetrics.density
        val color = if (risk.front == AlertLevel.RED) Color.rgb(255, 52, 77) else Color.rgb(246, 184, 0)
        val y = height - 13f * d
        val x1 = width * 0.14f
        val x2 = width * 0.86f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 4f * d
        paint.color = color
        paint.setShadowLayer(10f * d, 0f, 0f, Color.argb(135, Color.red(color), Color.green(color), Color.blue(color)))
        canvas.drawLine(x1, y, x2, y, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL

        if (risk.frontText.isNotBlank()) {
            textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
            textPaint.textSize = 9.2f * d
            val tw = textPaint.measureText(risk.frontText)
            val boxW = min(width * 0.69f, tw + 34f * d)
            val bx = (width - boxW) / 2f
            val by = y - 29f * d
            paint.color = Color.argb(218, 250, 251, 252)
            canvas.drawRoundRect(bx, by - 25f * d, bx + boxW, by, 13f * d, 13f * d, paint)
            paint.color = color
            canvas.drawCircle(bx + 13f * d, by - 12.5f * d, 3.3f * d, paint)
            textPaint.color = Color.rgb(28, 34, 40)
            canvas.drawText(risk.frontText, bx + 24f * d, by - 8.5f * d, textPaint)
        }
    }

    private fun drawParkingMode(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height * 0.60f
        val carW = min(66f * d, width * 0.12f)
        val carH = carW * 1.92f
        val car = RectF(cx - carW / 2f, cy - carH / 2f, cx + carW / 2f, cy + carH / 2f)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(58, 248, 249, 250)
        canvas.drawRoundRect(cx - 94f * d, cy - 132f * d, cx + 94f * d, cy + 132f * d, 28f * d, 28f * d, paint)

        paint.shader = LinearGradient(car.left, car.top, car.right, car.top, intArrayOf(Color.rgb(218,224,230), Color.WHITE, Color.rgb(208,216,222)), null, Shader.TileMode.CLAMP)
        paint.setShadowLayer(14f * d, 0f, 6f * d, Color.argb(58, 0, 0, 0))
        canvas.drawRoundRect(car, 20f * d, 20f * d, paint)
        paint.clearShadowLayer()
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * d
        paint.color = Color.argb(105, 42, 52, 61)
        canvas.drawRoundRect(car, 20f * d, 20f * d, paint)
        paint.style = Paint.Style.FILL

        val glass = RectF(car.left + 11f * d, car.top + 29f * d, car.right - 11f * d, car.bottom - 37f * d)
        paint.color = Color.rgb(27, 35, 42)
        canvas.drawRoundRect(glass, 12f * d, 12f * d, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f * d
        for (i in 0..2) {
            paint.color = Color.argb(108 - i * 23, 34, 181, 115)
            val spread = carW * (0.78f + i * 0.36f)
            val arcH = 41f * d + i * 15f * d
            canvas.drawArc(RectF(cx - spread, car.top - arcH, cx + spread, car.top + arcH * 0.30f), 205f, 130f, false, paint)
            canvas.drawArc(RectF(cx - spread, car.bottom - arcH * 0.30f, cx + spread, car.bottom + arcH), 25f, 130f, false, paint)
        }
        paint.style = Paint.Style.FILL

        val tagW = 103f * d
        val tagY = cy - 157f * d
        paint.color = Color.argb(198, 250, 251, 252)
        canvas.drawRoundRect(cx - tagW / 2f, tagY, cx + tagW / 2f, tagY + 23f * d, 12f * d, 12f * d, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        textPaint.textSize = 8f * d
        textPaint.color = Color.rgb(72, 82, 91)
        canvas.drawText("MANIOBRA · ≤ 5 KM/H", cx, tagY + 15f * d, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }
}
