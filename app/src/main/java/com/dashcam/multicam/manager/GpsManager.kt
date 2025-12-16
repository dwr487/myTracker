package com.dashcam.multicam.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import com.dashcam.multicam.model.GpsData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GPS管理器
 * 负责GPS定位和数据收集
 */
class GpsManager(private val context: Context) : LocationListener {

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gpsData = MutableStateFlow<GpsData?>(null)
    val gpsData: StateFlow<GpsData?> = _gpsData.asStateFlow()

    private val _isGpsEnabled = MutableStateFlow(false)
    val isGpsEnabled: StateFlow<Boolean> = _isGpsEnabled.asStateFlow()

    companion object {
        private const val TAG = "GpsManager"
        private const val MIN_TIME_MS = 1000L // 1秒更新一次
        private const val MIN_DISTANCE_M = 5f // 移动5米更新一次
    }

    /**
     * 开始GPS定位
     */
    fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "没有定位权限")
            return
        }

        try {
            // 检查GPS是否可用
            val isGpsProviderEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkProviderEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            _isGpsEnabled.value = isGpsProviderEnabled || isNetworkProviderEnabled

            // 优先使用GPS，其次使用网络定位
            if (isGpsProviderEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    this
                )
                Log.d(TAG, "GPS定位已启动")
            }

            if (isNetworkProviderEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    this
                )
                Log.d(TAG, "网络定位已启动")
            }

            // 获取最后已知位置
            val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            lastKnownLocation?.let {
                updateGpsData(it)
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "启动定位失败: 权限不足", e)
        } catch (e: Exception) {
            Log.e(TAG, "启动定位失败", e)
        }
    }

    /**
     * 停止GPS定位
     */
    fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(this)
            Log.d(TAG, "GPS定位已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止定位失败", e)
        }
    }

    /**
     * 检查是否有定位权限
     */
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 更新GPS数据
     */
    private fun updateGpsData(location: Location) {
        val gps = GpsData(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            speed = location.speed,
            bearing = location.bearing,
            timestamp = location.time
        )
        _gpsData.value = gps
        Log.d(TAG, "GPS更新: ${gps.latitude}, ${gps.longitude}, 速度: ${gps.speed}m/s")
    }

    // LocationListener 实现
    override fun onLocationChanged(location: Location) {
        updateGpsData(location)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        Log.d(TAG, "Provider状态改变: $provider, status: $status")
    }

    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "Provider已启用: $provider")
        _isGpsEnabled.value = true
    }

    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "Provider已禁用: $provider")
        val isAnyProviderEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        _isGpsEnabled.value = isAnyProviderEnabled
    }

    /**
     * 获取当前GPS数据
     */
    fun getCurrentGpsData(): GpsData? = _gpsData.value

    /**
     * 格式化GPS数据为字符串
     */
    fun formatGpsData(gps: GpsData): String {
        return """
            纬度: ${"%.6f".format(gps.latitude)}
            经度: ${"%.6f".format(gps.longitude)}
            高度: ${"%.1f".format(gps.altitude)}m
            速度: ${"%.1f".format(gps.speed * 3.6f)}km/h
            方向: ${"%.1f".format(gps.bearing)}°
        """.trimIndent()
    }
}
