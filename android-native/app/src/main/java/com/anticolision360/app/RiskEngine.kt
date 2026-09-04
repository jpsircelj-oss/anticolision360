package com.anticolision360.app

import android.graphics.RectF
import kotlin.math.abs
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
    val lateralRate: Float,
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
        var previousCenterX: Float,
        var closingRate: Float,
        var lateralRate: Float,
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
                val centerSimilarity = 1f - min(1f, sqrt(dx * dx + dy * dy) / 0.30f)
                val score = max(overlap, centerSimilarity * 0.62f)
                if (score > bestScore && score > 0.22f) {
                    best = track
                    bestScore = score
                }
            }

            if (best == null) {
                val size = sqrt(max(det.box.width() * det.box.height(), 0.000001f))
                tracks += InternalTrack(
                    id = nextId++,
                    label = det.label,
                    score = det.score,
                    box = RectF(det.box),
                    previousSize = size,
                    previousCenterX = det.box.centerX(),
                    closingRate = 0f,
                    lateralRate = 0f,
                    lastSeen = now,
                    born = now
                )
                used += tracks.last().id
            } else {
                val track = best!!
                val dt = ((now - track.lastSeen).coerceAtLeast(1L) / 1000f).coerceAtMost(0.8f)
                val measured = RectF(det.box)
                val smoothed = RectF(
                    track.box.left * 0.46f + measured.left * 0.54f,
                    track.box.top * 0.46f + measured.top * 0.54f,
                    track.box.right * 0.46f + measured.right * 0.54f,
                    track.box.bottom * 0.46f + measured.bottom * 0.54f
                )
                val size = sqrt(max(smoothed.width() * smoothed.height(), 0.000001f))
                val instantClosing = ((size - track.previousSize) / dt).coerceIn(-1.2f, 1.2f)
                val instantLateral = ((smoothed.centerX() - track.previousCenterX) / dt).coerceIn(-1.5f, 1.5f)
                track.closingRate = track.closingRate * 0.78f + instantClosing * 0.22f
                track.lateralRate = track.lateralRate * 0.76f + instantLateral * 0.24f
                track.previousSize = size
                track.previousCenterX = smoothed.centerX()
                track.box = smoothed
                track.score = track.score * 0.40f + det.score * 0.60f
                track.lastSeen = now
                used += track.id
            }
        }

        tracks.removeAll { now - it.lastSeen > 760L }
        return tracks.map {
            TrackedObject(
                id = it.id,
                label = it.label,
                score = it.score,
                box = RectF(it.box),
                closingRate = it.closingRate,
                lateralRate = it.lateralRate,
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
        var bestLeftDanger = 0f
        var bestRightDanger = 0f
        var bestFrontDanger = 0f

        val speed = speedKmh
        val parking = if (speed != null && speed <= 5.0f) {
            if (lowSpeedSince == 0L) lowSpeedSince = now
            now - lowSpeedSince >= 1200L
        } else {
            lowSpeedSince = 0L
            false
        }

        tracks.forEach { t ->
            if (t.ageMs < 220L || t.score < 0.28f) return@forEach

            val x = t.centerX
            val bottom = t.box.bottom
            val width = t.box.width()
            val height = t.box.height()
            val area = t.area
            val closing = t.closingRate
            val ttc = t.ttcSeconds

            // Dynamic trapezoidal driving corridor: narrow in the distance,
            // wider near the hood. This avoids treating parked side vehicles as lead cars.
            val depth = ((bottom - 0.32f) / 0.68f).coerceIn(0f, 1f)
            val halfLane = 0.10f + 0.20f * depth
            val laneLeft = 0.50f - halfLane
            val laneRight = 0.50f + halfLane
            val overlap = max(0f, min(t.box.right, laneRight) - max(t.box.left, laneLeft))
            val overlapRatio = overlap / max(width, 0.001f)
            val stronglyCentral = x in (0.50f - halfLane * 0.68f)..(0.50f + halfLane * 0.68f)
            val geometryPlausible = width < 0.64f && height < 0.78f && area < 0.30f
            val inFrontCorridor = bottom > 0.34f && geometryPlausible && (overlapRatio > 0.34f || stronglyCentral)

            if (inFrontCorridor && t.label in vehicles && t.ageMs >= 420L) {
                // No front alert from distance alone: there must be real convergence.
                val red = (ttc < 2.35f && closing > 0.007f && area > 0.006f) ||
                    (stronglyCentral && area > 0.115f && closing > 0.0042f && t.ageMs > 650L)
                val yellow = red ||
                    (ttc < 4.4f && closing > 0.0042f && area > 0.004f) ||
                    (stronglyCentral && area > 0.065f && closing > 0.0025f && t.ageMs > 650L)

                val level = when {
                    red -> AlertLevel.RED
                    yellow -> AlertLevel.YELLOW
                    else -> AlertLevel.NONE
                }
                if (level != AlertLevel.NONE) {
                    val danger = (if (ttc.isFinite()) (8f - ttc).coerceAtLeast(0f) else 0f) + area * 18f + closing * 55f
                    if (level.ordinal > front.ordinal || (level == front && danger > bestFrontDanger)) {
                        front = level
                        bestFrontDanger = danger
                        frontTargetId = t.id
                        frontText = if (level == AlertLevel.RED) {
                            "Vehículo adelante · cierre rápido"
                        } else {
                            "Vehículo adelante · vigilar distancia"
                        }
                    }
                }
            }

            if (speed != null && speed > 31.5f && t.label in sideRelevant && bottom > 0.40f && t.ageMs >= 280L) {
                val side = when {
                    x < 0.42f -> -1
                    x > 0.58f -> 1
                    else -> 0
                }
                if (side != 0) {
                    val isVulnerable = t.label in vulnerable
                    val movingTowardLane = if (side < 0) t.lateralRate > 0.012f else t.lateralRate < -0.012f
                    val laneBoundary = if (side < 0) laneLeft else laneRight
                    val intrudes = if (side < 0) t.box.right > laneBoundary else t.box.left < laneBoundary
                    val deepIntrusion = if (side < 0) t.box.right > laneLeft + 0.055f else t.box.left < laneRight - 0.055f

                    val level = if (isVulnerable) {
                        val near = area > 0.007f || bottom > 0.63f
                        val red = near && (
                            (movingTowardLane && (area > 0.013f || bottom > 0.72f)) ||
                                (intrudes && ttc < 2.7f && closing > 0.004f)
                            )
                        val yellow = red || (near && (movingTowardLane || intrudes || closing > 0.0045f))
                        when {
                            red -> AlertLevel.RED
                            yellow -> AlertLevel.YELLOW
                            else -> AlertLevel.NONE
                        }
                    } else {
                        // Parked or same-pace side vehicles should not cause red just because
                        // they are visually large. They must actually intrude or converge.
                        val samePace = abs(closing) < 0.0024f && abs(t.lateralRate) < 0.010f
                        val red = deepIntrusion && !samePace && (
                            (ttc < 2.5f && closing > 0.005f) || area > 0.085f
                            )
                        val yellow = red || (intrudes && (
                            area > 0.028f || closing > 0.0045f || movingTowardLane
                            ))
                        when {
                            red -> AlertLevel.RED
                            yellow -> AlertLevel.YELLOW
                            else -> AlertLevel.NONE
                        }
                    }

                    if (level != AlertLevel.NONE) {
                        val danger = area * 18f + abs(t.lateralRate) * 8f + closing.coerceAtLeast(0f) * 40f + if (intrudes) 1.2f else 0f
                        if (side < 0 && (level.ordinal > left.ordinal || (level == left && danger > bestLeftDanger))) {
                            left = level
                            bestLeftDanger = danger
                            leftTargetId = t.id
                        }
                        if (side > 0 && (level.ordinal > right.ordinal || (level == right && danger > bestRightDanger))) {
                            right = level
                            bestRightDanger = danger
                            rightTargetId = t.id
                        }
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
