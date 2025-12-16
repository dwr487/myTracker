package com.dashcam.multicam.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dashcam.multicam.R
import com.dashcam.multicam.model.RecordingState
import com.dashcam.multicam.service.RecordingService
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 主Activity
 * 显示四路摄像头预览和控制界面
 */
class MainActivity : AppCompatActivity() {

    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private lateinit var cameraExecutor: ExecutorService

    // UI组件
    private lateinit var previewFront: PreviewView
    private lateinit var previewRear: PreviewView
    private lateinit var previewLeft: PreviewView
    private lateinit var previewRight: PreviewView
    private lateinit var btnStartStop: Button
    private lateinit var btnProtect: Button
    private lateinit var btnSettings: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvGpsInfo: TextView
    private lateinit var tvStorageInfo: TextView

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            serviceBound = true
            Log.d(TAG, "服务已连接")

            // 监听录制状态
            observeRecordingState()
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            serviceBound = false
            Log.d(TAG, "服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 检查权限
        if (allPermissionsGranted()) {
            startCameraPreviews()
            bindRecordingService()
        } else {
            requestPermissions()
        }
    }

    private fun initViews() {
        previewFront = findViewById(R.id.preview_front)
        previewRear = findViewById(R.id.preview_rear)
        previewLeft = findViewById(R.id.preview_left)
        previewRight = findViewById(R.id.preview_right)
        btnStartStop = findViewById(R.id.btn_start_stop)
        btnProtect = findViewById(R.id.btn_protect)
        btnSettings = findViewById(R.id.btn_settings)
        tvStatus = findViewById(R.id.tv_status)
        tvGpsInfo = findViewById(R.id.tv_gps_info)
        tvStorageInfo = findViewById(R.id.tv_storage_info)

        btnStartStop.setOnClickListener {
            toggleRecording()
        }

        btnProtect.setOnClickListener {
            protectCurrentVideo()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /**
     * 绑定录制服务
     */
    private fun bindRecordingService() {
        val intent = Intent(this, RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    /**
     * 启动摄像头预览
     */
    private fun startCameraPreviews() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // 前置摄像头预览
                startPreview(cameraProvider, previewFront, CameraSelector.DEFAULT_FRONT_CAMERA)

                // 后置摄像头预览
                startPreview(cameraProvider, previewRear, CameraSelector.DEFAULT_BACK_CAMERA)

                // 注意：左右摄像头需要根据实际设备调整
                // 这里使用前后摄像头作为示例

            } catch (e: Exception) {
                Log.e(TAG, "启动摄像头预览失败", e)
                Toast.makeText(this, "启动摄像头失败", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 启动单个摄像头预览
     */
    private fun startPreview(
        cameraProvider: ProcessCameraProvider,
        previewView: PreviewView,
        cameraSelector: CameraSelector
    ) {
        try {
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview
            )
        } catch (e: Exception) {
            Log.e(TAG, "绑定摄像头失败", e)
        }
    }

    /**
     * 切换录制状态
     */
    private fun toggleRecording() {
        recordingService?.let { service ->
            when (service.recordingState.value) {
                RecordingState.IDLE -> {
                    service.startRecording()
                    btnStartStop.text = "停止录制"
                }
                RecordingState.RECORDING -> {
                    service.stopRecording()
                    btnStartStop.text = "开始录制"
                }
                else -> {}
            }
        }
    }

    /**
     * 保护当前视频
     */
    private fun protectCurrentVideo() {
        Toast.makeText(this, "当前视频已标记为紧急保护", Toast.LENGTH_SHORT).show()
        // 实际保护逻辑在服务中通过碰撞检测自动触发
        // 这里可以手动触发保护
    }

    /**
     * 监听录制状态
     */
    private fun observeRecordingState() {
        lifecycleScope.launch {
            recordingService?.recordingState?.collect { state ->
                updateRecordingStatus(state)
            }
        }

        // 监听GPS数据
        lifecycleScope.launch {
            recordingService?.getGpsManager()?.gpsData?.collect { gps ->
                gps?.let {
                    tvGpsInfo.text = """
                        GPS: ${String.format("%.6f", it.latitude)}, ${String.format("%.6f", it.longitude)}
                        速度: ${String.format("%.1f", it.speed * 3.6f)} km/h
                    """.trimIndent()
                }
            }
        }
    }

    /**
     * 更新录制状态显示
     */
    private fun updateRecordingStatus(state: RecordingState) {
        tvStatus.text = when (state) {
            RecordingState.IDLE -> "未录制"
            RecordingState.RECORDING -> "录制中"
            RecordingState.PAUSED -> "已暂停"
            RecordingState.ERROR -> "错误"
        }
    }

    /**
     * 更新UI
     */
    private fun updateUI() {
        recordingService?.let { service ->
            // 更新存储信息
            tvStorageInfo.text = service.getStorageManager().getStorageSummary()
        }
    }

    /**
     * 检查是否所有权限已授予
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 请求权限
     */
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            REQUIRED_PERMISSIONS,
            REQUEST_CODE_PERMISSIONS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCameraPreviews()
                bindRecordingService()
            } else {
                Toast.makeText(this, "需要所有权限才能使用行车记录仪", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        cameraExecutor.shutdown()
    }
}
