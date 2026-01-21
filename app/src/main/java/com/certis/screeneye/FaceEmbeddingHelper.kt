package com.certis.screeneye

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.face.Face
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class FaceEmbeddingHelper(context: Context) {

    private val interpreter: Interpreter?
    private val inputSize: Int
    private val inputType: DataType
    private val outputSize: Int

    init {
        val modelBytes = runCatching {
            context.assets.open("face_net.tflite").use { input ->
                input.readBytes()
            }
        }.getOrNull()
        if (modelBytes == null || modelBytes.size < 1024) {
            interpreter = null
            inputSize = 0
            inputType = DataType.FLOAT32
            outputSize = 0
        } else {
            val buffer = ByteBuffer.allocateDirect(modelBytes.size)
            buffer.order(ByteOrder.nativeOrder())
            buffer.put(modelBytes)
            buffer.rewind()
            interpreter = Interpreter(buffer)
            val inputShape = interpreter.getInputTensor(0).shape()
            inputSize = inputShape[1]
            inputType = interpreter.getInputTensor(0).dataType()
            val outputShape = interpreter.getOutputTensor(0).shape()
            outputSize = outputShape.last()
        }
    }

    fun isReady(): Boolean = interpreter != null

    fun extractEmbedding(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        face: Face
    ): FloatArray? {
        val interpreter = interpreter ?: return null
        val bitmap = imageProxyToBitmap(imageProxy) ?: return null
        val rotated = rotateBitmap(bitmap, rotationDegrees)
        val faceBitmap = cropFace(rotated, face.boundingBox) ?: return null
        val scaled = Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)

        val output = Array(1) { FloatArray(outputSize) }
        val inputBuffer = when (inputType) {
            DataType.UINT8 -> toByteBufferUInt8(scaled)
            else -> toByteBufferFloat(scaled)
        }
        interpreter.run(inputBuffer, output)
        return output[0]
    }

    private fun cropFace(source: Bitmap, bounds: Rect): Bitmap? {
        val left = bounds.left.coerceIn(0, source.width - 1)
        val top = bounds.top.coerceIn(0, source.height - 1)
        val right = bounds.right.coerceIn(left + 1, source.width)
        val bottom = bounds.bottom.coerceIn(top + 1, source.height)
        if (right <= left || bottom <= top) {
            return null
        }
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun toByteBufferFloat(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF)
            val g = (pixel shr 8 and 0xFF)
            val b = (pixel and 0xFF)
            buffer.putFloat((r - 127.5f) / 128f)
            buffer.putFloat((g - 127.5f) / 128f)
            buffer.putFloat((b - 127.5f) / 128f)
        }
        buffer.rewind()
        return buffer
    }

    private fun toByteBufferUInt8(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buffer.put((pixel shr 16 and 0xFF).toByte())
            buffer.put((pixel shr 8 and 0xFF).toByte())
            buffer.put((pixel and 0xFF).toByte())
        }
        buffer.rewind()
        return buffer
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            90,
            out
        )
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
}
