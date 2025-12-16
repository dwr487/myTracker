package com.dashcam.multicam.manager

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.dashcam.multicam.model.CameraConfig
import com.dashcam.multicam.model.CameraPosition

/**
 * 多摄像头管理器
 * 负责检测和配置多个摄像头
 */
class MultiCameraManager(private val context: Context) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val availableCameras = mutableListOf<CameraConfig>()

    companion object {
        private const val TAG = "MultiCameraManager"
    }

    /**
     * 初始化并检测可用摄像头
     */
    fun initialize(): List<CameraConfig> {
        availableCameras.clear()

        try {
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "检测到 ${cameraIds.size} 个摄像头")

            // 为每个摄像头分配位置
            cameraIds.forEachIndexed { index, cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                val position = when {
                    facing == CameraCharacteristics.LENS_FACING_FRONT && index == 0 -> CameraPosition.FRONT
                    facing == CameraCharacteristics.LENS_FACING_BACK && index == 1 -> CameraPosition.REAR
                    index == 2 -> CameraPosition.LEFT
                    index == 3 -> CameraPosition.RIGHT
                    else -> CameraPosition.FRONT // 默认
                }

                val config = CameraConfig(
                    cameraId = cameraId,
                    position = position
                )

                availableCameras.add(config)
                Log.d(TAG, "摄像头 $cameraId 配置为 ${position.displayName}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "初始化摄像头失败", e)
        }

        return availableCameras
    }

    /**
     * 获取可用摄像头列表
     */
    fun getAvailableCameras(): List<CameraConfig> = availableCameras

    /**
     * 根据位置获取摄像头配置
     */
    fun getCameraByPosition(position: CameraPosition): CameraConfig? {
        return availableCameras.firstOrNull { it.position == position }
    }

    /**
     * 检查设备是否支持多摄像头
     */
    fun supportsMultiCamera(): Boolean {
        return try {
            cameraManager.cameraIdList.size >= 2
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取摄像头支持的分辨率列表
     */
    fun getSupportedResolutions(cameraId: String): List<android.util.Size> {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val configs = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            configs?.getOutputSizes(android.media.MediaRecorder::class.java)?.toList()
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "获取分辨率失败: $cameraId", e)
            emptyList()
        }
    }
}
