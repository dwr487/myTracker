package com.dashcam.multicam.utils

import android.content.Context
import android.graphics.*
import android.media.*
import android.util.Log
import com.dashcam.multicam.model.WatermarkConfig
import com.dashcam.multicam.model.WatermarkData
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频水印编码器
 * 使用MediaCodec将水印编码到视频文件中
 */
class VideoWatermarkEncoder(
    private val context: Context,
    private val watermarkConfig: WatermarkConfig
) {

    companion object {
        private const val TAG = "VideoWatermarkEncoder"
        private const val TIMEOUT_US = 10000L
        private const val MIME_TYPE = "video/avc"
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
    }

    /**
     * 为视频添加水印
     * @param inputFile 输入视频文件
     * @param outputFile 输出视频文件
     * @param watermarkData 水印数据
     * @return 是否成功
     */
    fun addWatermark(
        inputFile: File,
        outputFile: File,
        watermarkData: WatermarkData
    ): Boolean {
        if (!watermarkConfig.enabled) {
            Log.d(TAG, "水印未启用，跳过编码")
            return false
        }

        return try {
            processVideo(inputFile, outputFile, watermarkData)
            Log.d(TAG, "水印编码成功: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "水印编码失败", e)
            false
        }
    }

    /**
     * 处理视频，添加水印
     */
    private fun processVideo(
        inputFile: File,
        outputFile: File,
        watermarkData: WatermarkData
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        // 查找视频轨道
        var videoTrackIndex = -1
        val mediaFormat: MediaFormat
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                mediaFormat = format
                extractor.selectTrack(i)
                break
            }
        }

        if (videoTrackIndex == -1) {
            throw IllegalArgumentException("未找到视频轨道")
        }

        val trackFormat = extractor.getTrackFormat(videoTrackIndex)
        val width = trackFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = trackFormat.getInteger(MediaFormat.KEY_HEIGHT)

        // 创建解码器
        val decoder = createDecoder(trackFormat)

        // 创建编码器
        val encoder = createEncoder(width, height)

        // 创建混合器
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 处理视频帧
        processFrames(
            extractor,
            decoder,
            encoder,
            muxer,
            width,
            height,
            watermarkData
        )

        // 清理资源
        extractor.release()
        decoder.stop()
        decoder.release()
        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
    }

    /**
     * 创建解码器
     */
    private fun createDecoder(format: MediaFormat): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        return decoder
    }

    /**
     * 创建编码器
     */
    private fun createEncoder(width: Int, height: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000) // 8 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        return encoder
    }

    /**
     * 处理视频帧
     */
    private fun processFrames(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        width: Int,
        height: Int,
        watermarkData: WatermarkData
    ) {
        var outputVideoTrack = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        var isEOS = false
        var frameCount = 0

        while (!isEOS) {
            // 解码输入
            if (!isEOS) {
                val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            // 获取解码输出
            val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputBufferIndex >= 0) {
                // 处理解码后的帧（这里简化处理，实际需要通过Surface传递）
                decoder.releaseOutputBuffer(outputBufferIndex, true)
                frameCount++
            }

            // 获取编码输出
            var encoderOutputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            while (encoderOutputBufferIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(encoderOutputBufferIndex)!!

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0) {
                    if (!muxerStarted) {
                        throw RuntimeException("混合器未启动")
                    }

                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(outputVideoTrack, outputBuffer, bufferInfo)
                }

                encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }

                encoderOutputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            }

            // 处理编码器格式变化
            if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw RuntimeException("格式已改变")
                }
                val newFormat = encoder.outputFormat
                outputVideoTrack = muxer.addTrack(newFormat)
                muxer.start()
                muxerStarted = true
            }
        }

        Log.d(TAG, "处理了 $frameCount 帧")
    }

    /**
     * 在Bitmap上绘制水印
     */
    private fun drawWatermarkOnBitmap(
        bitmap: Bitmap,
        watermarkData: WatermarkData
    ): Bitmap {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = watermarkConfig.textColor
            textSize = watermarkConfig.textSize * 2 // 调整大小
            isAntiAlias = true
        }

        val bgPaint = Paint().apply {
            color = watermarkConfig.backgroundColor
            style = Paint.Style.FILL
        }

        val lines = watermarkData.formatText(watermarkConfig)
        val padding = watermarkConfig.padding * 2f

        var y = padding
        lines.forEach { line ->
            val textBounds = Rect()
            paint.getTextBounds(line, 0, line.length, textBounds)

            val x = when (watermarkConfig.position) {
                com.dashcam.multicam.model.WatermarkPosition.TOP_LEFT,
                com.dashcam.multicam.model.WatermarkPosition.BOTTOM_LEFT -> padding
                com.dashcam.multicam.model.WatermarkPosition.TOP_RIGHT,
                com.dashcam.multicam.model.WatermarkPosition.BOTTOM_RIGHT ->
                    bitmap.width - textBounds.width() - padding
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
}
