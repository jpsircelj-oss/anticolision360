package com.anticolision360.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.support.image.TensorImage
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class DetectorEngine(context: Context) {
    private val relevant = setOf(
        "person", "bicycle", "car", "motorcycle", "bus", "truck", "skateboard",
        "dog", "cat", "horse", "sheep", "cow", "bear"
    )

    private val detector: ObjectDetector

    init {
        val base = BaseOptions.builder()
            .setNumThreads(4)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setMaxResults(20)
            .setScoreThreshold(0.25f)
            .build()
        detector = ObjectDetector.createFromFileAndOptions(
            context,
            "efficientdet_lite0.tflite",
            options
        )
    }

    fun detect(image: ImageProxy): List<RawDetection> {
        val bitmap = rgbaImageToBitmap(image)
        val rotated = if (image.imageInfo.rotationDegrees == 0) bitmap else {
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        val input = TensorImage.fromBitmap(rotated)
        val detections = detector.detect(input)
        val w = rotated.width.toFloat().coerceAtLeast(1f)
        val h = rotated.height.toFloat().coerceAtLeast(1f)

        return detections.mapNotNull { detection ->
            val category = detection.categories.maxByOrNull { it.score } ?: return@mapNotNull null
            val label = category.label.lowercase()
            if (label !in relevant) return@mapNotNull null
            val b = detection.boundingBox
            val left = (b.left / w).coerceIn(0f, 1f)
            val top = (b.top / h).coerceIn(0f, 1f)
            val right = (b.right / w).coerceIn(0f, 1f)
            val bottom = (b.bottom / h).coerceIn(0f, 1f)
            if (right <= left || bottom <= top) return@mapNotNull null
            RawDetection(label, category.score, android.graphics.RectF(left, top, right, bottom))
        }
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
                    pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
