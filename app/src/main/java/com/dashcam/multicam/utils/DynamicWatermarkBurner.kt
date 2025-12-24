package com.dashcam.multicam.utils

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * 动态水印烧录器
 * 使用FFmpeg将ASS字幕永久烧录到视频中，实现动态水印效果
 */
class DynamicWatermarkBurner {

    companion object {
        private const val TAG = "DynamicWatermarkBurner"
    }

    /**
     * 将ASS字幕烧录到视频中
     * @param inputVideo 输入视频文件
     * @param inputASS ASS字幕文件
     * @param outputVideo 输出视频文件
     * @return 是否成功
     */
    fun burnWatermark(
        inputVideo: File,
        inputASS: File,
        outputVideo: File
    ): Boolean {
        if (!inputVideo.exists()) {
            Log.e(TAG, "输入视频文件不存在: ${inputVideo.absolutePath}")
            return false
        }

        if (!inputASS.exists()) {
            Log.e(TAG, "ASS字幕文件不存在: ${inputASS.absolutePath}")
            return false
        }

        try {
            // 构建FFmpeg命令
            val command = buildFFmpegCommand(inputVideo, inputASS, outputVideo)

            Log.d(TAG, "开始烧录动态水印: ${inputVideo.name}")
            Log.d(TAG, "ASS文件: ${inputASS.name}")
            Log.d(TAG, "FFmpeg命令: $command")

            // 执行FFmpeg命令
            val session = FFmpegKit.execute(command)

            if (ReturnCode.isSuccess(session.returnCode)) {
                Log.d(TAG, "动态水印烧录成功: ${outputVideo.name}")
                return true
            } else {
                Log.e(TAG, "动态水印烧录失败")
                Log.e(TAG, "返回码: ${session.returnCode}")
                Log.e(TAG, "输出: ${session.output}")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "烧录动态水印时发生异常", e)
            return false
        }
    }

    /**
     * 构建FFmpeg命令
     * 使用ass滤镜将字幕烧录到视频中
     */
    private fun buildFFmpegCommand(
        inputVideo: File,
        inputASS: File,
        outputVideo: File
    ): String {
        // ASS文件路径需要转义（Windows路径的反斜杠和冒号）
        val assPath = inputASS.absolutePath
            .replace("\\", "\\\\")
            .replace(":", "\\:")

        return buildString {
            append("-i \"${inputVideo.absolutePath}\" ")
            // 使用ass滤镜烧录字幕
            append("-vf \"ass='$assPath'\" ")
            // 视频编码设置
            append("-c:v libx264 ")        // H.264编码
            append("-preset medium ")      // 中等预设（平衡速度和质量）
            append("-crf 23 ")              // 质量参数（18-28，越小质量越好）
            // 音频直接复制，不重新编码
            append("-c:a copy ")
            // 覆盖输出文件
            append("-y ")
            append("\"${outputVideo.absolutePath}\"")
        }
    }

    /**
     * 获取视频时长（秒）
     */
    fun getVideoDuration(videoFile: File): Long {
        if (!videoFile.exists()) {
            Log.e(TAG, "视频文件不存在: ${videoFile.absolutePath}")
            return 0
        }

        try {
            // 使用ffprobe获取视频时长
            val command = "-v error -show_entries format=duration " +
                    "-of default=noprint_wrappers=1:nokey=1 \"${videoFile.absolutePath}\""

            val session = FFmpegKit.execute(command)

            if (ReturnCode.isSuccess(session.returnCode)) {
                val output = session.output?.trim()
                if (output != null && output.isNotEmpty()) {
                    val durationSeconds = output.toDoubleOrNull() ?: 0.0
                    return (durationSeconds * 1000).toLong()  // 转换为毫秒
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取视频时长失败", e)
        }

        return 0
    }
}
