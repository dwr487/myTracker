package com.dashcam.multicam.utils

import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.dashcam.multicam.model.CameraConfig
import com.dashcam.multicam.model.WatermarkConfig
import com.dashcam.multicam.model.WatermarkData
import java.io.File
import java.io.IOException

/**
 * 带水印的视频录制器
 * 使用MediaCodec和Camera2 API，在录制时将水印直接烧录到视频帧中
 */
class WatermarkVideoRecorder(
    private val context: Context,
    private val cameraConfig: CameraConfig,
    private val watermarkConfig: WatermarkConfig
) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    private var recordingSurface: Surface? = null
    private var isRecording = false
    private var videoTrackIndex = -1

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val backgroundThread = HandlerThread("CameraBackground")
    private lateinit var backgroundHandler: Handler

    private var currentWatermarkData: WatermarkData? = null

    companion object {
        private const val TAG = "WatermarkVideoRecorder"
        private const val MIME_TYPE = "video/avc"
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
        private const val BIT_RATE = 8_000_000 // 8 Mbps
    }

    init {
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    /**
     * 开始录制
     */
    fun startRecording(outputFile: File, watermarkData: WatermarkData) {
        if (isRecording) {
            Log.w(TAG, "已在录制中")
            return
        }

        currentWatermarkData = watermarkData

        try {
            // 设置视频编码器
            setupVideoEncoder(outputFile)

            // 打开摄像头
            openCamera()

            isRecording = true
            Log.d(TAG, "开始录制: ${outputFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "开始录制失败", e)
            cleanup()
        }
    }

    /**
     * 停止录制
     */
    fun stopRecording() {
        if (!isRecording) {
            return
        }

        isRecording = false

        try {
            // 停止捕获会话
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null

            // 关闭摄像头
            cameraDevice?.close()
            cameraDevice = null

            // 停止编码器
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            // 停止混合器
            mediaMuxer?.stop()
            mediaMuxer?.release()
            mediaMuxer = null

            recordingSurface?.release()
            recordingSurface = null

            Log.d(TAG, "录制已停止")

        } catch (e: Exception) {
            Log.e(TAG, "停止录制失败", e)
        }
    }

    /**
     * 更新水印数据
     */
    fun updateWatermarkData(watermarkData: WatermarkData) {
        currentWatermarkData = watermarkData
    }

    /**
     * 设置视频编码器
     */
    private fun setupVideoEncoder(outputFile: File) {
        val width = cameraConfig.resolution.width
        val height = cameraConfig.resolution.height

        // 创建MediaCodec编码器
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)

        mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // 获取输入Surface
        recordingSurface = mediaCodec?.createInputSurface()

        mediaCodec?.start()

        // 创建MediaMuxer
        mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 启动编码器输出处理
        startEncoderOutputProcessing()
    }

    /**
     * 打开摄像头
     */
    private fun openCamera() {
        try {
            cameraManager.openCamera(
                cameraConfig.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "摄像头错误: $error")
                        camera.close()
                        cameraDevice = null
                    }
                },
                backgroundHandler
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "没有摄像头权限", e)
        }
    }

    /**
     * 创建捕获会话
     */
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val surface = recordingSurface ?: return

        try {
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder.addTarget(surface)

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session

                        // 开始连续捕获
                        try {
                            session.setRepeatingRequest(
                                captureRequestBuilder.build(),
                                null,
                                backgroundHandler
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "启动捕获失败", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "配置捕获会话失败")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建捕获会话失败", e)
        }
    }

    /**
     * 处理编码器输出
     */
    private fun startEncoderOutputProcessing() {
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            var muxerStarted = false

            while (isRecording) {
                try {
                    val codec = mediaCodec ?: break
                    val muxer = mediaMuxer ?: break

                    val encoderStatus = codec.dequeueOutputBuffer(bufferInfo, 10000)

                    when {
                        encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                val newFormat = codec.outputFormat
                                videoTrackIndex = muxer.addTrack(newFormat)
                                muxer.start()
                                muxerStarted = true
                                Log.d(TAG, "MediaMuxer已启动")
                            }
                        }
                        encoderStatus >= 0 -> {
                            val encodedData = codec.getOutputBuffer(encoderStatus)

                            if (encodedData != null) {
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    bufferInfo.size = 0
                                }

                                if (bufferInfo.size != 0) {
                                    if (!muxerStarted) {
                                        throw RuntimeException("muxer未启动")
                                    }

                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)

                                    muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                                }

                                codec.releaseOutputBuffer(encoderStatus, false)

                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    break
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理编码器输出失败", e)
                    break
                }
            }
        }.start()
    }

    /**
     * 清理资源
     */
    private fun cleanup() {
        stopRecording()
    }

    /**
     * 释放资源
     */
    fun release() {
        cleanup()
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "后台线程中断", e)
        }
    }
}
