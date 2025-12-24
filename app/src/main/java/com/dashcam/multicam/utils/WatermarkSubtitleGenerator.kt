package com.dashcam.multicam.utils

import android.util.Log
import com.dashcam.multicam.model.WatermarkConfig
import com.dashcam.multicam.model.WatermarkData
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 水印字幕生成器
 * 将水印信息导出为SRT字幕文件，可在播放器中显示
 */
class WatermarkSubtitleGenerator(
    private val watermarkConfig: WatermarkConfig
) {

    companion object {
        private const val TAG = "WatermarkSubtitleGen"
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss,SSS", Locale.getDefault())

    /**
     * 生成SRT字幕文件
     * @param videoFile 视频文件
     * @param watermarkData 水印数据列表（按时间排序）
     * @return 字幕文件
     */
    fun generateSubtitle(
        videoFile: File,
        watermarkDataList: List<Pair<Long, WatermarkData>>
    ): File? {
        if (!watermarkConfig.enabled || watermarkDataList.isEmpty()) {
            return null
        }

        try {
            val srtFile = File(videoFile.parent, "${videoFile.nameWithoutExtension}.srt")
            srtFile.bufferedWriter().use { writer ->
                watermarkDataList.forEachIndexed { index, (timestamp, data) ->
                    val startTime = timestamp
                    val endTime = if (index < watermarkDataList.size - 1) {
                        watermarkDataList[index + 1].first
                    } else {
                        timestamp + 1000 // 最后一帧显示1秒
                    }

                    // 字幕序号
                    writer.write("${index + 1}\n")

                    // 时间范围
                    writer.write("${formatTime(startTime)} --> ${formatTime(endTime)}\n")

                    // 水印内容
                    val lines = data.formatText(watermarkConfig)
                    lines.forEach { line ->
                        writer.write("$line\n")
                    }

                    // 空行分隔
                    writer.write("\n")
                }
            }

            Log.d(TAG, "字幕文件生成成功: ${srtFile.absolutePath}")
            return srtFile

        } catch (e: Exception) {
            Log.e(TAG, "生成字幕文件失败", e)
            return null
        }
    }

    /**
     * 格式化时间为SRT格式 (HH:mm:ss,SSS)
     */
    private fun formatTime(milliseconds: Long): String {
        val hours = milliseconds / 3600000
        val minutes = (milliseconds % 3600000) / 60000
        val seconds = (milliseconds % 60000) / 1000
        val millis = milliseconds % 1000

        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * 为单个视频文件创建字幕
     */
    fun createSubtitleForVideo(
        videoFile: File,
        startWatermarkData: WatermarkData,
        durationMs: Long = 60000L // 默认1分钟
    ): File? {
        // 创建简单的字幕，整个视频使用同一水印
        val dataList = listOf(
            0L to startWatermarkData,
            durationMs to startWatermarkData
        )
        return generateSubtitle(videoFile, dataList)
    }
}
