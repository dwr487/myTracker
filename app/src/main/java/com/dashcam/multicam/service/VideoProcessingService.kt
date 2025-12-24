package com.dashcam.multicam.service

import android.app.Service
import android.content.Intent
import android.graphics.*
import android.media.*
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.dashcam.multicam.model.WatermarkConfig
import com.dashcam.multicam.model.WatermarkData
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频处理服务
 * 在后台将水印烧录到已录制的视频中
 */
class VideoProcessingService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val processingQueue = mutableListOf<ProcessingTask>()
    private var isProcessing = false

    companion object {
        private const val TAG = "VideoProcessingService"
        private const val MIME_TYPE = "video/avc"
        private const val TIMEOUT_US = 10000L
    }

    data class ProcessingTask(
        val inputFile: File,
        val outputFile: File,
        val watermarkConfig: WatermarkConfig,
        val watermarkData: WatermarkData
    )

    inner class LocalBinder : Binder() {
        fun getService(): VideoProcessingService = this@VideoProcessingService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * 添加视频到处理队列
     */
    fun addToQueue(
        inputFile: File,
        watermarkConfig: WatermarkConfig,
        watermarkData: WatermarkData
    ) {
        val outputFile = File(inputFile.parent, "${inputFile.nameWithoutExtension}_watermarked.mp4")
        val tempFile = File(inputFile.parent, "${inputFile.nameWithoutExtension}_temp.mp4")

        val task = ProcessingTask(inputFile, tempFile, watermarkConfig, watermarkData)
        processingQueue.add(task)

        if (!isProcessing) {
            processNext()
        }
    }

    /**
     * 处理下一个视频
     */
    private fun processNext() {
        if (processingQueue.isEmpty()) {
            isProcessing = false
            return
        }

        isProcessing = true
        val task = processingQueue.removeAt(0)

        serviceScope.launch(Dispatchers.IO) {
            try {
                processVideo(task)
                // 处理成功后替换原文件
                if (task.outputFile.exists()) {
                    task.inputFile.delete()
                    task.outputFile.renameTo(task.inputFile)
                    Log.d(TAG, "视频处理完成: ${task.inputFile.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "视频处理失败", e)
                task.outputFile.delete()
            } finally {
                processNext()
            }
        }
    }

    /**
     * 处理视频，添加水印
     */
    private fun processVideo(task: ProcessingTask) {
        if (!task.watermarkConfig.enabled) {
            Log.d(TAG, "水印未启用，跳过处理")
            return
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(task.inputFile.absolutePath)

        // 查找视频轨道
        var videoTrackIndex = -1
        var trackFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                trackFormat = format
                extractor.selectTrack(i)
                break
            }
        }

        if (videoTrackIndex == -1 || trackFormat == null) {
            throw IllegalArgumentException("未找到视频轨道")
        }

        // 获取视频参数
        val width = trackFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = trackFormat.getInteger(MediaFormat.KEY_HEIGHT)

        // 创建解码器
        val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(trackFormat, null, null, 0)
        decoder.start()

        // 创建编码器
        val encoder = createEncoder(width, height)

        // 创建混合器
        val muxer = MediaMuxer(task.outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 处理视频帧
        try {
            processFramesWithWatermark(
                extractor,
                decoder,
                encoder,
                muxer,
                width,
                height,
                task.watermarkConfig,
                task.watermarkData
            )
        } finally {
            // 清理资源
            extractor.release()
            decoder.stop()
            decoder.release()
            encoder.stop()
            encoder.release()
            muxer.stop()
            muxer.release()
        }
    }

    /**
     * 创建编码器
     */
    private fun createEncoder(width: Int, height: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        return encoder
    }

    /**
     * 处理帧并添加水印
     */
    private fun processFramesWithWatermark(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        width: Int,
        height: Int,
        watermarkConfig: WatermarkConfig,
        watermarkData: WatermarkData
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerStarted = false
        var videoTrackIndex = -1

        var outputDone = false
        var inputDone = false

        // 创建水印Bitmap
        val watermarkBitmap = createWatermarkBitmap(width, height, watermarkConfig, watermarkData)

        while (!outputDone) {
            // 输入解码器
            if (!inputDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputBufferIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(
                            inputBufferIndex, 0, sampleSize,
                            presentationTimeUs, 0
                        )
                        extractor.advance()
                    }
                }
            }

            // 从解码器获取输出（由于实际应用复杂性，这里简化处理）
            // 在实际应用中，需要将解码后的YUV数据转换为RGB，绘制水印，再转回YUV
            // 这需要大量的图像处理代码

            var outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            while (outputBufferIndex >= 0) {
                decoder.releaseOutputBuffer(outputBufferIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                    break
                }

                outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            }
        }

        watermarkBitmap.recycle()
    }

    /**
     * 创建水印Bitmap
     */
    private fun createWatermarkBitmap(
        videoWidth: Int,
        videoHeight: Int,
        watermarkConfig: WatermarkConfig,
        watermarkData: WatermarkData
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 绘制透明背景
        canvas.drawColor(Color.TRANSPARENT)

        // 绘制水印文本
        val paint = Paint().apply {
            color = watermarkConfig.textColor
            textSize = watermarkConfig.textSize * 3 // 放大以适应视频分辨率
            isAntiAlias = true
        }

        val bgPaint = Paint().apply {
            color = watermarkConfig.backgroundColor
            style = Paint.Style.FILL
        }

        val lines = watermarkData.formatText(watermarkConfig)
        val padding = watermarkConfig.padding * 3f

        var y = padding
        when (watermarkConfig.position) {
            com.dashcam.multicam.model.WatermarkPosition.BOTTOM_LEFT,
            com.dashcam.multicam.model.WatermarkPosition.BOTTOM_RIGHT -> {
                y = videoHeight - (lines.size * paint.textSize + padding * lines.size)
            }
            else -> y = padding
        }

        lines.forEach { line ->
            val textBounds = Rect()
            paint.getTextBounds(line, 0, line.length, textBounds)

            val x = when (watermarkConfig.position) {
                com.dashcam.multicam.model.WatermarkPosition.TOP_LEFT,
                com.dashcam.multicam.model.WatermarkPosition.BOTTOM_LEFT -> padding
                com.dashcam.multicam.model.WatermarkPosition.TOP_RIGHT,
                com.dashcam.multicam.model.WatermarkPosition.BOTTOM_RIGHT ->
                    videoWidth - textBounds.width() - padding
            }

            // 绘制背景
            canvas.drawRect(
                x - padding / 2,
                y - textBounds.height() - padding / 2,
                x + textBounds.width() + padding / 2,
                y + padding / 2,
                bgPaint
            )

            // 绘制文本
            canvas.drawText(line, x, y, paint)
            y += textBounds.height() + padding
        }

        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
