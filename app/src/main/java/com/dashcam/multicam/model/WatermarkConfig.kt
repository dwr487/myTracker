package com.dashcam.multicam.model

import android.graphics.Color

/**
 * 水印配置
 */
data class WatermarkConfig(
    val enabled: Boolean = true,
    val showTimestamp: Boolean = true,
    val showGps: Boolean = true,
    val showSpeed: Boolean = true,
    val showCameraName: Boolean = true,
    val customText: String = "",
    val position: WatermarkPosition = WatermarkPosition.TOP_LEFT,
    val textSize: Float = 14f,
    val textColor: Int = Color.WHITE,
    val backgroundColor: Int = Color.parseColor("#80000000"), // 半透明黑色
    val padding: Int = 8
)

/**
 * 水印位置
 */
enum class WatermarkPosition(val displayName: String) {
    TOP_LEFT("左上角"),
    TOP_RIGHT("右上角"),
    BOTTOM_LEFT("左下角"),
    BOTTOM_RIGHT("右下角")
}

/**
 * 水印数据
 */
data class WatermarkData(
    val timestamp: Long = System.currentTimeMillis(),
    val cameraPosition: CameraPosition? = null,
    val gpsData: GpsData? = null,
    val customText: String = ""
) {
    /**
     * 格式化为显示文本
     */
    fun formatText(config: WatermarkConfig): List<String> {
        val lines = mutableListOf<String>()

        // 时间戳
        if (config.showTimestamp) {
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            lines.add(dateFormat.format(java.util.Date(timestamp)))
        }

        // 摄像头名称
        if (config.showCameraName && cameraPosition != null) {
            lines.add("摄像头: ${cameraPosition.displayName}")
        }

        // GPS坐标
        if (config.showGps && gpsData != null) {
            lines.add("位置: ${String.format("%.6f", gpsData.latitude)}, ${String.format("%.6f", gpsData.longitude)}")
            lines.add("高度: ${String.format("%.1f", gpsData.altitude)}m")
        }

        // 速度
        if (config.showSpeed && gpsData != null) {
            val speedKmh = gpsData.speed * 3.6f // m/s to km/h
            lines.add("速度: ${String.format("%.1f", speedKmh)} km/h")
            if (gpsData.bearing != 0f) {
                lines.add("方向: ${String.format("%.1f", gpsData.bearing)}°")
            }
        }

        // 自定义文本
        if (config.customText.isNotEmpty()) {
            lines.add(config.customText)
        }

        if (customText.isNotEmpty() && customText != config.customText) {
            lines.add(customText)
        }

        return lines
    }
}
