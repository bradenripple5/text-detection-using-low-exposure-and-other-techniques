package com.example.lowexposurecamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class TextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        textSize = 40f
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val previousBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var sourceWidth = 0
    private var sourceHeight = 0
    private var detections: List<Detection> = emptyList()

    fun clear() {
        detections = emptyList()
        sourceWidth = 0
        sourceHeight = 0
        postInvalidateOnAnimation()
    }

    fun updateDetections(width: Int, height: Int, detections: List<Detection>) {
        sourceWidth = width
        sourceHeight = height
        this.detections = detections
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sourceWidth == 0 || sourceHeight == 0 || detections.isEmpty()) {
            return
        }

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val sourceWidthF = sourceWidth.toFloat()
        val sourceHeightF = sourceHeight.toFloat()
        val viewAspect = viewWidth / viewHeight
        val sourceAspect = sourceWidthF / sourceHeightF
        val scale: Float
        val offsetX: Float
        val offsetY: Float
        if (viewAspect > sourceAspect) {
            scale = viewHeight / sourceHeightF
            offsetX = (viewWidth - sourceWidthF * scale) / 2f
            offsetY = 0f
        } else {
            scale = viewWidth / sourceWidthF
            offsetX = 0f
            offsetY = (viewHeight - sourceHeightF * scale) / 2f
        }
        detections.forEach { detection ->
            val scaledRect = RectF(
                offsetX + detection.bounds.left * scale,
                offsetY + detection.bounds.top * scale,
                offsetX + detection.bounds.right * scale,
                offsetY + detection.bounds.bottom * scale
            )
            detection.previousBounds?.let { prev ->
                val prevRect = RectF(
                    offsetX + prev.left * scale,
                    offsetY + prev.top * scale,
                    offsetX + prev.right * scale,
                    offsetY + prev.bottom * scale
                )
                canvas.drawRect(prevRect, previousBoxPaint)
                val prevCenterX = prevRect.centerX()
                val prevCenterY = prevRect.centerY()
                val currentCenterX = scaledRect.centerX()
                val currentCenterY = scaledRect.centerY()
                canvas.drawLine(prevCenterX, prevCenterY, currentCenterX, currentCenterY, linkPaint)
            }
            canvas.drawRect(scaledRect, boxPaint)
            val label = buildString {
                append(detection.text)
                append(" (+")
                append(detection.frameDeltaMs)
                append(" ms | #")
                append(detection.count)
                if (detection.isTrackingBitmap) {
                    append(" | tracking bitmap")
                }
                append(")")
            }
            val textWidth = textPaint.measureText(label)
            val textMetrics = textPaint.fontMetrics
            val textHeight = textMetrics.bottom - textMetrics.top
            val textLeft = scaledRect.left
            val textTop = (scaledRect.top - textHeight).coerceAtLeast(0f)
            val textRight = (textLeft + textWidth).coerceAtMost(viewWidth)
            val textBottom = textTop + textHeight
            canvas.drawRect(textLeft, textTop, textRight, textBottom, textBgPaint)
            val textBaseline = textBottom - textMetrics.bottom
            canvas.drawText(label, textLeft, textBaseline, textPaint)
        }
    }

    data class Detection(
        val text: String,
        val bounds: RectF,
        val frameDeltaMs: Long,
        val count: Int,
        val previousBounds: RectF?,
        val isTrackingBitmap: Boolean = false
    )
}
