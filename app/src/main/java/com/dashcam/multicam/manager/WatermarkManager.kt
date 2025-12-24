package com.dashcam.multicam.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.dashcam.multicam.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 水印管理器
 * 负责水印配置管理和数据更新
 */
class WatermarkManager(private val context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    private val _watermarkConfig = MutableStateFlow(loadConfig())
    val watermarkConfig: StateFlow<WatermarkConfig> = _watermarkConfig.asStateFlow()

    private val _watermarkData = MutableStateFlow<Map<CameraPosition, WatermarkData>>(emptyMap())
    val watermarkData: StateFlow<Map<CameraPosition, WatermarkData>> = _watermarkData.asStateFlow()

    companion object {
        private const val PREF_WATERMARK_ENABLED = "watermark_enabled"
        private const val PREF_SHOW_TIMESTAMP = "watermark_show_timestamp"
        private const val PREF_SHOW_GPS = "watermark_show_gps"
        private const val PREF_SHOW_SPEED = "watermark_show_speed"
        private const val PREF_SHOW_CAMERA_NAME = "watermark_show_camera_name"
        private const val PREF_CUSTOM_TEXT = "watermark_custom_text"
        private const val PREF_POSITION = "watermark_position"
        private const val PREF_TEXT_SIZE = "watermark_text_size"
    }

    /**
     * 从SharedPreferences加载配置
     */
    private fun loadConfig(): WatermarkConfig {
        return WatermarkConfig(
            enabled = prefs.getBoolean(PREF_WATERMARK_ENABLED, true),
            showTimestamp = prefs.getBoolean(PREF_SHOW_TIMESTAMP, true),
            showGps = prefs.getBoolean(PREF_SHOW_GPS, true),
            showSpeed = prefs.getBoolean(PREF_SHOW_SPEED, true),
            showCameraName = prefs.getBoolean(PREF_SHOW_CAMERA_NAME, true),
            customText = prefs.getString(PREF_CUSTOM_TEXT, "") ?: "",
            position = WatermarkPosition.valueOf(
                prefs.getString(PREF_POSITION, WatermarkPosition.TOP_LEFT.name)
                    ?: WatermarkPosition.TOP_LEFT.name
            ),
            textSize = prefs.getFloat(PREF_TEXT_SIZE, 14f)
        )
    }

    /**
     * 保存配置到SharedPreferences
     */
    fun saveConfig(config: WatermarkConfig) {
        prefs.edit().apply {
            putBoolean(PREF_WATERMARK_ENABLED, config.enabled)
            putBoolean(PREF_SHOW_TIMESTAMP, config.showTimestamp)
            putBoolean(PREF_SHOW_GPS, config.showGps)
            putBoolean(PREF_SHOW_SPEED, config.showSpeed)
            putBoolean(PREF_SHOW_CAMERA_NAME, config.showCameraName)
            putString(PREF_CUSTOM_TEXT, config.customText)
            putString(PREF_POSITION, config.position.name)
            putFloat(PREF_TEXT_SIZE, config.textSize)
            apply()
        }
        _watermarkConfig.value = config
    }

    /**
     * 更新配置
     */
    fun updateConfig(config: WatermarkConfig) {
        _watermarkConfig.value = config
        saveConfig(config)
    }

    /**
     * 更新指定摄像头的水印数据
     */
    fun updateWatermarkData(
        cameraPosition: CameraPosition,
        gpsData: GpsData? = null,
        customText: String = ""
    ) {
        val currentData = _watermarkData.value.toMutableMap()
        currentData[cameraPosition] = WatermarkData(
            timestamp = System.currentTimeMillis(),
            cameraPosition = cameraPosition,
            gpsData = gpsData,
            customText = customText
        )
        _watermarkData.value = currentData
    }

    /**
     * 批量更新所有摄像头的GPS数据
     */
    fun updateGpsDataForAll(gpsData: GpsData?) {
        val currentData = _watermarkData.value.toMutableMap()
        CameraPosition.values().forEach { position ->
            val existing = currentData[position]
            currentData[position] = WatermarkData(
                timestamp = System.currentTimeMillis(),
                cameraPosition = position,
                gpsData = gpsData,
                customText = existing?.customText ?: ""
            )
        }
        _watermarkData.value = currentData
    }

    /**
     * 获取指定摄像头的水印数据
     */
    fun getWatermarkData(cameraPosition: CameraPosition): WatermarkData {
        return _watermarkData.value[cameraPosition] ?: WatermarkData(
            timestamp = System.currentTimeMillis(),
            cameraPosition = cameraPosition
        )
    }

    /**
     * 获取指定摄像头的格式化水印文本
     */
    fun getFormattedText(cameraPosition: CameraPosition): List<String> {
        val data = getWatermarkData(cameraPosition)
        return data.formatText(_watermarkConfig.value)
    }

    /**
     * 启用/禁用水印
     */
    fun setWatermarkEnabled(enabled: Boolean) {
        val newConfig = _watermarkConfig.value.copy(enabled = enabled)
        updateConfig(newConfig)
    }

    /**
     * 设置自定义文本
     */
    fun setCustomText(text: String) {
        val newConfig = _watermarkConfig.value.copy(customText = text)
        updateConfig(newConfig)
    }

    /**
     * 设置水印位置
     */
    fun setPosition(position: WatermarkPosition) {
        val newConfig = _watermarkConfig.value.copy(position = position)
        updateConfig(newConfig)
    }

    /**
     * 获取当前配置
     */
    fun getCurrentConfig(): WatermarkConfig = _watermarkConfig.value
}
