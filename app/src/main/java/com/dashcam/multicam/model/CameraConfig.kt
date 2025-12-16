package com.dashcam.multicam.model

/**
 * 摄像头配置
 */
data class CameraConfig(
    val cameraId: String,
    val position: CameraPosition,
    val resolution: VideoResolution = VideoResolution.HD_1080P,
    val fps: Int = 30,
    val bitrate: Int = 8_000_000 // 8 Mbps
)

/**
 * 摄像头位置
 */
enum class CameraPosition(val displayName: String) {
    FRONT("前摄像头"),
    REAR("后摄像头"),
    LEFT("左摄像头"),
    RIGHT("右摄像头")
}

/**
 * 视频分辨率
 */
enum class VideoResolution(val width: Int, val height: Int, val displayName: String) {
    HD_720P(1280, 720, "720P"),
    HD_1080P(1920, 1080, "1080P"),
    HD_2K(2560, 1440, "2K"),
    UHD_4K(3840, 2160, "4K")
}

/**
 * GPS数据
 */
data class GpsData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val timestamp: Long
)

/**
 * 传感器数据（加速度计）
 */
data class SensorData(
    val x: Float,
    val y: Float,
    val z: Float,
    val magnitude: Float,
    val timestamp: Long
)

/**
 * 录制状态
 */
enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED,
    ERROR
}

/**
 * 视频文件信息
 */
data class VideoFileInfo(
    val fileName: String,
    val filePath: String,
    val cameraPosition: CameraPosition,
    val startTime: Long,
    val endTime: Long,
    val fileSize: Long,
    val isProtected: Boolean = false, // 紧急视频保护标记
    val gpsData: GpsData? = null
)
