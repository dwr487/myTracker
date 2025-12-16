package com.dashcam.multicam.manager

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager as AndroidSensorManager
import android.util.Log
import com.dashcam.multicam.model.SensorData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.sqrt

/**
 * 传感器管理器
 * 负责加速度计数据采集和碰撞检测
 */
class SensorManager(private val context: Context) : SensorEventListener {

    private val sensorManager: AndroidSensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as AndroidSensorManager

    private var accelerometer: Sensor? = null

    private val _sensorData = MutableSharedFlow<SensorData>(replay = 1)
    val sensorData: SharedFlow<SensorData> = _sensorData.asSharedFlow()

    private val _collisionDetected = MutableSharedFlow<SensorData>(replay = 0)
    val collisionDetected: SharedFlow<SensorData> = _collisionDetected.asSharedFlow()

    companion object {
        private const val TAG = "SensorManager"
        private const val COLLISION_THRESHOLD = 20f // G值阈值（约2G）
        private const val HARSH_BRAKE_THRESHOLD = 15f // 急刹车阈值
        private const val HARSH_TURN_THRESHOLD = 15f // 急转弯阈值
    }

    /**
     * 初始化传感器
     */
    fun initialize() {
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Log.w(TAG, "设备没有加速度计")
        } else {
            Log.d(TAG, "加速度计已初始化")
        }
    }

    /**
     * 开始监听传感器
     */
    fun startListening() {
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                AndroidSensorManager.SENSOR_DELAY_NORMAL
            )
            Log.d(TAG, "开始监听加速度计")
        }
    }

    /**
     * 停止监听传感器
     */
    fun stopListening() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "停止监听加速度计")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // 计算加速度的大小
            val magnitude = sqrt(x * x + y * y + z * z)

            val data = SensorData(
                x = x,
                y = y,
                z = z,
                magnitude = magnitude,
                timestamp = System.currentTimeMillis()
            )

            // 更新传感器数据
            _sensorData.tryEmit(data)

            // 检测碰撞
            if (magnitude > COLLISION_THRESHOLD) {
                Log.w(TAG, "检测到碰撞! 加速度: $magnitude m/s²")
                _collisionDetected.tryEmit(data)
            } else if (magnitude > HARSH_BRAKE_THRESHOLD) {
                Log.i(TAG, "检测到急刹车/急加速: $magnitude m/s²")
            } else if (x > HARSH_TURN_THRESHOLD || y > HARSH_TURN_THRESHOLD) {
                Log.i(TAG, "检测到急转弯: X=$x, Y=$y m/s²")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "传感器精度改变: ${sensor?.name}, 精度: $accuracy")
    }

    /**
     * 判断是否是碰撞事件
     */
    fun isCollision(data: SensorData): Boolean {
        return data.magnitude > COLLISION_THRESHOLD
    }

    /**
     * 判断是否是急刹车
     */
    fun isHarshBrake(data: SensorData): Boolean {
        return data.z > HARSH_BRAKE_THRESHOLD
    }

    /**
     * 判断是否是急转弯
     */
    fun isHarshTurn(data: SensorData): Boolean {
        return data.x > HARSH_TURN_THRESHOLD || data.y > HARSH_TURN_THRESHOLD
    }

    /**
     * 格式化传感器数据为字符串
     */
    fun formatSensorData(data: SensorData): String {
        return """
            X: ${"%.2f".format(data.x)} m/s²
            Y: ${"%.2f".format(data.y)} m/s²
            Z: ${"%.2f".format(data.z)} m/s²
            总加速度: ${"%.2f".format(data.magnitude)} m/s²
        """.trimIndent()
    }
}
