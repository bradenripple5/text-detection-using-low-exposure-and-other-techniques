package com.example.lowexposurecamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class TextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        isClickable = true
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val phoneBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 20, 20, 20)
        style = Paint.Style.FILL
    }

    private val phoneGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
    var showLabels: Boolean = true
        set(value) {
            if (field == value) {
                return
            }
            field = value
            postInvalidateOnAnimation()
        }
    var onDetectionTap: ((Detection) -> Unit)? = null

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

        val transform = computeOverlayTransform() ?: return
        val viewWidth = width.toFloat()
        detections.forEach { detection ->
            val scaledRect = transform.mapRect(detection.bounds)
            detection.previousBounds?.let { prev ->
                val prevRect = transform.mapRect(prev)
                canvas.drawRect(prevRect, previousBoxPaint)
                val prevCenterX = prevRect.centerX()
                val prevCenterY = prevRect.centerY()
                val currentCenterX = scaledRect.centerX()
                val currentCenterY = scaledRect.centerY()
                canvas.drawLine(prevCenterX, prevCenterY, currentCenterX, currentCenterY, linkPaint)
            }
            canvas.drawRect(scaledRect, boxPaint)
            if (detection.isDialable) {
                drawPhoneBadge(canvas, scaledRect, viewWidth)
            }
            if (!showLabels) {
                return@forEach
            }
            val label = buildLabel(detection)
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

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) {
            return onDetectionTap != null && detections.isNotEmpty()
        }
        val tappedDetection = findDetectionAt(event.x, event.y) ?: return super.onTouchEvent(event)
        performClick()
        onDetectionTap?.invoke(tappedDetection)
        return true
    }

    private fun findDetectionAt(x: Float, y: Float): Detection? {
        val transform = computeOverlayTransform() ?: return null
        val viewWidth = width.toFloat()
        for (index in detections.indices.reversed()) {
            val detection = detections[index]
            val boundsRect = transform.mapRect(detection.bounds)
            val labelTapped = showLabels && computeLabelRect(boundsRect, detection, viewWidth).contains(x, y)
            if (boundsRect.contains(x, y) || labelTapped) {
                return detection
            }
        }
        return null
    }

    private fun computeLabelRect(boundsRect: RectF, detection: Detection, viewWidth: Float): RectF {
        val label = buildLabel(detection)
        val textWidth = textPaint.measureText(label)
        val textMetrics = textPaint.fontMetrics
        val textHeight = textMetrics.bottom - textMetrics.top
        val textLeft = boundsRect.left
        val textTop = (boundsRect.top - textHeight).coerceAtLeast(0f)
        val textRight = (textLeft + textWidth).coerceAtMost(viewWidth)
        val textBottom = textTop + textHeight
        return RectF(textLeft, textTop, textRight, textBottom)
    }

    private fun computeOverlayTransform(): OverlayTransform? {
        if (sourceWidth == 0 || sourceHeight == 0 || width == 0 || height == 0) {
            return null
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
        return OverlayTransform(scale, offsetX, offsetY)
    }

    private fun buildLabel(detection: Detection): String = buildString {
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

    private fun drawPhoneBadge(canvas: Canvas, boundsRect: RectF, viewWidth: Float) {
        val badgeSize = 36f
        val margin = 8f
        val left = (boundsRect.right + margin).coerceAtMost(viewWidth - badgeSize)
        val top = (boundsRect.top - badgeSize - margin).coerceAtLeast(0f)
        val badgeRect = RectF(left, top, left + badgeSize, top + badgeSize)
        canvas.drawRoundRect(badgeRect, 10f, 10f, phoneBadgePaint)

        val path = Path().apply {
            moveTo(badgeRect.left + 11f, badgeRect.top + 12f)
            quadTo(badgeRect.left + 9f, badgeRect.top + 16f, badgeRect.left + 13f, badgeRect.top + 20f)
            lineTo(badgeRect.left + 16f, badgeRect.top + 23f)
            quadTo(badgeRect.left + 18f, badgeRect.top + 25f, badgeRect.left + 22f, badgeRect.top + 23f)
            lineTo(badgeRect.left + 25f, badgeRect.top + 20f)
            quadTo(badgeRect.left + 27f, badgeRect.top + 18f, badgeRect.left + 25f, badgeRect.top + 15f)
            lineTo(badgeRect.left + 22f, badgeRect.top + 12f)
            quadTo(badgeRect.left + 18f, badgeRect.top + 8f, badgeRect.left + 15f, badgeRect.top + 10f)
            close()
        }
        canvas.drawPath(path, phoneGlyphPaint)
    }

    private data class OverlayTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float
    ) {
        fun mapRect(rect: RectF): RectF = RectF(
            offsetX + rect.left * scale,
            offsetY + rect.top * scale,
            offsetX + rect.right * scale,
            offsetY + rect.bottom * scale
        )
    }

    data class Detection(
        val text: String,
        val bounds: RectF,
        val frameDeltaMs: Long,
        val count: Int,
        val previousBounds: RectF?,
        val isTrackingBitmap: Boolean = false,
        val isDialable: Boolean = false
    )
}
