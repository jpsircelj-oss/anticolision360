package com.anticolision360.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Perspective corridor projected on the road image.
 *
 * The corridor is always two metres wide in the calibrated near field (one metre
 * to either side of the camera axis). Only its centre line bends with the road.
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
 * Lightweight road-direction estimator for a phone/tablet camera.
 *
 * It looks for paired pavement/lane transitions at four depths, ignores pixels
 * covered by detected road users and then applies temporal confidence filtering.
 * When the evidence is weak, the last stable geometry is retained for risk
 * continuity but the visual guide fades out instead of inventing road lines.
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

        val rows = SAMPLE_ROWS.map { y ->
            estimateRow(pixels, y, detections)
        }
        val reliable = rows.filter { it.quality >= MIN_ROW_QUALITY }
        val rawConfidence = if (reliable.isEmpty()) {
            0f
        } else {
            val coverage = reliable.size / SAMPLE_ROWS.size.toFloat()
            val meanQuality = reliable.map { it.quality }.average().toFloat()
            (meanQuality * 0.72f + coverage * 0.28f).coerceIn(0f, 1f)
        }

        if (reliable.size < 2 || rawConfidence < MIN_FRAME_CONFIDENCE) {
            weakFrames += 1
            val decayedConfidence = geometry.confidence * if (weakFrames <= 4) 0.90f else 0.76f
            geometry = geometry.copy(
                confidence = decayedConfidence,
                stableFrames = max(0, geometry.stableFrames - 1),
                visible = decayedConfidence >= VISIBLE_CONFIDENCE && weakFrames <= HOLD_WEAK_FRAMES
            )
            return geometry
        }

        weakFrames = 0
        val near = weightedCenter(reliable.filter { it.y >= 0.82f }, 0.50f)
        val middle = weightedCenter(reliable.filter { it.y in 0.62f..0.84f }, near)
        val far = weightedCenter(reliable.filter { it.y <= 0.70f }, middle)

        // Keep the corridor attached to the vehicle near the bonnet while allowing
        // progressively more curvature towards the horizon.
        val rawBase = (0.50f * 0.86f + near * 0.14f).coerceIn(0.46f, 0.54f)
        val rawMiddle = middle.coerceIn(rawBase - 0.14f, rawBase + 0.14f)
        val extrapolatedFar = (far + (far - rawMiddle) * 0.32f)
            .coerceIn(rawMiddle - 0.22f, rawMiddle + 0.22f)

        val alpha = (0.10f + rawConfidence * 0.20f).coerceIn(0.12f, 0.29f)
        val stable = (geometry.stableFrames + 1).coerceAtMost(12)
        val filteredConfidence = geometry.confidence * 0.58f + rawConfidence * 0.42f
        geometry = geometry.copy(
            farCenterX = lerp(geometry.farCenterX, extrapolatedFar, alpha),
            middleCenterX = lerp(geometry.middleCenterX, rawMiddle, alpha),
            baseCenterX = lerp(geometry.baseCenterX, rawBase, alpha * 0.60f),
            confidence = filteredConfidence,
            stableFrames = stable,
            visible = stable >= REQUIRED_STABLE_FRAMES && filteredConfidence >= VISIBLE_CONFIDENCE
        )
        return geometry
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
            pixels = pixels,
            y = y,
            normalizedY = normalizedY,
            start = expectedCenter - expectedLaneHalf * 1.85f,
            end = expectedCenter - expectedLaneHalf * 0.42f,
            expected = expectedCenter - expectedLaneHalf,
            expectedHalf = expectedLaneHalf,
            detections = detections
        )
        val right = strongestEdge(
            pixels = pixels,
            y = y,
            normalizedY = normalizedY,
            start = expectedCenter + expectedLaneHalf * 0.42f,
            end = expectedCenter + expectedLaneHalf * 1.85f,
            expected = expectedCenter + expectedLaneHalf,
            expectedHalf = expectedLaneHalf,
            detections = detections
        )

        val pairWidth = right.x - left.x
        val expectedWidth = expectedLaneHalf * 2f
        val widthAgreement = (1f - abs(pairWidth - expectedWidth) / max(expectedWidth, 0.03f))
            .coerceIn(0f, 1f)
        val evidence = min(left.score, right.score)
        // Pair geometry may reinforce real image evidence, but can never create
        // confidence on its own when both searches found only their defaults.
        val quality = (evidence * (0.74f + widthAgreement * 0.26f)).coerceIn(0f, 1f)
        val measuredCenter = (left.x + right.x) * 0.5f
        val guardedCenter = measuredCenter.coerceIn(expectedCenter - 0.10f, expectedCenter + 0.10f)
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
            val score = horizontalGradient * 0.58f +
                verticalGradient * 0.08f +
                marking * 0.25f +
                proximity * 0.09f
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
                y >= box.top - 0.015f && y <= box.bottom + 0.025f
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

    private fun lerp(from: Float, to: Float, alpha: Float): Float =
        from + (to - from) * alpha

    companion object {
        private const val ANALYSIS_WIDTH = 192
        private const val ANALYSIS_HEIGHT = 144
        private const val ANALYSIS_INTERVAL_MS = 170L
        private const val MIN_ROW_QUALITY = 0.16f
        private const val MIN_FRAME_CONFIDENCE = 0.18f
        private const val VISIBLE_CONFIDENCE = 0.20f
        private const val REQUIRED_STABLE_FRAMES = 3
        private const val HOLD_WEAK_FRAMES = 5
        private val SAMPLE_ROWS = floatArrayOf(0.54f, 0.66f, 0.79f, 0.91f)
    }
}
