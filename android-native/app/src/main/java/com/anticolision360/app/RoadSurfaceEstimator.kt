package com.anticolision360.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Perspective corridor projected on the real road surface.
 *
 * Width is calibrated to two metres in the near field. The centre line bends
 * with pavement evidence and with reliable vehicle ground-contact anchors.
 */
data class RoadGeometry(
    val farY: Float = 0.43f,
    val middleY: Float = 0.6975f,
    val baseY: Float = 0.965f,
    val farCenterX: Float = 0.50f,
    val middleCenterX: Float = 0.50f,
    val baseCenterX: Float = 0.50f,
    val farHalfWidth: Float = 0.014f,
    val middleHalfWidth: Float = 0.072f,
    val baseHalfWidth: Float = 0.18f,
    val confidence: Float = 0f,
    val stableFrames: Int = 0,
    val visible: Boolean = false
) {
    fun corridorAt(normalizedY: Float): Pair<Float, Float> {
        val t = ((normalizedY - farY) / max(0.001f, baseY - farY)).coerceIn(0f, 1f)
        val center = quadraticThroughMiddle(farCenterX, middleCenterX, baseCenterX, t)
        val halfWidth = quadraticThroughMiddle(farHalfWidth, middleHalfWidth, baseHalfWidth, t)
            .coerceAtLeast(0.008f)
        return Pair(
            (center - halfWidth).coerceIn(0.01f, 0.99f),
            (center + halfWidth).coerceIn(0.01f, 0.99f)
        )
    }

    private fun quadraticThroughMiddle(start: Float, middle: Float, end: Float, t: Float): Float {
        val control = 2f * middle - 0.5f * (start + end)
        val oneMinusT = 1f - t
        return oneMinusT * oneMinusT * start +
            2f * oneMinusT * t * control +
            t * t * end
    }

    companion object {
        fun calibratedStraight(): RoadGeometry = RoadGeometry()
    }
}

/**
 * Core 2.4 road estimator.
 *
 * Priority order:
 * 1) real pavement/lane transitions,
 * 2) lower edge / wheel-contact point of road vehicles that are aligned with our path,
 * 3) temporal history only for short gaps.
 *
 * If there is no credible road evidence the visual corridor disappears instead of
 * drawing a straight fictitious corridor over walls, furniture or the dashboard.
 */
class RoadSurfaceEstimator {

    private data class RowEstimate(
        val y: Float,
        val center: Float,
        val quality: Float
    )

    private data class EdgeCandidate(val x: Float, val score: Float)

    private var geometry = RoadGeometry.calibratedStraight()
    private var lastAnalysisAt = 0L
    private var weakFrames = 0

    fun analyze(
        source: Bitmap,
        detections: List<RawDetection>,
        now: Long
    ): RoadGeometry {
        if (now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) return geometry
        lastAnalysisAt = now

        val scaled = Bitmap.createScaledBitmap(source, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, true)
        val pixels = IntArray(ANALYSIS_WIDTH * ANALYSIS_HEIGHT)
        scaled.getPixels(pixels, 0, ANALYSIS_WIDTH, 0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
        if (scaled !== source) scaled.recycle()

        val pavementRows = SAMPLE_ROWS.map { y -> estimateRow(pixels, y, detections) }
        val pavementReliable = pavementRows.filter { it.quality >= MIN_ROW_QUALITY }
        val contactAnchors = vehicleGroundContactAnchors(detections)

        // Vehicle wheel/chassis anchors never replace pavement evidence by themselves;
        // they reinforce direction and curvature when road markings are weak/partial.
        val combined = (pavementReliable + contactAnchors).sortedBy { it.y }
        val pavementCoverage = pavementReliable.size / SAMPLE_ROWS.size.toFloat()
        val pavementQuality = if (pavementReliable.isEmpty()) 0f
        else pavementReliable.map { it.quality }.average().toFloat()
        val anchorQuality = if (contactAnchors.isEmpty()) 0f
        else contactAnchors.map { it.quality }.average().toFloat()

        val hasEnoughGeometry =
            pavementReliable.size >= 2 ||
                (pavementReliable.isNotEmpty() && contactAnchors.isNotEmpty()) ||
                contactAnchors.size >= 2

        val rawConfidence = (
            pavementQuality * 0.58f +
                pavementCoverage * 0.24f +
                anchorQuality * 0.18f
            ).coerceIn(0f, 1f)

        if (!hasEnoughGeometry || rawConfidence < MIN_FRAME_CONFIDENCE) {
            weakFrames += 1
            val decayedConfidence = geometry.confidence * if (weakFrames <= 2) 0.82f else 0.58f
            geometry = geometry.copy(
                confidence = decayedConfidence,
                stableFrames = max(0, geometry.stableFrames - 1),
                visible = decayedConfidence >= VISIBLE_CONFIDENCE && weakFrames <= HOLD_WEAK_FRAMES
            )
            return geometry
        }

        weakFrames = 0

        val nearEvidence = combined.filter { it.y >= 0.82f }
        val middleEvidence = combined.filter { it.y in 0.62f..0.84f }
        val farEvidence = combined.filter { it.y <= 0.70f }

        val near = weightedCenter(nearEvidence, 0.50f)
        val middle = weightedCenter(middleEvidence, near)
        val far = weightedCenter(farEvidence, middle)

        // Keep the corridor attached to the camera/vehicle in the near field,
        // but allow more curvature farther ahead. Ground-contact anchors pull the
        // middle/far portions toward the actual road occupied by vehicles.
        val rawBase = (0.50f * 0.90f + near * 0.10f).coerceIn(0.465f, 0.535f)
        val anchorPull = (anchorQuality * 0.30f).coerceIn(0f, 0.22f)
        val rawMiddleBase = middle.coerceIn(rawBase - 0.16f, rawBase + 0.16f)
        val rawMiddle = lerp(rawMiddleBase, contactCenter(contactAnchors, rawMiddleBase), anchorPull)
        val extrapolatedFarBase = (far + (far - rawMiddle) * 0.26f)
            .coerceIn(rawMiddle - 0.24f, rawMiddle + 0.24f)
        val extrapolatedFar = lerp(
            extrapolatedFarBase,
            contactCenter(contactAnchors.filter { it.y <= 0.74f }, extrapolatedFarBase),
            anchorPull * 1.15f
        )

        val alpha = (0.11f + rawConfidence * 0.23f).coerceIn(0.13f, 0.32f)
        val stable = (geometry.stableFrames + 1).coerceAtMost(12)
        val filteredConfidence = geometry.confidence * 0.52f + rawConfidence * 0.48f

        geometry = geometry.copy(
            farCenterX = lerp(geometry.farCenterX, extrapolatedFar, alpha),
            middleCenterX = lerp(geometry.middleCenterX, rawMiddle, alpha),
            baseCenterX = lerp(geometry.baseCenterX, rawBase, alpha * 0.52f),
            confidence = filteredConfidence,
            stableFrames = stable,
            visible = stable >= REQUIRED_STABLE_FRAMES && filteredConfidence >= VISIBLE_CONFIDENCE
        )
        return geometry
    }

    /**
     * Uses the lower centre of a detected road vehicle as an approximation of the
     * tyre/chassis contact with the road plane. Very wide profile vehicles are
     * excluded because they are likely crossing the road and are poor lane anchors.
     */
    private fun vehicleGroundContactAnchors(detections: List<RawDetection>): List<RowEstimate> {
        val anchors = mutableListOf<RowEstimate>()
        for (d in detections) {
            if (d.label !in ROAD_VEHICLES || d.score < 0.44f) continue
            val box = d.box
            if (box.bottom !in 0.44f..0.96f) continue

            val aspect = box.width() / max(box.height(), 0.001f)
            if (d.label in setOf("car", "truck", "bus") && aspect > 1.70f) continue

            val y = (box.bottom - box.height() * 0.025f).coerceIn(0.42f, 0.95f)
            val contactX = box.centerX()
            val current = geometry.corridorAt(y)
            val currentCenter = (current.first + current.second) * 0.5f
            val currentHalf = (current.second - current.first) * 0.5f

            // Only use vehicles plausibly travelling on our road path as road-plane anchors.
            val tolerance = max(0.075f, currentHalf * 1.45f)
            val offset = abs(contactX - currentCenter)
            if (offset > tolerance) continue

            val alignment = (1f - offset / tolerance).coerceIn(0f, 1f)
            val depthWeight = ((y - 0.42f) / 0.53f).coerceIn(0.20f, 1f)
            val quality = (d.score * (0.52f + alignment * 0.30f) * (0.72f + depthWeight * 0.28f))
                .coerceIn(0f, 0.82f)

            anchors += RowEstimate(y, contactX, quality)
        }
        return anchors.sortedByDescending { it.quality }.take(4)
    }

    private fun estimateRow(
        pixels: IntArray,
        normalizedY: Float,
        detections: List<RawDetection>
    ): RowEstimate {
        val y = (normalizedY * (ANALYSIS_HEIGHT - 1)).toInt().coerceIn(4, ANALYSIS_HEIGHT - 5)
        val previousBounds = geometry.corridorAt(normalizedY)
        val expectedCenter = (previousBounds.first + previousBounds.second) * 0.5f
        val corridorHalf = (previousBounds.second - previousBounds.first) * 0.5f
        val expectedLaneHalf = max(0.032f, corridorHalf * 1.62f)

        val left = strongestEdge(
            pixels, y, normalizedY,
            expectedCenter - expectedLaneHalf * 1.85f,
            expectedCenter - expectedLaneHalf * 0.42f,
            expectedCenter - expectedLaneHalf,
            expectedLaneHalf,
            detections
        )
        val right = strongestEdge(
            pixels, y, normalizedY,
            expectedCenter + expectedLaneHalf * 0.42f,
            expectedCenter + expectedLaneHalf * 1.85f,
            expectedCenter + expectedLaneHalf,
            expectedLaneHalf,
            detections
        )

        val pairWidth = right.x - left.x
        val expectedWidth = expectedLaneHalf * 2f
        val widthAgreement = (1f - abs(pairWidth - expectedWidth) / max(expectedWidth, 0.03f))
            .coerceIn(0f, 1f)
        val evidence = min(left.score, right.score)
        val quality = (evidence * (0.74f + widthAgreement * 0.26f)).coerceIn(0f, 1f)
        val measuredCenter = (left.x + right.x) * 0.5f
        val guardedCenter = measuredCenter.coerceIn(expectedCenter - 0.11f, expectedCenter + 0.11f)
        return RowEstimate(normalizedY, guardedCenter, quality)
    }

    private fun strongestEdge(
        pixels: IntArray,
        y: Int,
        normalizedY: Float,
        start: Float,
        end: Float,
        expected: Float,
        expectedHalf: Float,
        detections: List<RawDetection>
    ): EdgeCandidate {
        val from = (min(start, end).coerceIn(0.03f, 0.96f) * ANALYSIS_WIDTH).toInt()
        val to = (max(start, end).coerceIn(0.04f, 0.97f) * ANALYSIS_WIDTH).toInt()
        var bestX = expected.coerceIn(0.02f, 0.98f)
        var bestScore = 0f

        for (x in from..to) {
            if (x < 4 || x >= ANALYSIS_WIDTH - 4) continue
            val nx = x / ANALYSIS_WIDTH.toFloat()
            if (occluded(nx, normalizedY, detections)) continue

            val leftGray = gray(pixels[y * ANALYSIS_WIDTH + x - 3])
            val rightGray = gray(pixels[y * ANALYSIS_WIDTH + x + 3])
            val horizontalGradient = abs(rightGray - leftGray) / 255f
            val upperGray = gray(pixels[(y - 2) * ANALYSIS_WIDTH + x])
            val lowerGray = gray(pixels[(y + 2) * ANALYSIS_WIDTH + x])
            val verticalGradient = abs(lowerGray - upperGray) / 255f
            val marking = markingLikelihood(pixels[y * ANALYSIS_WIDTH + x])
            val proximity = (1f - abs(nx - expected) / max(expectedHalf * 0.95f, 0.025f))
                .coerceIn(0f, 1f)
            val score = horizontalGradient * 0.56f +
                verticalGradient * 0.09f +
                marking * 0.27f +
                proximity * 0.08f
            if (score > bestScore) {
                bestScore = score
                bestX = nx
            }
        }
        return EdgeCandidate(bestX, bestScore)
    }

    private fun occluded(x: Float, y: Float, detections: List<RawDetection>): Boolean =
        detections.any { detection ->
            val box = detection.box
            x >= box.left - 0.018f && x <= box.right + 0.018f &&
                y >= box.top - 0.015f && y <= box.bottom - 0.010f
        }

    private fun markingLikelihood(color: Int): Float {
        val r = Color.red(color).toFloat()
        val g = Color.green(color).toFloat()
        val b = Color.blue(color).toFloat()
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val white = if (minChannel > 145f && maxChannel - minChannel < 45f) {
            ((minChannel - 145f) / 110f).coerceIn(0f, 1f)
        } else 0f
        val yellow = if (r > 135f && g > 95f && b < 145f && r > b * 1.18f) {
            ((r + g - 230f) / 250f).coerceIn(0f, 1f)
        } else 0f
        return max(white, yellow)
    }

    private fun gray(color: Int): Float =
        Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f

    private fun weightedCenter(rows: List<RowEstimate>, fallback: Float): Float {
        if (rows.isEmpty()) return fallback
        val total = rows.sumOf { it.quality.toDouble() }.toFloat().coerceAtLeast(0.001f)
        return rows.sumOf { (it.center * it.quality).toDouble() }.toFloat() / total
    }

    private fun contactCenter(rows: List<RowEstimate>, fallback: Float): Float =
        weightedCenter(rows, fallback)

    private fun lerp(from: Float, to: Float, alpha: Float): Float = from + (to - from) * alpha

    companion object {
        private const val ANALYSIS_WIDTH = 192
        private const val ANALYSIS_HEIGHT = 144
        private const val ANALYSIS_INTERVAL_MS = 150L
        private const val MIN_ROW_QUALITY = 0.16f
        private const val MIN_FRAME_CONFIDENCE = 0.20f
        private const val VISIBLE_CONFIDENCE = 0.23f
        private const val REQUIRED_STABLE_FRAMES = 3
        private const val HOLD_WEAK_FRAMES = 2
        private val SAMPLE_ROWS = floatArrayOf(0.52f, 0.64f, 0.76f, 0.88f, 0.93f)
        private val ROAD_VEHICLES = setOf("car", "truck", "bus", "motorcycle")
    }
}
