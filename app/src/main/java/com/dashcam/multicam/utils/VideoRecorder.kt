package com.dashcam.multicam.utils

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.video.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dashcam.multicam.model.CameraConfig
import com.dashcam.multicam.model.VideoResolution
import java.io.File
import java.util.concurrent.Executor

/**
 * 视频录制器
 * 使用CameraX API进行视频录制
 */
class VideoRecorder(
    private val context: Context,
    private val cameraConfig: CameraConfig
) {
    private var recording: Recording? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    companion object {
        private const val TAG = "VideoRecorder"
    }

    /**
     * 开始录制视频
     */
    fun startRecording(outputFile: File) {
        try {
            // 创建录制配置
            val qualitySelector = when (cameraConfig.resolution) {
                VideoResolution.HD_720P -> QualitySelector.from(Quality.HD)
                VideoResolution.HD_1080P -> QualitySelector.from(Quality.FHD)
                VideoResolution.HD_2K -> QualitySelector.from(Quality.UHD)
                VideoResolution.UHD_4K -> QualitySelector.from(Quality.UHD)
            }

            // 创建Recorder
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            // 创建输出选项
            val outputOptions = FileOutputOptions.Builder(outputFile).build()

            // 开始录制
            recording = recorder.prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.d(TAG, "录制开始: ${cameraConfig.position.displayName}")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                Log.e(TAG, "录制错误: ${event.error}", event.cause)
                            } else {
                                Log.d(TAG, "录制完成: ${outputFile.absolutePath}")
                            }
                        }
                        is VideoRecordEvent.Status -> {
                            // 录制状态更新
                        }
                    }
                }

            Log.d(TAG, "开始录制: ${cameraConfig.position.displayName} -> ${outputFile.name}")

        } catch (e: Exception) {
            Log.e(TAG, "开始录制失败: ${cameraConfig.position.displayName}", e)
        }
    }

    /**
     * 停止录制
     */
    fun stopRecording() {
        recording?.stop()
        recording = null
        Log.d(TAG, "停止录制: ${cameraConfig.position.displayName}")
    }

    /**
     * 暂停录制
     */
    fun pauseRecording() {
        recording?.pause()
        Log.d(TAG, "暂停录制: ${cameraConfig.position.displayName}")
    }

    /**
     * 恢复录制
     */
    fun resumeRecording() {
        recording?.resume()
        Log.d(TAG, "恢复录制: ${cameraConfig.position.displayName}")
    }

    /**
     * 是否正在录制
     */
    fun isRecording(): Boolean {
        return recording != null
    }

    /**
     * 释放资源
     */
    fun release() {
        stopRecording()
        videoCapture = null
    }
}
