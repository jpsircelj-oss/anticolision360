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
    val ageMs: Long,
    val hitCount: Int,
    val lastSeenAgeMs: Long,
    val stability: Float
) {
    val centerX: Float get() = box.centerX()
    val centerY: Float get() = box.centerY()
    val area: Float get() = max(0f, box.width()) * max(0f, box.height())
    val visualSize: Float get() = sqrt(max(area, 0.000001f))
    val confirmed: Boolean
        get() = hitCount >= 4 && ageMs >= 300L && lastSeenAgeMs <= 260L && stability >= 0.48f

    val ttcSeconds: Float
        get() = if (closingRate > 0.0040f) visualSize / closingRate else Float.POSITIVE_INFINITY
}

data class RiskState(
    val left: AlertLevel = AlertLevel.NONE,
    val right: AlertLevel = AlertLevel.NONE,
    val front: AlertLevel = AlertLevel.NONE,
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
        var closingRate: Float,
        var lateralRate: Float,
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
                val track = InternalTrack(
                    id = nextId++,
                    box = RectF(det.box),
                    score = det.score,
                    previousSize = size,
                    previousCenterX = det.box.centerX(),
                    closingRate = 0f,
                    lateralRate = 0f,
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

                // Heavy temporal smoothing: a single noisy box must never create red.
                best.closingRate = best.closingRate * 0.84f + instantClosing * 0.16f
                best.lateralRate = best.lateralRate * 0.82f + instantLateral * 0.18f
                best.previousSize = size
                best.previousCenterX = smoothed.centerX()
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

            TrackedObject(
                id = t.id,
                label = stableLabel(t),
                score = t.score,
                box = RectF(t.box),
                closingRate = t.closingRate,
                lateralRate = t.lateralRate,
                ageMs = age,
                hitCount = t.hitCount,
                lastSeenAgeMs = seenAge,
                stability = stability
            )
        }
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

    private val frontRelevant = setOf(
        "car", "truck", "bus", "motorcycle", "bicycle", "person", "skateboard",
        "dog", "cat", "horse", "sheep", "cow", "bear"
    )
    private val sideRelevant = frontRelevant
    private val vulnerable = setOf(
        "motorcycle", "bicycle", "person", "skateboard",
        "dog", "cat", "horse", "sheep", "cow", "bear"
    )

    private data class Candidate(val level: AlertLevel, val targetId: Int?, val danger: Float)

    private class ZoneLatch {
        var level: AlertLevel = AlertLevel.NONE
        private var yellowFrames = 0
        private var redFrames = 0
        private var lastEvidenceAt = 0L

        fun update(candidate: AlertLevel, now: Long): AlertLevel {
            if (candidate != AlertLevel.NONE) lastEvidenceAt = now

            yellowFrames = if (candidate == AlertLevel.YELLOW || candidate == AlertLevel.RED) {
                (yellowFrames + 1).coerceAtMost(8)
            } else 0

            redFrames = if (candidate == AlertLevel.RED) {
                (redFrames + 1).coerceAtMost(8)
            } else 0

            level = when {
                redFrames >= 2 -> AlertLevel.RED
                yellowFrames >= 3 && level != AlertLevel.RED -> AlertLevel.YELLOW
                candidate == AlertLevel.YELLOW && level == AlertLevel.RED -> AlertLevel.YELLOW
                candidate == AlertLevel.NONE && now - lastEvidenceAt > 520L -> AlertLevel.NONE
                else -> level
            }
            return level
        }
    }

    private val leftLatch = ZoneLatch()
    private val rightLatch = ZoneLatch()
    private val frontLatch = ZoneLatch()

    private var lowSpeedSince = 0L

    fun evaluate(tracks: List<TrackedObject>, speedKmh: Float?, now: Long): RiskState {
        val speed = speedKmh ?: 0f
        val parking = if (speedKmh != null && speed <= 5f) {
            if (lowSpeedSince == 0L) lowSpeedSince = now
            now - lowSpeedSince >= 1200L
        } else {
            lowSpeedSince = 0L
            false
        }

        var frontCandidate = Candidate(AlertLevel.NONE, null, 0f)
        var leftCandidate = Candidate(AlertLevel.NONE, null, 0f)
        var rightCandidate = Candidate(AlertLevel.NONE, null, 0f)

        for (t in tracks) {
            if (!t.confirmed || t.label !in frontRelevant) continue

            val depth = ((t.box.bottom - 0.30f) / 0.70f).coerceIn(0f, 1f)
            val halfLane = 0.095f + 0.205f * depth
            val laneLeft = 0.50f - halfLane
            val laneRight = 0.50f + halfLane
            val overlap = max(0f, min(t.box.right, laneRight) - max(t.box.left, laneLeft))
            val overlapRatio = overlap / max(t.box.width(), 0.001f)
            val stronglyCentral =
                t.centerX in (0.50f - halfLane * 0.62f)..(0.50f + halfLane * 0.62f)
            val inFrontCorridor =
                t.box.bottom > 0.30f && (overlapRatio > 0.38f || stronglyCentral)

            if (inFrontCorridor) {
                val c = frontRisk(t, speed, stronglyCentral)
                if (better(c, frontCandidate)) frontCandidate = c
            }

            if (speed > 30f && t.label in sideRelevant && t.box.bottom > 0.38f) {
                val side = when {
                    t.centerX < 0.43f -> -1
                    t.centerX > 0.57f -> 1
                    else -> 0
                }
                if (side != 0) {
                    val laneBoundary = if (side < 0) laneLeft else laneRight
                    val intrudes =
                        if (side < 0) t.box.right > laneBoundary else t.box.left < laneBoundary
                    val movingToward =
                        if (side < 0) t.lateralRate > 0.010f else t.lateralRate < -0.010f
                    val c = sideRisk(t, intrudes, movingToward, speed)
                    if (side < 0 && better(c, leftCandidate)) leftCandidate = c
                    if (side > 0 && better(c, rightCandidate)) rightCandidate = c
                }
            }
        }

        val front = frontLatch.update(frontCandidate.level, now)
        val left = leftLatch.update(leftCandidate.level, now)
        val right = rightLatch.update(rightCandidate.level, now)

        return RiskState(
            left = left,
            right = right,
            front = front,
            parkingMode = parking,
            speedKmh = speedKmh,
            leftTargetId = if (left != AlertLevel.NONE) leftCandidate.targetId else null,
            rightTargetId = if (right != AlertLevel.NONE) rightCandidate.targetId else null,
            frontTargetId = if (front != AlertLevel.NONE) frontCandidate.targetId else null
        )
    }

    private fun frontRisk(t: TrackedObject, speed: Float, stronglyCentral: Boolean): Candidate {
        // A front alert needs real convergence. Size alone is not enough.
        if (t.closingRate <= 0.0022f) return Candidate(AlertLevel.NONE, null, 0f)

        val highSpeed = speed >= 100f
        val mediumSpeed = speed >= 60f
        val yellowTtc = when {
            highSpeed -> 5.8f
            mediumSpeed -> 5.0f
            else -> 4.3f
        }
        val redTtc = when {
            highSpeed -> 2.9f
            mediumSpeed -> 2.6f
            else -> 2.3f
        }
        val minArea = when {
            highSpeed -> 0.0018f
            mediumSpeed -> 0.0025f
            else -> 0.0034f
        }

        val vulnerableBoost = t.label in vulnerable
        val yellow =
            (t.ttcSeconds < yellowTtc && t.area > minArea) ||
                (vulnerableBoost && t.ttcSeconds < yellowTtc + 0.7f && t.box.bottom > 0.48f)

        val red =
            (t.ttcSeconds < redTtc && t.area > minArea * 1.25f && t.closingRate > 0.0045f) ||
                (
                    stronglyCentral &&
                        t.area > 0.095f &&
                        t.closingRate > 0.0048f &&
                        t.ageMs > 650L
                    )

        val level = when {
            red -> AlertLevel.RED
            yellow -> AlertLevel.YELLOW
            else -> AlertLevel.NONE
        }

        val danger =
            if (level == AlertLevel.NONE) 0f
            else (8f - t.ttcSeconds.coerceAtMost(8f)) + t.area * 20f + t.closingRate * 70f

        return Candidate(level, if (level == AlertLevel.NONE) null else t.id, danger)
    }

    private fun sideRisk(
        t: TrackedObject,
        intrudes: Boolean,
        movingToward: Boolean,
        speed: Float
    ): Candidate {
        // Stationary parked objects at the side should remain silent.
        if (!intrudes && !movingToward) return Candidate(AlertLevel.NONE, null, 0f)

        val vulnerableObject = t.label in vulnerable
        val samePace = abs(t.closingRate) < 0.0022f && abs(t.lateralRate) < 0.010f
        val near = t.area > (if (speed >= 90f) 0.0045f else 0.0065f) || t.box.bottom > 0.62f

        val red = when {
            vulnerableObject ->
                near && intrudes && (
                    movingToward ||
                        (t.ttcSeconds < 2.8f && t.closingRate > 0.0040f)
                    )
            else ->
                !samePace && intrudes && movingToward &&
                    (t.area > 0.018f || t.ttcSeconds < 2.6f)
        }

        val yellow = red || when {
            vulnerableObject -> near && (intrudes || movingToward)
            else -> !samePace && intrudes &&
                (movingToward || t.closingRate > 0.0040f || t.area > 0.025f)
        }

        val level = when {
            red -> AlertLevel.RED
            yellow -> AlertLevel.YELLOW
            else -> AlertLevel.NONE
        }

        val danger =
            if (level == AlertLevel.NONE) 0f
            else t.area * 20f + abs(t.lateralRate) * 10f +
                t.closingRate.coerceAtLeast(0f) * 50f + if (intrudes) 1.4f else 0f

        return Candidate(level, if (level == AlertLevel.NONE) null else t.id, danger)
    }

    private fun better(a: Candidate, b: Candidate): Boolean =
        a.level.ordinal > b.level.ordinal ||
            (a.level == b.level && a.danger > b.danger)
}
