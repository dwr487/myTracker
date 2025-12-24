# 动态水印烧录到视频流集成指南

## 法规要求说明

根据法规要求，行车记录仪的水印信息必须：
1. **直接烧录到视频流中**，不能使用外部字幕文件
2. **动态显示实时信息**，包括每秒变化的时间戳、GPS坐标、速度等
3. **无法移除或篡改**，确保视频证据的完整性

本项目实现了完整的动态水印烧录方案，满足所有法规要求。

## 技术方案：ASS字幕 + FFmpeg烧录

### 核心思路

1. **实时数据采集**：录制过程中每秒记录当前的GPS和时间数据
2. **ASS字幕生成**：将每秒的数据保存为ASS格式字幕文件
3. **FFmpeg烧录**：使用FFmpeg的`ass`滤镜将字幕永久烧录到视频中
4. **动态效果**：视频播放时，水印内容每秒自动更新

### 为什么选择这个方案？

- ✅ **真正的动态水印**：每秒都可以显示不同的GPS和时间信息
- ✅ **性能优越**：后处理方式不影响录制稳定性
- ✅ **高质量输出**：FFmpeg提供专业级视频处理能力
- ✅ **灵活配置**：支持自定义水印样式、位置、颜色
- ✅ **法规合规**：水印永久嵌入，无法移除

## 实现详解

### 1. 添加FFmpegKit依赖

在 `app/build.gradle` 中添加：

```gradle
dependencies {
    // FFmpegKit for video watermarking
    implementation 'com.arthenica:ffmpeg-kit-min:6.0-2'
}
```

### 2. 动态水印数据生成器

创建 `DynamicWatermarkGenerator.kt`，负责收集和生成ASS字幕文件：

```kotlin
class DynamicWatermarkGenerator(
    private val watermarkConfig: WatermarkConfig
) {
    private val watermarkDataPoints = mutableListOf<TimedWatermarkData>()

    data class TimedWatermarkData(
        val timestampSeconds: Int,  // 视频中的秒数
        val watermarkData: WatermarkData
    )

    /**
     * 添加一个时间点的水印数据
     */
    fun addDataPoint(videoTimestampMs: Long, watermarkData: WatermarkData) {
        val seconds = (videoTimestampMs / 1000).toInt()
        if (watermarkDataPoints.none { it.timestampSeconds == seconds }) {
            watermarkDataPoints.add(
                TimedWatermarkData(seconds, watermarkData.copy())
            )
        }
    }

    /**
     * 生成ASS字幕文件
     */
    fun generateASSFile(outputFile: File, videoDurationMs: Long): Boolean {
        // 生成ASS文件头
        // 写入每秒的水印事件
        // 详细实现见项目源码
    }
}
```

**关键点**：
- 每秒记录一次数据点，包含时间戳和GPS信息
- ASS格式支持丰富的样式（颜色、位置、字体等）
- 每个数据点对应视频中的一秒

### 3. 动态水印烧录器

创建 `DynamicWatermarkBurner.kt`，使用FFmpeg烧录ASS字幕：

```kotlin
class DynamicWatermarkBurner {
    /**
     * 将ASS字幕烧录到视频中
     */
    fun burnWatermark(
        inputVideo: File,
        inputASS: File,
        outputVideo: File
    ): Boolean {
        val command = buildFFmpegCommand(inputVideo, inputASS, outputVideo)
        val session = FFmpegKit.execute(command)
        return ReturnCode.isSuccess(session.returnCode)
    }

    private fun buildFFmpegCommand(
        inputVideo: File,
        inputASS: File,
        outputVideo: File
    ): String {
        val assPath = inputASS.absolutePath
            .replace("\\", "\\\\")
            .replace(":", "\\:")

        return "-i \"${inputVideo.absolutePath}\" " +
                "-vf \"ass='$assPath'\" " +
                "-c:v libx264 " +
                "-preset medium " +
                "-crf 23 " +
                "-c:a copy " +
                "-y " +
                "\"${outputVideo.absolutePath}\""
    }
}
```

**FFmpeg命令说明**：
- `-i`: 输入视频文件
- `-vf "ass='path'"`: 使用ass滤镜烧录字幕
- `-c:v libx264`: 使用H.264编码
- `-preset medium`: 编码速度和质量的平衡
- `-crf 23`: 质量参数（18-28，越小质量越好）
- `-c:a copy`: 音频直接复制，不重新编码

### 4. 在RecordingService中集成

修改 `RecordingService.kt`：

```kotlin
class RecordingService : Service() {
    // 为每个摄像头创建动态水印生成器
    private val watermarkGenerators = mutableMapOf<CameraPosition, DynamicWatermarkGenerator>()
    private var watermarkCollectionJob: Job? = null
    private var segmentStartTimeMs = 0L

    /**
     * 启动水印数据收集（每秒收集一次）
     */
    private fun startWatermarkDataCollection() {
        watermarkCollectionJob = serviceScope.launch {
            while (isActive) {
                val currentTimeMs = System.currentTimeMillis() - segmentStartTimeMs

                watermarkGenerators.forEach { (position, generator) ->
                    val watermarkData = watermarkManager.getWatermarkData(position)
                    generator.addDataPoint(currentTimeMs, watermarkData)
                }

                delay(1000)  // 每秒收集一次
            }
        }
    }

    /**
     * 烧录动态水印到视频
     */
    private fun burnDynamicWatermarkToVideo(videoFile: File, position: CameraPosition) {
        val generator = watermarkGenerators[position] ?: return

        // 生成ASS字幕文件
        val assFile = File(videoFile.parent, "${videoFile.nameWithoutExtension}.ass")
        val burner = DynamicWatermarkBurner()

        val videoDuration = burner.getVideoDuration(videoFile)
        generator.generateASSFile(assFile, videoDuration)

        // 烧录水印
        val tempFile = File(videoFile.parent, "${videoFile.nameWithoutExtension}_temp.mp4")
        if (burner.burnWatermark(videoFile, assFile, tempFile)) {
            videoFile.delete()
            tempFile.renameTo(videoFile)
            assFile.delete()  // 清理临时ASS文件
        }
    }
}
```

## 工作流程

### 录制阶段

1. 开始录制时，为每个摄像头创建 `DynamicWatermarkGenerator`
2. 启动数据收集协程，每秒执行一次：
   - 获取当前GPS数据（坐标、速度、方向）
   - 获取当前时间戳
   - 计算视频时间偏移量
   - 添加数据点到生成器
3. 录制正常进行，不受水印收集影响

### 段落结束阶段

1. 停止当前段的录制
2. 为每个摄像头的视频：
   - 调用 `generateASSFile()` 生成ASS字幕文件
   - 调用 `burnWatermark()` 使用FFmpeg烧录水印
   - 替换原视频文件
   - 清理临时文件
3. 清空水印数据，准备下一段
4. 开始录制新段

## ASS字幕文件示例

```ass
[Script Info]
Title: Dashcam Watermark
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, ...
Style: WatermarkStyle,Arial,28,&H00FFFFFF,...

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:01.00,WatermarkStyle,,0,0,0,,2024-12-24 10:30:00\NGPS: 39.904202, 116.407394\N速度: 45.2 km/h\NFRONT
Dialogue: 0,0:00:01.00,0:00:02.00,WatermarkStyle,,0,0,0,,2024-12-24 10:30:01\NGPS: 39.904215, 116.407402\N速度: 46.1 km/h\NFRONT
Dialogue: 0,0:00:02.00,0:00:03.00,WatermarkStyle,,0,0,0,,2024-12-24 10:30:02\NGPS: 39.904228, 116.407410\N速度: 47.3 km/h\NFRONT
...
```

**说明**：
- 每秒一个Dialogue事件
- 时间戳、GPS、速度每秒都在变化
- 使用`\N`换行符分隔多行文本
- 样式统一定义在Style部分

## 性能优化

### 1. 数据采集优化
- 采用异步协程，不阻塞录制线程
- 每秒采集1次，平衡精度和性能
- 自动去重，避免重复数据

### 2. FFmpeg处理优化
```kotlin
// 使用medium预设，平衡速度和质量
"-preset medium"

// 音频直接复制，不重新编码
"-c:a copy"

// 合理的CRF值，保证质量
"-crf 23"
```

### 3. 资源管理
- 及时清理临时ASS文件
- 使用临时文件避免破坏原视频
- 分段处理，避免内存占用过大

## 测试验证

### 1. 功能测试
```kotlin
// 验证数据采集
assertEquals(60, generator.getDataPointCount())  // 60秒的视频

// 验证ASS文件生成
assertTrue(assFile.exists())
assertTrue(assFile.length() > 0)

// 验证FFmpeg烧录
assertTrue(burner.burnWatermark(input, ass, output))
assertTrue(output.exists())
```

### 2. 播放验证
1. 使用VLC Player打开生成的视频
2. 确认水印显示在正确位置
3. 快进播放，验证时间戳每秒变化
4. 验证GPS坐标随车辆移动而变化
5. 确认水印无法通过播放器禁用

### 3. 质量检查
```bash
# 检查视频编码信息
ffprobe output.mp4

# 验证水印是否烧录（不应有字幕流）
ffprobe -show_streams output.mp4 | grep codec_type
```

## 常见问题

### Q1: 水印延迟或不同步？
A: 检查 `segmentStartTimeMs` 的初始化时机，确保在开始录制时正确设置。

### Q2: ASS文件路径包含特殊字符？
A: 使用路径转义：
```kotlin
val assPath = inputASS.absolutePath
    .replace("\\", "\\\\")
    .replace(":", "\\:")
```

### Q3: FFmpeg处理速度太慢？
A: 调整preset：
- `ultrafast`: 最快，质量较低
- `medium`: 平衡（推荐）
- `slow`: 质量最好，速度慢

### Q4: 内存占用过高？
A: 确保及时清理：
```kotlin
watermarkGenerators.values.forEach { it.clear() }
```

## 合规性说明

本实现满足以下法规要求：

✅ **水印嵌入视频流**：使用FFmpeg的ass滤镜将字幕永久烧录到视频中

✅ **动态实时信息**：每秒记录和显示当前的GPS、时间、速度等信息

✅ **无法移除**：水印已成为视频像素的一部分，无法分离

✅ **准确性**：GPS和时间戳直接来自系统传感器，精确到秒

✅ **完整性**：每段视频都包含完整的水印数据

## 总结

本方案通过以下技术实现了法规要求的动态水印：

1. **实时数据采集**：每秒记录GPS和时间
2. **ASS字幕格式**：灵活且功能强大
3. **FFmpeg烧录**：专业且高效
4. **自动化处理**：无需人工干预

相比实时编码方案，本方案具有更好的稳定性和可维护性，同时完全满足法规对动态水印的要求。
