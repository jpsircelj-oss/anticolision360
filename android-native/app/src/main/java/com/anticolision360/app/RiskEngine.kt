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
    val distanceMeters: Float,
    val closingMps: Float,
    val ageMs: Long,
    val hitCount: Int,
    val lastSeenAgeMs: Long,
    val stability: Float
) {
    val centerX: Float get() = box.centerX()
    val centerY: Float get() = box.centerY()
    val area: Float get() = max(0f, box.width()) * max(0f, box.height())
    val visualSize: Float get() = sqrt(max(area, 0.000001f))
    val aspectRatio: Float get() = box.width() / max(box.height(), 0.001f)
    val profileLike: Boolean get() = label in setOf("car", "truck", "bus") && aspectRatio >= 1.55f
    val roadVehicle: Boolean get() = label in setOf("car", "truck", "bus", "motorcycle")
    val confirmed: Boolean
        get() = hitCount >= 4 && ageMs >= 300L && lastSeenAgeMs <= 260L && stability >= 0.48f

    val ttcSeconds: Float
        get() = when {
            closingMps > 0.25f -> distanceMeters / closingMps
            closingRate > 0.0040f -> visualSize / closingRate
            else -> Float.POSITIVE_INFINITY
        }
}

data class RiskState(
    val left: AlertLevel = AlertLevel.NONE,
    val right: AlertLevel = AlertLevel.NONE,
    val front: AlertLevel = AlertLevel.NONE,
    val frontCritical: Boolean = false,
    val yellowAudible: Boolean = false,
    val parkingMode: Boolean = false,
    val speedKmh: Float? = null,
    val leftTargetId: Int? = null,
    val rightTargetId: Int? = null,
    val frontTargetId: Int? = null
)

class MotionTracker {

    private data class InternalTrack(
        val id: Int,
        var box: RectF,
        var score: Float,
        var previousSize: Float,
        var previousCenterX: Float,
        var previousDistance: Float,
        var closingRate: Float,
        var lateralRate: Float,
        var closingMps: Float,
        var lastSeen: Long,
        val born: Long,
        var hitCount: Int,
        val votes: MutableMap<String, Float>
    )

    private val tracks = mutableListOf<InternalTrack>()
    private var nextId = 1

    fun update(detections: List<RawDetection>, now: Long): List<TrackedObject> {
        val used = mutableSetOf<Int>()

        detections.sortedByDescending { it.score }.forEach { det ->
            val best = tracks.asSequence()
                .filter { it.id !in used && compatible(stableLabel(it), det.label) }
                .map { it to matchScore(it.box, det.box) }
                .filter { it.second >= 0.24f }
                .maxByOrNull { it.second }
                ?.first

            if (best == null) {
                val size = sqrt(max(det.box.width() * det.box.height(), 0.000001f))
                val distance = estimateDistanceMeters(det.label, det.box)
                val track = InternalTrack(
                    id = nextId++,
                    box = RectF(det.box),
                    score = det.score,
                    previousSize = size,
                    previousCenterX = det.box.centerX(),
                    previousDistance = distance,
                    closingRate = 0f,
                    lateralRate = 0f,
                    closingMps = 0f,
                    lastSeen = now,
                    born = now,
                    hitCount = 1,
                    votes = mutableMapOf(det.label to det.score)
                )
                tracks += track
                used += track.id
            } else {
                val dt = ((now - best.lastSeen).coerceAtLeast(1L) / 1000f).coerceIn(0.03f, 0.55f)
                val measured = det.box
                val alpha = if (best.hitCount < 3) 0.68f else 0.48f
                val smoothed = RectF(
                    best.box.left * (1f - alpha) + measured.left * alpha,
                    best.box.top * (1f - alpha) + measured.top * alpha,
                    best.box.right * (1f - alpha) + measured.right * alpha,
                    best.box.bottom * (1f - alpha) + measured.bottom * alpha
                )

                val size = sqrt(max(smoothed.width() * smoothed.height(), 0.000001f))
                val instantClosing = ((size - best.previousSize) / dt).coerceIn(-0.65f, 0.65f)
                val instantLateral =
                    ((smoothed.centerX() - best.previousCenterX) / dt).coerceIn(-0.80f, 0.80f)
                val label = stableLabel(best)
                val distance = estimateDistanceMeters(label, smoothed)
                val instantClosingMps = ((best.previousDistance - distance) / dt).coerceIn(-35f, 35f)

                best.closingRate = best.closingRate * 0.84f + instantClosing * 0.16f
                best.lateralRate = best.lateralRate * 0.82f + instantLateral * 0.18f
                best.closingMps = best.closingMps * 0.82f + instantClosingMps * 0.18f
                best.previousSize = size
                best.previousCenterX = smoothed.centerX()
                best.previousDistance = distance
                best.box = smoothed
                best.score = best.score * 0.45f + det.score * 0.55f
                best.lastSeen = now
                best.hitCount += 1
                best.votes[det.label] = (best.votes[det.label] ?: 0f) + det.score
                used += best.id
            }
        }

        tracks.removeAll { now - it.lastSeen > 650L }

        return tracks.map { t ->
            val age = now - t.born
            val seenAge = now - t.lastSeen
            val hitFactor = (t.hitCount / 7f).coerceIn(0f, 1f)
            val ageFactor = (age / 700f).coerceIn(0f, 1f)
            val freshness = (1f - seenAge / 650f).coerceIn(0f, 1f)
            val stability = (
                0.35f * hitFactor +
                    0.25f * ageFactor +
                    0.25f * t.score.coerceIn(0f, 1f) +
                    0.15f * freshness
                ).coerceIn(0f, 1f)
            val label = stableLabel(t)

            TrackedObject(
                id = t.id,
                label = label,
                score = t.score,
                box = RectF(t.box),
                closingRate = t.closingRate,
                lateralRate = t.lateralRate,
                distanceMeters = estimateDistanceMeters(label, t.box),
                closingMps = t.closingMps,
                ageMs = age,
                hitCount = t.hitCount,
                lastSeenAgeMs = seenAge,
                stability = stability
            )
        }
    }

    private fun estimateDistanceMeters(label: String, box: RectF): Float {
        val h = box.height().coerceAtLeast(0.018f)
        val reference = when (label) {
            "truck", "bus" -> 2.55f
            "person" -> 1.72f
            "motorcycle", "bicycle", "skateboard" -> 1.55f
            "horse", "cow" -> 1.85f
            else -> 2.00f
        }
        return (reference / h).coerceIn(1.5f, 120f)
    }

    private fun stableLabel(track: InternalTrack): String =
        track.votes.maxByOrNull { it.value }?.key ?: "unknown"

    private fun compatible(a: String, b: String): Boolean {
        if (a == b) return true
        return family(a) == family(b) && family(a) != "other"
    }

    private fun family(label: String): String = when (label) {
        "car", "truck", "bus" -> "vehicle"
        "motorcycle", "bicycle", "person", "skateboard" -> "vru"
        "dog", "cat", "horse", "sheep", "cow", "bear" -> "animal"
        else -> "other"
    }

    private fun matchScore(a: RectF, b: RectF): Float {
        val overlap = iou(a, b)
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        val distance = sqrt(dx * dx + dy * dy)
        val center = 1f - min(1f, distance / 0.28f)
        return max(overlap, center * 0.64f)
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bottom - t)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }
}

class RiskEngine {

    private val relevant = setOf(
        "car", "truck", "bus", "motorcycle", "bicycle", "person", "skateboard",
        "dog", "cat", "horse", "sheep", "cow", "bear"
    )

    private val vulnerable = setOf(
        "motorcycle", "bicycle", "person", "skateboard",
        "dog", "cat", "horse", "sheep", "cow", "bear"
    )

    private data class Candidate(
        val level: AlertLevel,
        val targetId: Int?,
        val danger: Float,
        val critical: Boolean = false,
        val audible: Boolean = true
    )

    private class ZoneLatch {
        var level: AlertLevel = AlertLevel.NONE
        private var yellowFrames = 0
        private var redFrames = 0
        private var lastEvidenceAt = 0L

        fun update(candidate: AlertLevel, now: Long): AlertLevel {
            if (candidate != AlertLevel.NONE) lastEvidenceAt = now
            yellowFrames = if (candidate != AlertLevel.NONE) (yellowFrames + 1).coerceAtMost(8) else 0
            redFrames = if (candidate == AlertLevel.RED) (redFrames + 1).coerceAtMost(8) else 0

            level = when {
                redFrames >= 2 -> AlertLevel.RED
                yellowFrames >= 2 && level != AlertLevel.RED -> AlertLevel.YELLOW
                candidate == AlertLevel.YELLOW && level == AlertLevel.RED -> AlertLevel.YELLOW
                candidate == AlertLevel.NONE && now - lastEvidenceAt > 450L -> AlertLevel.NONE
                else -> level
            }
            return level
        }
    }

    private val leftLatch = ZoneLatch()
    private val rightLatch = ZoneLatch()
    private val frontLatch = ZoneLatch()
    private var lowSpeedSince = 0L

    fun evaluate(
        tracks: List<TrackedObject>,
        speedKmh: Float?,
        now: Long,
        road: RoadGeometry = RoadGeometry.calibratedStraight()
    ): RiskState {
        val speed = speedKmh ?: 0f
        val parking = if (speedKmh != null && speed <= 5f) {
            if (lowSpeedSince == 0L) lowSpeedSince = now
            now - lowSpeedSince >= 1200L
        } else {
            lowSpeedSince = 0L
            false
        }

        var frontCandidate = Candidate(AlertLevel.NONE, null, 0f, audible = false)
        var leftCandidate = Candidate(AlertLevel.NONE, null, 0f, audible = false)
        var rightCandidate = Candidate(AlertLevel.NONE, null, 0f, audible = false)

        for (t in tracks) {
            if (!t.confirmed || t.label !in relevant) continue

            val corridor = road.corridorAt(t.box.bottom)
            val corridorLeft = corridor.first
            val corridorRight = corridor.second
            val halfCorridor = (corridorRight - corridorLeft) * 0.5f
            val corridorCenter = (corridorLeft + corridorRight) * 0.5f

            val overlap = max(0f, min(t.box.right, corridorRight) - max(t.box.left, corridorLeft))
            val overlapRatio = overlap / max(t.box.width(), 0.001f)
            val insideCorridor = overlapRatio > 0.34f || t.centerX in corridorLeft..corridorRight
            val stronglyCentral = t.centerX in
                (corridorCenter - halfCorridor * 0.62f)..(corridorCenter + halfCorridor * 0.62f)

            if (insideCorridor) {
                val c = frontRisk(t, speed, stronglyCentral)
                if (better(c, frontCandidate)) frontCandidate = c
            }

            val side = if (t.centerX <= corridorCenter) -1 else 1
            val sideCandidate = sideRisk(
                t = t,
                side = side,
                corridorLeft = corridorLeft,
                corridorRight = corridorRight,
                insideCorridor = insideCorridor,
                speed = speed
            )
            if (side < 0 && better(sideCandidate, leftCandidate)) leftCandidate = sideCandidate
            if (side > 0 && better(sideCandidate, rightCandidate)) rightCandidate = sideCandidate
            if (sideCandidate.critical && better(sideCandidate, frontCandidate)) frontCandidate = sideCandidate
        }

        val front = frontLatch.update(frontCandidate.level, now)
        val left = leftLatch.update(leftCandidate.level, now)
        val right = rightLatch.update(rightCandidate.level, now)

        val yellowAudible =
            (front == AlertLevel.YELLOW && frontCandidate.audible) ||
                (left == AlertLevel.YELLOW && leftCandidate.audible) ||
                (right == AlertLevel.YELLOW && rightCandidate.audible)

        return RiskState(
            left = left,
            right = right,
            front = front,
            frontCritical = front == AlertLevel.RED && frontCandidate.critical,
            yellowAudible = yellowAudible,
            parkingMode = parking,
            speedKmh = speedKmh,
            leftTargetId = if (left != AlertLevel.NONE) leftCandidate.targetId else null,
            rightTargetId = if (right != AlertLevel.NONE) rightCandidate.targetId else null,
            frontTargetId = if (front != AlertLevel.NONE) frontCandidate.targetId else null
        )
    }

    private fun frontRisk(t: TrackedObject, speed: Float, stronglyCentral: Boolean): Candidate {
        val d = t.distanceMeters
        val relativeClosing = t.closingMps

        // Vehículo visto de perfil dentro del corredor: sólo precaución amarilla audible.
        // Esta condición nunca genera línea roja inferior ni alarma fuerte.
        if (t.profileLike) {
            return if (d <= 70f) {
                Candidate(AlertLevel.YELLOW, t.id, 44f - d * 0.20f, audible = true)
            } else {
                Candidate(AlertLevel.NONE, null, 0f, audible = false)
            }
        }

        if (d < 5.0f) return Candidate(AlertLevel.RED, t.id, 100f - d, critical = true)
        if (d < 6.0f) return Candidate(AlertLevel.RED, t.id, 80f - d)
        if (d < 8.0f) return Candidate(AlertLevel.YELLOW, t.id, 60f - d)

        val samePace = abs(relativeClosing) < 0.65f && abs(t.closingRate) < 0.0025f
        if (samePace && d >= 8f) return Candidate(AlertLevel.NONE, null, 0f, audible = false)

        val yellowTtc = when {
            speed >= 100f -> 5.8f
            speed >= 60f -> 5.0f
            else -> 4.2f
        }
        val redTtc = when {
            speed >= 100f -> 2.8f
            speed >= 60f -> 2.5f
            else -> 2.2f
        }

        val red = relativeClosing > 1.0f && t.ttcSeconds < redTtc && (stronglyCentral || d < 18f)
        val yellow = red || (relativeClosing > 0.55f && t.ttcSeconds < yellowTtc)

        return when {
            red -> Candidate(AlertLevel.RED, t.id, 30f - t.ttcSeconds)
            yellow -> Candidate(AlertLevel.YELLOW, t.id, 20f - t.ttcSeconds)
            t.label in vulnerable && d < 12f -> Candidate(AlertLevel.YELLOW, t.id, 15f - d)
            else -> Candidate(AlertLevel.NONE, null, 0f, audible = false)
        }
    }

    private fun sideRisk(
        t: TrackedObject,
        side: Int,
        corridorLeft: Float,
        corridorRight: Float,
        insideCorridor: Boolean,
        speed: Float
    ): Candidate {
        // Si un auto/camión/bus se ve de perfil dentro de las líneas, mantener sólo
        // precaución amarilla con sonido suave; nunca escalar a rojo por esta condición.
        if (insideCorridor && t.profileLike && t.distanceMeters <= 70f) {
            return Candidate(
                AlertLevel.YELLOW,
                t.id,
                38f - t.distanceMeters * 0.15f,
                critical = false,
                audible = true
            )
        }

        val boundary = if (side < 0) corridorLeft else corridorRight
        val nearEdge = if (side < 0) t.box.right else t.box.left
        val touchesBoundary = if (side < 0) t.box.right >= boundary else t.box.left <= boundary
        val towardCorridor = if (side < 0) t.lateralRate > 0.006f else t.lateralRate < -0.006f

        val lookAheadSeconds = when {
            t.distanceMeters > 45f -> 2.3f
            t.distanceMeters > 20f -> 1.8f
            else -> 1.25f
        }
        val projectedNearEdge = nearEdge + t.lateralRate * lookAheadSeconds
        val projectedCross = if (side < 0) {
            projectedNearEdge >= corridorLeft
        } else {
            projectedNearEdge <= corridorRight
        }

        val boundaryGap = abs(nearEdge - boundary)
        val lateralSpeed = abs(t.lateralRate).coerceAtLeast(0.0001f)
        val lateralTtc = boundaryGap / lateralSpeed
        val closeEnough = t.distanceMeters <= if (speed >= 90f) 65f else 50f

        if (touchesBoundary && !towardCorridor && !projectedCross) {
            return Candidate(AlertLevel.YELLOW, t.id, 26f - t.distanceMeters * 0.10f, audible = true)
        }

        if (towardCorridor && projectedCross && closeEnough) {
            val critical = lateralTtc < 0.85f || (insideCorridor && t.distanceMeters < 5f)
            return Candidate(
                AlertLevel.RED,
                t.id,
                50f - lateralTtc.coerceAtMost(20f) + if (critical) 30f else 0f,
                critical = critical,
                audible = true
            )
        }

        if (!insideCorridor && t.profileLike && t.distanceMeters <= 70f && !projectedCross) {
            return Candidate(AlertLevel.YELLOW, t.id, 21f - t.distanceMeters * 0.08f, audible = false)
        }

        if (t.label in vulnerable && closeEnough && (touchesBoundary || towardCorridor)) {
            return Candidate(AlertLevel.YELLOW, t.id, 22f - t.distanceMeters * 0.10f, audible = true)
        }

        val parallelSameDirection =
            t.roadVehicle &&
                !insideCorridor &&
                !touchesBoundary &&
                !projectedCross &&
                abs(t.lateralRate) < 0.012f &&
                abs(t.closingMps) < 4.0f &&
                t.distanceMeters <= 70f

        if (parallelSameDirection) {
            return Candidate(AlertLevel.YELLOW, t.id, 8f - t.distanceMeters * 0.02f, audible = false)
        }

        return Candidate(AlertLevel.NONE, null, 0f, audible = false)
    }

    private fun better(a: Candidate, b: Candidate): Boolean =
        a.critical && !b.critical ||
            (a.critical == b.critical && a.level.ordinal > b.level.ordinal) ||
            (a.critical == b.critical && a.level == b.level && a.danger > b.danger)
}
