package com.anticolision360.app

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class AlertLevel { NONE, YELLOW, RED }

data class RawDetection(
    val label: String,
    val score: Float,
    val box: RectF
)

data class TrackedObject(
    val id: Int,
    val label: String,
    val score: Float,
    val box: RectF,
    val closingRate: Float,
    val ageMs: Long
) {
    val centerX: Float get() = box.centerX()
    val centerY: Float get() = box.centerY()
    val area: Float get() = max(0f, box.width()) * max(0f, box.height())
    val ttcSeconds: Float
        get() {
            val size = sqrt(max(area, 0.000001f))
            return if (closingRate > 0.006f) size / closingRate else Float.POSITIVE_INFINITY
        }
}

data class RiskState(
    val left: AlertLevel = AlertLevel.NONE,
    val right: AlertLevel = AlertLevel.NONE,
    val front: AlertLevel = AlertLevel.NONE,
    val frontText: String = "",
    val parkingMode: Boolean = false,
    val speedKmh: Float? = null,
    val leftTargetId: Int? = null,
    val rightTargetId: Int? = null,
    val frontTargetId: Int? = null
)

class MotionTracker {
    private data class InternalTrack(
        val id: Int,
        var label: String,
        var score: Float,
        var box: RectF,
        var previousSize: Float,
        var closingRate: Float,
        var lastSeen: Long,
        var born: Long
    )

    private val tracks = mutableListOf<InternalTrack>()
    private var nextId = 1

    fun update(detections: List<RawDetection>, now: Long): List<TrackedObject> {
        val used = mutableSetOf<Int>()

        detections.sortedByDescending { it.score }.forEach { det ->
            var best: InternalTrack? = null
            var bestScore = 0f
            tracks.forEach { track ->
                if (track.id in used || track.label != det.label) return@forEach
                val overlap = iou(track.box, det.box)
                val dx = track.box.centerX() - det.box.centerX()
                val dy = track.box.centerY() - det.box.centerY()
                val centerSimilarity = 1f - min(1f, sqrt(dx * dx + dy * dy) / 0.32f)
                val score = max(overlap, centerSimilarity * 0.58f)
                if (score > bestScore && score > 0.20f) {
                    best = track
                    bestScore = score
                }
            }

            if (best == null) {
                val size = sqrt(max(det.box.width() * det.box.height(), 0.000001f))
                tracks += InternalTrack(
                    id = nextId++, label = det.label, score = det.score,
                    box = RectF(det.box), previousSize = size,
                    closingRate = 0f, lastSeen = now, born = now
                )
                used += tracks.last().id
            } else {
                val track = best!!
                val dt = ((now - track.lastSeen).coerceAtLeast(1L) / 1000f).coerceAtMost(0.8f)
                val measured = RectF(det.box)
                val smoothed = RectF(
                    track.box.left * 0.42f + measured.left * 0.58f,
                    track.box.top * 0.42f + measured.top * 0.58f,
                    track.box.right * 0.42f + measured.right * 0.58f,
                    track.box.bottom * 0.42f + measured.bottom * 0.58f
                )
                val size = sqrt(max(smoothed.width() * smoothed.height(), 0.000001f))
                val instantClosing = ((size - track.previousSize) / dt).coerceIn(-1.2f, 1.2f)
                track.closingRate = track.closingRate * 0.74f + instantClosing * 0.26f
                track.previousSize = size
                track.box = smoothed
                track.score = track.score * 0.35f + det.score * 0.65f
                track.lastSeen = now
                used += track.id
            }
        }

        tracks.removeAll { now - it.lastSeen > 800L }
        return tracks.map {
            TrackedObject(
                id = it.id,
                label = it.label,
                score = it.score,
                box = RectF(it.box),
                closingRate = it.closingRate,
                ageMs = now - it.born
            )
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        val intersection = max(0f, r - l) * max(0f, bot - t)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }
}

class RiskEngine {
    private val vehicles = setOf("car", "truck", "bus", "motorcycle")
    private val sideRelevant = setOf(
        "car", "truck", "bus", "motorcycle", "bicycle", "person", "skateboard",
        "dog", "cat", "horse", "sheep", "cow", "bear"
    )
    private val vulnerable = setOf(
        "motorcycle", "bicycle", "person", "skateboard", "dog", "cat", "horse", "sheep", "cow", "bear"
    )

    private var lowSpeedSince = 0L

    fun evaluate(tracks: List<TrackedObject>, speedKmh: Float?, now: Long): RiskState {
        var left = AlertLevel.NONE
        var right = AlertLevel.NONE
        var front = AlertLevel.NONE
        var frontText = ""
        var leftTargetId: Int? = null
        var rightTargetId: Int? = null
        var frontTargetId: Int? = null

        val speed = speedKmh
        val parking = if (speed != null && speed <= 5.0f) {
            if (lowSpeedSince == 0L) lowSpeedSince = now
            now - lowSpeedSince >= 1200L
        } else {
            lowSpeedSince = 0L
            false
        }

        tracks.forEach { t ->
            if (t.ageMs < 160L || t.score < 0.27f) return@forEach

            val x = t.centerX
            val bottom = t.box.bottom
            val width = t.box.width()
            val height = t.box.height()
            val stronglyCentral = x in 0.42f..0.58f
            val frontBand = if (width > 0.46f) 0.43f..0.57f else 0.36f..0.64f
            val geometryPlausible = width < 0.70f && height < 0.82f
            val inFrontCorridor = x in frontBand && bottom > 0.34f && geometryPlausible

            if (inFrontCorridor && t.label in vehicles && t.ageMs >= 250L) {
                val ttc = t.ttcSeconds
                val closing = t.closingRate
                val speedFactor = ((speed ?: 30f) / 100f).coerceIn(0.15f, 1.30f)
                val redArea = 0.105f - 0.022f * speedFactor
                val yellowArea = 0.050f - 0.008f * speedFactor

                val red = (ttc < 2.6f && closing > 0.005f) ||
                    (stronglyCentral && t.area > redArea && closing > 0.0020f && t.ageMs > 420L)
                val yellow = red ||
                    (ttc < 5.2f && closing > 0.003f) ||
                    (stronglyCentral && t.area > yellowArea && closing > 0.0015f && t.ageMs > 420L)

                val candidate = when {
                    red -> AlertLevel.RED
                    yellow -> AlertLevel.YELLOW
                    else -> AlertLevel.NONE
                }

                if (candidate.ordinal > front.ordinal) {
                    front = candidate
                    frontTargetId = t.id
                    frontText = when (candidate) {
                        AlertLevel.RED -> "Vehículo adelante · cierre rápido"
                        AlertLevel.YELLOW -> "Vehículo adelante · vigilar distancia"
                        else -> ""
                    }
                }
            }

            if (speed != null && speed > 30f && t.label in sideRelevant && bottom > 0.40f && t.ageMs >= 220L) {
                val side = when {
                    x < 0.36f -> -1
                    x > 0.64f -> 1
                    else -> 0
                }
                if (side != 0) {
                    val isVulnerable = t.label in vulnerable
                    val intrudesTowardLane = if (side < 0) t.box.right > 0.36f else t.box.left < 0.64f
                    val nearEnough = t.area > if (isVulnerable) 0.006f else 0.018f
                    val closingEnough = t.closingRate > if (isVulnerable) 0.0025f else 0.0045f
                    val urgent = when {
                        isVulnerable -> nearEnough && (t.ttcSeconds < 3.6f || t.area > 0.025f || closingEnough)
                        else -> intrudesTowardLane && (t.ttcSeconds < 3.0f || t.area > 0.060f || closingEnough)
                    }
                    val caution = when {
                        isVulnerable -> nearEnough || closingEnough
                        else -> intrudesTowardLane && (nearEnough || closingEnough)
                    }
                    val level = when {
                        urgent -> AlertLevel.RED
                        caution -> AlertLevel.YELLOW
                        else -> AlertLevel.NONE
                    }
                    if (side < 0 && level.ordinal > left.ordinal) {
                        left = level
                        leftTargetId = if (level == AlertLevel.NONE) null else t.id
                    }
                    if (side > 0 && level.ordinal > right.ordinal) {
                        right = level
                        rightTargetId = if (level == AlertLevel.NONE) null else t.id
                    }
                }
            }
        }

        return RiskState(
            left = left,
            right = right,
            front = front,
            frontText = frontText,
            parkingMode = parking,
            speedKmh = speedKmh,
            leftTargetId = leftTargetId,
            rightTargetId = rightTargetId,
            frontTargetId = frontTargetId
        )
    }
}
