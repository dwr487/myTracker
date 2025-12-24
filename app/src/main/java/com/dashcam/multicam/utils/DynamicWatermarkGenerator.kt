package com.dashcam.multicam.utils

import android.util.Log
import com.dashcam.multicam.model.WatermarkConfig
import com.dashcam.multicam.model.WatermarkData
import com.dashcam.multicam.model.WatermarkPosition
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 动态水印生成器
 * 生成ASS格式字幕文件，包含每秒变化的时间和GPS信息
 */
class DynamicWatermarkGenerator(
    private val watermarkConfig: WatermarkConfig
) {

    companion object {
        private const val TAG = "DynamicWatermarkGenerator"
        private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    // 存储每秒的水印数据
    private val watermarkDataPoints = mutableListOf<TimedWatermarkData>()

    data class TimedWatermarkData(
        val timestampSeconds: Int,  // 视频中的秒数
        val watermarkData: WatermarkData
    )

    /**
     * 添加一个时间点的水印数据
     * @param videoTimestampMs 视频中的时间戳（毫秒）
     * @param watermarkData 水印数据
     */
    fun addDataPoint(videoTimestampMs: Long, watermarkData: WatermarkData) {
        val seconds = (videoTimestampMs / 1000).toInt()

        // 避免重复添加同一秒的数据
        if (watermarkDataPoints.none { it.timestampSeconds == seconds }) {
            watermarkDataPoints.add(
                TimedWatermarkData(seconds, watermarkData.copy())
            )
        }
    }

    /**
     * 生成ASS字幕文件
     * @param outputFile 输出的ASS文件
     * @param videoDurationMs 视频总时长（毫秒）
     */
    fun generateASSFile(outputFile: File, videoDurationMs: Long): Boolean {
        if (watermarkDataPoints.isEmpty()) {
            Log.w(TAG, "没有水印数据点")
            return false
        }

        try {
            outputFile.bufferedWriter().use { writer ->
                // 写入ASS文件头
                writeASSHeader(writer, videoDurationMs)

                // 写入每个时间点的水印
                writeASSEvents(writer, videoDurationMs)
            }

            Log.d(TAG, "ASS字幕文件生成成功: ${outputFile.absolutePath}")
            Log.d(TAG, "共 ${watermarkDataPoints.size} 个数据点")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "生成ASS文件失败", e)
            return false
        }
    }

    /**
     * 写入ASS文件头部信息
     */
    private fun writeASSHeader(writer: java.io.BufferedWriter, videoDurationMs: Long) {
        writer.appendLine("[Script Info]")
        writer.appendLine("Title: Dashcam Watermark")
        writer.appendLine("ScriptType: v4.00+")
        writer.appendLine("WrapStyle: 0")
        writer.appendLine("PlayResX: 1920")  // 参考分辨率
        writer.appendLine("PlayResY: 1080")
        writer.appendLine("ScaledBorderAndShadow: yes")
        writer.appendLine("")

        // 定义样式
        writer.appendLine("[V4+ Styles]")
        writer.appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")

        val style = buildASSStyle()
        writer.appendLine(style)
        writer.appendLine("")

        // 事件部分
        writer.appendLine("[Events]")
        writer.appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
    }

    /**
     * 构建ASS样式字符串
     */
    private fun buildASSStyle(): String {
        // 提取颜色的ABGR值（ASS使用&HAABBGGRR格式）
        val textColor = convertColorToASS(watermarkConfig.textColor)
        val bgColor = convertColorToASS(watermarkConfig.backgroundColor)

        // 确定对齐方式
        val alignment = when (watermarkConfig.position) {
            WatermarkPosition.TOP_LEFT -> 7      // 左上
            WatermarkPosition.TOP_RIGHT -> 9     // 右上
            WatermarkPosition.BOTTOM_LEFT -> 1   // 左下
            WatermarkPosition.BOTTOM_RIGHT -> 3  // 右下
        }

        val fontSize = (watermarkConfig.textSize * 2).toInt()  // ASS字号通常需要放大

        return "Style: WatermarkStyle,Arial,$fontSize,$textColor,&H00FFFFFF,$bgColor,$bgColor," +
                "-1,0,0,0,100,100,0,0,3,2,1,$alignment,10,10,10,1"
    }

    /**
     * 将Android颜色转换为ASS格式颜色
     * Android: AARRGGBB
     * ASS: &HAABBGGRR
     */
    private fun convertColorToASS(color: Int): String {
        val a = (color shr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        return String.format("&H%02X%02X%02X%02X", a, b, g, r)
    }

    /**
     * 写入ASS事件（每秒一个事件）
     */
    private fun writeASSEvents(writer: java.io.BufferedWriter, videoDurationMs: Long) {
        // 排序数据点
        val sortedData = watermarkDataPoints.sortedBy { it.timestampSeconds }

        for (i in sortedData.indices) {
            val current = sortedData[i]
            val startTime = current.timestampSeconds

            // 结束时间是下一个数据点的时间，或视频结束时间
            val endTime = if (i < sortedData.size - 1) {
                sortedData[i + 1].timestampSeconds
            } else {
                (videoDurationMs / 1000).toInt()
            }

            // 格式化时间为ASS格式 (H:MM:SS.cc)
            val startTimeStr = formatASSTime(startTime)
            val endTimeStr = formatASSTime(endTime)

            // 生成水印文本
            val text = formatWatermarkText(current.watermarkData)

            // 写入事件
            writer.appendLine("Dialogue: 0,$startTimeStr,$endTimeStr,WatermarkStyle,,0,0,0,,$text")
        }
    }

    /**
     * 格式化ASS时间格式 (H:MM:SS.cc)
     */
    private fun formatASSTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%d:%02d:%02d.00", h, m, s)
    }

    /**
     * 格式化水印文本
     */
    private fun formatWatermarkText(data: WatermarkData): String {
        val lines = mutableListOf<String>()

        // 时间戳
        if (watermarkConfig.showTimestamp) {
            lines.add(timeFormat.format(data.timestamp))
        }

        // GPS坐标
        if (watermarkConfig.showGps && data.gpsData != null) {
            lines.add(String.format(
                "GPS: %.6f, %.6f",
                data.gpsData.latitude,
                data.gpsData.longitude
            ))
        }

        // 速度
        if (watermarkConfig.showSpeed && data.gpsData != null) {
            val speedKmh = data.gpsData.speed * 3.6  // m/s 转 km/h
            lines.add(String.format("速度: %.1f km/h", speedKmh))
        }

        // 摄像头名称
        if (watermarkConfig.showCameraName) {
            lines.add(data.cameraPosition.name)
        }

        // 自定义文本
        if (watermarkConfig.customText.isNotBlank()) {
            lines.add(watermarkConfig.customText)
        }

        // 使用\N进行换行（ASS格式）
        return lines.joinToString("\\N")
    }

    /**
     * 清空数据点
     */
    fun clear() {
        watermarkDataPoints.clear()
    }

    /**
     * 获取数据点数量
     */
    fun getDataPointCount(): Int = watermarkDataPoints.size
}
