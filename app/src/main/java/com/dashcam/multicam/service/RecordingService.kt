package com.dashcam.multicam.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dashcam.multicam.R
import com.dashcam.multicam.manager.*
import com.dashcam.multicam.model.CameraPosition
import com.dashcam.multicam.model.RecordingState
import com.dashcam.multicam.utils.VideoRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 录制服务
 * 负责多摄像头同时录制的后台服务
 */
class RecordingService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var multiCameraManager: MultiCameraManager
    private lateinit var storageManager: StorageManager
    private lateinit var gpsManager: GpsManager
    private lateinit var sensorManager: SensorManager
    private lateinit var watermarkManager: WatermarkManager

    private val videoRecorders = mutableMapOf<CameraPosition, VideoRecorder>()

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private var segmentJob: Job? = null
    private var currentSegmentFiles = mutableMapOf<CameraPosition, File>()

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "DashcamRecording"
        private const val SEGMENT_DURATION_MS = 60_000L // 每段1分钟
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")

        // 初始化管理器
        multiCameraManager = MultiCameraManager(this)
        storageManager = StorageManager(this)
        gpsManager = GpsManager(this)
        sensorManager = SensorManager(this)
        watermarkManager = WatermarkManager(this)

        // 初始化摄像头
        multiCameraManager.initialize()

        // 启动GPS
        gpsManager.startLocationUpdates()

        // 启动传感器
        sensorManager.initialize()
        sensorManager.startListening()

        // 监听碰撞事件
        serviceScope.launch {
            sensorManager.collisionDetected.collect { sensorData ->
                Log.w(TAG, "检测到碰撞，保护当前视频片段")
                protectCurrentSegments()
            }
        }

        // 监听GPS数据并更新水印
        serviceScope.launch {
            gpsManager.gpsData.collect { gpsData ->
                watermarkManager.updateGpsDataForAll(gpsData)
            }
        }

        // 创建通知渠道
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动命令")

        // 启动前台服务
        val notification = createNotification("准备录制...")
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * 开始录制所有摄像头
     */
    fun startRecording() {
        if (_recordingState.value == RecordingState.RECORDING) {
            Log.w(TAG, "已在录制中")
            return
        }

        serviceScope.launch {
            try {
                _recordingState.value = RecordingState.RECORDING

                // 获取所有摄像头配置
                val cameras = multiCameraManager.getAvailableCameras()
                Log.d(TAG, "开始录制 ${cameras.size} 个摄像头")

                // 为每个摄像头创建录制器
                cameras.forEach { config ->
                    val recorder = VideoRecorder(this@RecordingService, config)
                    videoRecorders[config.position] = recorder
                }

                // 开始第一个片段
                startNewSegment()

                // 启动分段录制
                startSegmentRecording()

                updateNotification("正在录制 - ${cameras.size}个摄像头")

            } catch (e: Exception) {
                Log.e(TAG, "开始录制失败", e)
                _recordingState.value = RecordingState.ERROR
            }
        }
    }

    /**
     * 停止录制
     */
    fun stopRecording() {
        if (_recordingState.value != RecordingState.RECORDING) {
            Log.w(TAG, "未在录制中")
            return
        }

        serviceScope.launch {
            try {
                // 停止分段录制
                segmentJob?.cancel()

                // 停止所有录制器
                videoRecorders.values.forEach { recorder ->
                    recorder.stopRecording()
                }
                videoRecorders.clear()

                _recordingState.value = RecordingState.IDLE
                updateNotification("录制已停止")

            } catch (e: Exception) {
                Log.e(TAG, "停止录制失败", e)
            }
        }
    }

    /**
     * 开始新的录制片段
     */
    private suspend fun startNewSegment() {
        // 检查存储空间
        if (storageManager.needsCleanup()) {
            Log.d(TAG, "执行存储清理")
            withContext(Dispatchers.IO) {
                storageManager.performCleanup()
            }
        }

        // 为当前片段生成字幕文件（如果启用）
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val shouldGenerateSubtitle = prefs.getBoolean("watermark_generate_subtitle", true)

        if (watermarkManager.getCurrentConfig().enabled && shouldGenerateSubtitle) {
            withContext(Dispatchers.IO) {
                currentSegmentFiles.forEach { (position, file) ->
                    if (file.exists()) {
                        generateSubtitleForVideo(file, position)
                    }
                }
            }
        }

        // 停止当前片段
        videoRecorders.values.forEach { it.stopRecording() }
        currentSegmentFiles.clear()

        // 开始新片段
        videoRecorders.forEach { (position, recorder) ->
            val file = storageManager.getVideoFilePath(position, isProtected = false)
            currentSegmentFiles[position] = file
            recorder.startRecording(file)
            Log.d(TAG, "开始录制新片段: ${position.displayName} -> ${file.name}")
        }
    }

    /**
     * 为视频生成字幕文件
     */
    private fun generateSubtitleForVideo(videoFile: File, position: CameraPosition) {
        try {
            val watermarkData = watermarkManager.getWatermarkData(position)
            val generator = com.dashcam.multicam.utils.WatermarkSubtitleGenerator(
                watermarkManager.getCurrentConfig()
            )
            generator.createSubtitleForVideo(videoFile, watermarkData, SEGMENT_DURATION_MS)
            Log.d(TAG, "字幕文件已生成: ${videoFile.nameWithoutExtension}.srt")
        } catch (e: Exception) {
            Log.e(TAG, "生成字幕文件失败: ${videoFile.name}", e)
        }
    }

    /**
     * 启动分段录制
     */
    private fun startSegmentRecording() {
        segmentJob = serviceScope.launch {
            while (isActive) {
                delay(SEGMENT_DURATION_MS)
                Log.d(TAG, "切换到新片段")
                startNewSegment()
            }
        }
    }

    /**
     * 保护当前录制片段（碰撞时）
     */
    private fun protectCurrentSegments() {
        currentSegmentFiles.values.forEach { file ->
            if (file.exists()) {
                storageManager.protectVideo(file)
            }
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "行车记录仪录制",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示行车记录仪录制状态"
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("多摄像头行车记录仪")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 更新通知
     */
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 获取GPS管理器
     */
    fun getGpsManager(): GpsManager = gpsManager

    /**
     * 获取存储管理器
     */
    fun getStorageManager(): StorageManager = storageManager

    /**
     * 获取摄像头管理器
     */
    fun getCameraManager(): MultiCameraManager = multiCameraManager

    /**
     * 获取水印管理器
     */
    fun getWatermarkManager(): WatermarkManager = watermarkManager

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")

        // 停止录制
        stopRecording()

        // 停止GPS
        gpsManager.stopLocationUpdates()

        // 停止传感器
        sensorManager.stopListening()

        // 取消协程
        serviceScope.cancel()
    }
}
