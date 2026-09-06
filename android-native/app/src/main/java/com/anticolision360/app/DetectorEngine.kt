package com.anticolision360.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import kotlin.math.max
import kotlin.math.min

/**
 * High-precision first-pass detector.
 *
 * Important: this class never decides collision risk. It only emits conservative,
 * geometrically plausible detections. Temporal confirmation and collision relevance
 * live in MotionTracker/RiskEngine.
 */
class DetectorEngine(context: Context) {

    private val relevant = setOf(
        "person", "bicycle", "car", "motorcycle", "bus", "truck", "skateboard",
        "dog", "cat", "horse", "sheep", "cow", "bear"
    )

    private val vulnerable = setOf(
        "person", "bicycle", "motorcycle", "skateboard",
        "dog", "cat", "horse", "sheep", "cow", "bear"
    )

    private val vehicles = setOf("car", "truck", "bus", "motorcycle")

    private val detector: ObjectDetector

    init {
        val baseOptions = BaseOptions.builder()
            .setNumThreads(4)
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(24)
            .setScoreThreshold(0.30f)
            .build()

        detector = ObjectDetector.createFromFileAndOptions(
            context,
            "efficientdet_lite0.tflite",
            options
        )
    }

    fun detect(image: ImageProxy): List<RawDetection> {
        val bitmap = rgbaImageToBitmap(image)
        val rotated = rotate(bitmap, image.imageInfo.rotationDegrees)
        val input = TensorImage.fromBitmap(rotated)
        val w = rotated.width.toFloat().coerceAtLeast(1f)
        val h = rotated.height.toFloat().coerceAtLeast(1f)

        val filtered = detector.detect(input).mapNotNull { detection ->
            val category = detection.categories.maxByOrNull { it.score } ?: return@mapNotNull null
            val label = category.label.lowercase()
            if (label !in relevant) return@mapNotNull null

            val minScore = when {
                label in vulnerable -> 0.38f
                label in vehicles -> 0.43f
                else -> 0.46f
            }
            if (category.score < minScore) return@mapNotNull null

            val b = detection.boundingBox
            val box = RectF(
                (b.left / w).coerceIn(0f, 1f),
                (b.top / h).coerceIn(0f, 1f),
                (b.right / w).coerceIn(0f, 1f),
                (b.bottom / h).coerceIn(0f, 1f)
            )
            if (!plausible(label, category.score, box)) return@mapNotNull null
            RawDetection(label, category.score, box)
        }.sortedByDescending { it.score }

        return suppressDuplicates(filtered).take(14)
    }

    private fun plausible(label: String, score: Float, box: RectF): Boolean {
        val bw = box.width()
        val bh = box.height()
        val area = bw * bh
        if (bw < 0.012f || bh < 0.015f || area < 0.00025f) return false

        val aspect = bw / max(bh, 0.001f)
        if (aspect < 0.08f || aspect > 8.5f) return false

        // Strong rejection of hood/dashboard/scene-wide false detections.
        if (box.bottom > 0.965f && box.top > 0.42f && bw > 0.54f) return false
        if (bw > 0.86f || bh > 0.88f || area > 0.40f) return false

        if (label in vehicles) {
            if (area > 0.27f && score < 0.62f) return false
            if (bw > 0.72f && score < 0.68f) return false
        } else {
            if (area > 0.24f && score < 0.58f) return false
        }
        return true
    }

    private fun suppressDuplicates(items: List<RawDetection>): List<RawDetection> {
        val kept = mutableListOf<RawDetection>()
        for (item in items) {
            val duplicate = kept.any { previous ->
                sameFamily(previous.label, item.label) && iou(previous.box, item.box) > 0.62f
            }
            if (!duplicate) kept += item
        }
        return kept
    }

    private fun sameFamily(a: String, b: String): Boolean {
        if (a == b) return true
        val roadVehicles = setOf("car", "truck", "bus")
        return a in roadVehicles && b in roadVehicles
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, r - l) * max(0f, bottom - t)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun rgbaImageToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes.first()
        val buffer = plane.buffer
        buffer.rewind()

        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = max(4, plane.pixelStride)
        val pixels = IntArray(width * height)

        if (rowStride == width * 4 && pixelStride == 4) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            var p = 0
            var i = 0
            while (p < pixels.size && i + 3 < bytes.size) {
                val r = bytes[i].toInt() and 0xff
                val g = bytes[i + 1].toInt() and 0xff
                val b = bytes[i + 2].toInt() and 0xff
                val a = bytes[i + 3].toInt() and 0xff
                pixels[p++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                i += 4
            }
        } else {
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                buffer.position(min(buffer.limit(), y * rowStride))
                val length = min(rowStride, buffer.remaining())
                buffer.get(row, 0, length)
                for (x in 0 until width) {
                    val i = x * pixelStride
                    if (i + 3 >= length) break
                    val r = row[i].toInt() and 0xff
                    val g = row[i + 1].toInt() and 0xff
                    val b = row[i + 2].toInt() and 0xff
                    val a = row[i + 3].toInt() and 0xff
                    pixels[y * width + x] =
                        (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
