# 水印烧录到视频流集成指南

## 法规要求说明

根据法规要求，水印信息必须直接烧录到视频流中，而不能使用外部字幕文件。本文档提供两种集成方案。

## 方案一：FFmpeg集成（推荐）

### 1. 添加FFmpeg依赖

在 `app/build.gradle` 中添加：

```gradle
dependencies {
    // FFmpeg for Android
    implementation 'com.arthenica:mobile-ffmpeg-full:4.4.LTS'
    // 或使用更新的FFmpegKit
    implementation 'com.arthenica:ffmpeg-kit-full:5.1'
}
```

### 2. 创建FFmpeg水印烧录器

```kotlin
package com.dashcam.multicam.utils

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.dashcam.multicam.model.WatermarkConfig
import com.dashcam.multicam.model.WatermarkData
import java.io.File

class FFmpegWatermarkBurner(
    private val watermarkConfig: WatermarkConfig
) {

    companion object {
        private const val TAG = "FFmpegWatermarkBurner"
    }

    /**
     * 将水印烧录到视频中
     */
    fun burnWatermark(
        inputFile: File,
        outputFile: File,
        watermarkData: WatermarkData
    ): Boolean {
        if (!watermarkConfig.enabled) {
            return false
        }

        // 构建水印文本
        val watermarkText = buildWatermarkText(watermarkData)

        // 确定水印位置
        val position = when (watermarkConfig.position) {
            WatermarkPosition.TOP_LEFT -> "x=10:y=10"
            WatermarkPosition.TOP_RIGHT -> "x=w-tw-10:y=10"
            WatermarkPosition.BOTTOM_LEFT -> "x=10:y=h-th-10"
            WatermarkPosition.BOTTOM_RIGHT -> "x=w-tw-10:y=h-th-10"
        }

        // 构建FFmpeg命令
        val command = buildString {
            append("-i ${inputFile.absolutePath} ")
            append("-vf \"")
            append("drawtext=")
            append("text='$watermarkText':")
            append("fontsize=${watermarkConfig.textSize * 2}:")
            append("fontcolor=white:")
            append("box=1:")
            append("boxcolor=black@0.5:")
            append("boxborderw=5:")
            append("$position")
            append("\" ")
            append("-c:a copy ")  // 复制音频流
            append("-y ")  // 覆盖输出文件
            append(outputFile.absolutePath)
        }

        Log.d(TAG, "执行FFmpeg命令: $command")

        // 执行FFmpeg命令
        val session = FFmpegKit.execute(command)

        return if (ReturnCode.isSuccess(session.returnCode)) {
            Log.d(TAG, "水印烧录成功")
            true
        } else {
            Log.e(TAG, "水印烧录失败: ${session.failStackTrace}")
            false
        }
    }

    /**
     * 构建水印文本
     */
    private fun buildWatermarkText(data: WatermarkData): String {
        val lines = data.formatText(watermarkConfig)
        return lines.joinToString("\\n")
            .replace("'", "\\'")  // 转义单引号
    }
}
```

### 3. 在RecordingService中集成

```kotlin
// 在RecordingService.kt中添加
private fun processVideoWithWatermark(videoFile: File, position: CameraPosition) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    val burnWatermark = prefs.getBoolean("watermark_burn_to_video", true)

    if (!burnWatermark) return

    serviceScope.launch(Dispatchers.IO) {
        try {
            val tempFile = File(videoFile.parent, "${videoFile.nameWithoutExtension}_temp.mp4")
            val watermarkData = watermarkManager.getWatermarkData(position)
            val burner = FFmpegWatermarkBurner(watermarkManager.getCurrentConfig())

            if (burner.burnWatermark(videoFile, tempFile, watermarkData)) {
                // 替换原文件
                videoFile.delete()
                tempFile.renameTo(videoFile)
                Log.d(TAG, "视频水印烧录完成: ${videoFile.name}")
            } else {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "烧录水印失败", e)
        }
    }
}
```

### 4. 更新设置界面

在 `preferences.xml` 中添加：

```xml
<SwitchPreference
    android:key="watermark_burn_to_video"
    android:title="烧录水印到视频"
    android:summary="将水印永久写入视频文件（需要额外处理时间）"
    android:defaultValue="true"
    android:dependency="watermark_enabled" />
```

## 方案二：MediaCodec实时烧录（高级）

### 优势
- 实时处理，无需后期处理
- 不需要额外存储空间

### 劣势
- 实现复杂
- 性能开销大
- 可能影响录制稳定性

### 实现步骤

1. **使用Camera2 API + MediaCodec**
   - 创建自定义Surface用于绘制
   - 使用OpenGL ES进行帧处理
   - 在每一帧上绘制水印

2. **核心代码框架**

```kotlin
// 创建OpenGL渲染器
class WatermarkRenderer(
    private val watermarkConfig: WatermarkConfig
) : GLSurfaceView.Renderer {

    override fun onDrawFrame(gl: GL10?) {
        // 1. 绘制摄像头帧
        // 2. 叠加水印纹理
        // 3. 输出到编码器
    }
}

// 使用MediaCodec录制
class RealtimeWatermarkRecorder {
    private fun setupEncoder() {
        // 配置MediaCodec
        // 创建InputSurface
        // 将Surface传给OpenGL渲染器
    }
}
```

这种方案需要深入的OpenGL ES和MediaCodec知识，建议使用方案一。

## 方案对比

| 特性 | FFmpeg方案 | MediaCodec实时 |
|------|-----------|----------------|
| 实现难度 | 简单 | 复杂 |
| 性能影响 | 后处理 | 实时影响 |
| 稳定性 | 高 | 中 |
| 额外存储 | 需要临时空间 | 不需要 |
| 推荐度 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |

## 推荐配置

### 分段处理策略
1. 录制视频时先不添加水印
2. 录制完每个分段后，立即在后台用FFmpeg处理
3. 处理完成后替换原文件
4. 使用队列管理，避免多个任务并发

### 性能优化
1. 使用低优先级后台线程
2. 仅在存储空间充足时处理
3. 可选择在充电时批量处理
4. 保留原始视频作为备份（可选）

## 法规符合性

使用FFmpeg方案烧录水印后：
- ✅ 水印永久嵌入视频流
- ✅ 无法移除或禁用
- ✅ 在任何播放器中都可见
- ✅ 符合法规要求

## 集成检查清单

- [ ] 添加FFmpegKit依赖
- [ ] 实现FFmpegWatermarkBurner类
- [ ] 集成到RecordingService
- [ ] 添加设置选项
- [ ] 测试烧录功能
- [ ] 测试性能影响
- [ ] 验证法规符合性
