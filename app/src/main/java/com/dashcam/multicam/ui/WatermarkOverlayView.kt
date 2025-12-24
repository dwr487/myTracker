package com.dashcam.multicam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.dashcam.multicam.model.WatermarkConfig
import com.dashcam.multicam.model.WatermarkPosition

/**
 * 水印叠加View
 * 在摄像头预览上叠加显示水印信息
 */
class WatermarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    private val backgroundPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private var watermarkConfig: WatermarkConfig = WatermarkConfig()
    private var watermarkLines: List<String> = emptyList()

    private val textBounds = Rect()

    /**
     * 更新水印配置
     */
    fun updateConfig(config: WatermarkConfig) {
        watermarkConfig = config
        textPaint.textSize = config.textSize * resources.displayMetrics.density
        textPaint.color = config.textColor
        backgroundPaint.color = config.backgroundColor
        invalidate()
    }

    /**
     * 更新水印文本
     */
    fun updateText(lines: List<String>) {
        watermarkLines = lines
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!watermarkConfig.enabled || watermarkLines.isEmpty()) {
            return
        }

        // 计算文本尺寸
        var maxWidth = 0f
        var totalHeight = 0f
        val lineHeights = mutableListOf<Float>()

        watermarkLines.forEach { line ->
            textPaint.getTextBounds(line, 0, line.length, textBounds)
            maxWidth = maxOf(maxWidth, textBounds.width().toFloat())
            val lineHeight = textBounds.height().toFloat()
            lineHeights.add(lineHeight)
            totalHeight += lineHeight
        }

        val padding = watermarkConfig.padding * resources.displayMetrics.density
        val totalWidth = maxWidth + padding * 2
        val totalHeightWithPadding = totalHeight + padding * 2 + (watermarkLines.size - 1) * padding / 2

        // 计算水印位置
        val (x, y) = when (watermarkConfig.position) {
            WatermarkPosition.TOP_LEFT -> {
                Pair(padding, padding)
            }
            WatermarkPosition.TOP_RIGHT -> {
                Pair(width - totalWidth, padding)
            }
            WatermarkPosition.BOTTOM_LEFT -> {
                Pair(padding, height - totalHeightWithPadding)
            }
            WatermarkPosition.BOTTOM_RIGHT -> {
                Pair(width - totalWidth, height - totalHeightWithPadding)
            }
        }

        // 绘制背景
        canvas.drawRect(
            x,
            y,
            x + totalWidth,
            y + totalHeightWithPadding,
            backgroundPaint
        )

        // 绘制文本
        var currentY = y + padding
        watermarkLines.forEachIndexed { index, line ->
            textPaint.getTextBounds(line, 0, line.length, textBounds)
            currentY += textBounds.height()
            canvas.drawText(line, x + padding, currentY, textPaint)
            currentY += padding / 2
        }
    }

    /**
     * 设置水印可见性
     */
    fun setWatermarkVisible(visible: Boolean) {
        visibility = if (visible && watermarkConfig.enabled) {
            VISIBLE
        } else {
            GONE
        }
    }
}
