package com.certis.screeneye

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.max

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22D3EE")
        style = Paint.Style.FILL
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var faces: List<Face> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var rotationDegrees: Int = 0
    private var isFrontFacing: Boolean = true

    fun updateFaces(
        faces: List<Face>,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        isFrontFacing: Boolean
    ) {
        this.faces = faces
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.rotationDegrees = rotationDegrees
        this.isFrontFacing = isFrontFacing
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (faces.isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
            return
        }

        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageHeight.toFloat()
        } else {
            imageWidth.toFloat()
        }
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageWidth.toFloat()
        } else {
            imageHeight.toFloat()
        }

        val scale = max(width / rotatedWidth, height / rotatedHeight)
        val dx = (width - rotatedWidth * scale) / 2f
        val dy = (height - rotatedHeight * scale) / 2f

        fun mapX(x: Float): Float {
            val mapped = x * scale + dx
            return if (isFrontFacing) width - mapped else mapped
        }

        fun mapY(y: Float): Float = y * scale + dy

        for (face in faces) {
            val box = face.boundingBox
            val left = mapX(box.left.toFloat())
            val right = mapX(box.right.toFloat())
            val top = mapY(box.top.toFloat())
            val bottom = mapY(box.bottom.toFloat())
            canvas.drawRect(
                minOf(left, right),
                top,
                maxOf(left, right),
                bottom,
                boxPaint
            )

            val landmarks = listOf(
                FaceLandmark.LEFT_EYE,
                FaceLandmark.RIGHT_EYE,
                FaceLandmark.NOSE_BASE,
                FaceLandmark.MOUTH_LEFT,
                FaceLandmark.MOUTH_RIGHT,
                FaceLandmark.MOUTH_BOTTOM
            )
            for (type in landmarks) {
                val point = face.getLandmark(type)?.position ?: continue
                canvas.drawCircle(mapX(point.x), mapY(point.y), 8f, dotPaint)
            }
        }
    }
}
