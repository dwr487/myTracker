package com.dashcam.multicam.utils

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.dashcam.multicam.model.WatermarkConfig
import com.dashcam.multicam.model.WatermarkData
import com.dashcam.multicam.model.WatermarkPosition
import java.io.File

/**
 * FFmpeg水印烧录器
 * 使用FFmpegKit将水印永久烧录到视频文件中
 */
class FFmpegWatermarkBurner(
    private val watermarkConfig: WatermarkConfig
) {

    companion object {
        private const val TAG = "FFmpegWatermarkBurner"
    }

    /**
     * 将水印烧录到视频中
     * @param inputFile 输入视频文件
     * @param outputFile 输出视频文件
     * @param watermarkData 水印数据
     * @return 是否成功
     */
    fun burnWatermark(
        inputFile: File,
        outputFile: File,
        watermarkData: WatermarkData
    ): Boolean {
        if (!watermarkConfig.enabled) {
            Log.d(TAG, "水印未启用")
            return false
        }

        if (!inputFile.exists()) {
            Log.e(TAG, "输入文件不存在: ${inputFile.absolutePath}")
            return false
        }

        try {
            // 构建水印文本
            val watermarkLines = watermarkData.formatText(watermarkConfig)

            // 构建FFmpeg命令
            val command = buildFFmpegCommand(inputFile, outputFile, watermarkLines)

            Log.d(TAG, "开始烧录水印: ${inputFile.name}")
            Log.d(TAG, "FFmpeg命令: $command")

            // 执行FFmpeg命令
            val session = FFmpegKit.execute(command)

            if (ReturnCode.isSuccess(session.returnCode)) {
                Log.d(TAG, "水印烧录成功: ${outputFile.name}")
                return true
            } else {
                Log.e(TAG, "水印烧录失败")
                Log.e(TAG, "返回码: ${session.returnCode}")
                Log.e(TAG, "输出: ${session.output}")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "烧录水印时发生异常", e)
            return false
        }
    }

    /**
     * 构建FFmpeg命令
     */
    private fun buildFFmpegCommand(
        inputFile: File,
        outputFile: File,
        watermarkLines: List<String>
    ): String {
        // 转义文本中的特殊字符
        val escapedText = watermarkLines.joinToString("\\n") { line ->
            line.replace("'", "\\'")
                .replace(":", "\\:")
                .replace(",", "\\,")
        }

        // 确定水印位置
        val position = when (watermarkConfig.position) {
            WatermarkPosition.TOP_LEFT -> "x=10:y=10"
            WatermarkPosition.TOP_RIGHT -> "x=w-tw-10:y=10"
            WatermarkPosition.BOTTOM_LEFT -> "x=10:y=h-th-10"
            WatermarkPosition.BOTTOM_RIGHT -> "x=w-tw-10:y=h-th-10"
        }

        // 转换颜色为十六进制
        val textColor = String.format("%06X", 0xFFFFFF and watermarkConfig.textColor)
        val bgColor = String.format("%06X", 0xFFFFFF and watermarkConfig.backgroundColor)
        val bgOpacity = String.format("%.2f", (watermarkConfig.backgroundColor ushr 24) / 255.0)

        // 构建drawtext滤镜
        val drawtext = buildString {
            append("drawtext=")
            append("text='$escapedText':")
            append("fontsize=${(watermarkConfig.textSize * 2).toInt()}:")
            append("fontcolor=0x$textColor:")
            append("box=1:")
            append("boxcolor=0x${bgColor}@$bgOpacity:")
            append("boxborderw=${watermarkConfig.padding}:")
            append(position)
        }

        // 构建完整命令
        return buildString {
            append("-i \"${inputFile.absolutePath}\" ")
            append("-vf \"$drawtext\" ")
            append("-c:v libx264 ")  // 使用H.264编码
            append("-preset ultrafast ")  // 使用快速预设
            append("-crf 23 ")  // 质量参数
            append("-c:a copy ")  // 复制音频流，不重新编码
            append("-y ")  // 覆盖输出文件
            append("\"${outputFile.absolutePath}\"")
        }
    }

    /**
     * 检查FFmpeg是否可用
     */
    fun isFFmpegAvailable(): Boolean {
        return try {
            val session = FFmpegKit.execute("-version")
            ReturnCode.isSuccess(session.returnCode)
        } catch (e: Exception) {
            Log.e(TAG, "检查FFmpeg可用性失败", e)
            false
        }
    }

    /**
     * 获取FFmpeg版本信息
     */
    fun getFFmpegVersion(): String? {
        return try {
            val session = FFmpegKit.execute("-version")
            if (ReturnCode.isSuccess(session.returnCode)) {
                session.output?.lines()?.firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取FFmpeg版本失败", e)
            null
        }
    }
}
