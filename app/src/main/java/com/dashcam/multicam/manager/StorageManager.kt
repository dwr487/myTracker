package com.dashcam.multicam.manager

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.dashcam.multicam.model.CameraPosition
import com.dashcam.multicam.model.VideoFileInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 存储管理器
 * 负责视频文件的存储、循环录制、空间管理
 */
class StorageManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageManager"
        private const val VIDEO_DIR = "DashcamVideos"
        private const val NORMAL_DIR = "Normal"
        private const val PROTECTED_DIR = "Protected"
        private const val MIN_FREE_SPACE_MB = 500L // 最小保留空间 500MB
        private const val SEGMENT_DURATION_MS = 60_000L // 每段视频1分钟
    }

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * 获取视频存储根目录
     */
    fun getVideoStorageDir(): File {
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir

        val videoDir = File(externalDir, VIDEO_DIR)
        if (!videoDir.exists()) {
            videoDir.mkdirs()
        }
        return videoDir
    }

    /**
     * 获取普通视频目录
     */
    fun getNormalVideoDir(): File {
        val dir = File(getVideoStorageDir(), NORMAL_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 获取受保护视频目录
     */
    fun getProtectedVideoDir(): File {
        val dir = File(getVideoStorageDir(), PROTECTED_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 生成视频文件名
     */
    fun generateVideoFileName(position: CameraPosition, isProtected: Boolean = false): String {
        val timestamp = dateFormat.format(Date())
        val prefix = if (isProtected) "PROTECTED" else "NORMAL"
        return "${prefix}_${position.name}_$timestamp.mp4"
    }

    /**
     * 获取视频文件完整路径
     */
    fun getVideoFilePath(
        position: CameraPosition,
        isProtected: Boolean = false
    ): File {
        val dir = if (isProtected) getProtectedVideoDir() else getNormalVideoDir()
        val cameraDir = File(dir, position.name)
        if (!cameraDir.exists()) {
            cameraDir.mkdirs()
        }
        val fileName = generateVideoFileName(position, isProtected)
        return File(cameraDir, fileName)
    }

    /**
     * 检查可用存储空间（MB）
     */
    fun getAvailableSpaceMB(): Long {
        return try {
            val stat = StatFs(getVideoStorageDir().path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            bytesAvailable / (1024 * 1024)
        } catch (e: Exception) {
            Log.e(TAG, "获取可用空间失败", e)
            0L
        }
    }

    /**
     * 检查是否需要清理空间
     */
    fun needsCleanup(): Boolean {
        return getAvailableSpaceMB() < MIN_FREE_SPACE_MB
    }

    /**
     * 执行循环录制清理（删除最旧的普通视频）
     */
    fun performCleanup() {
        val normalDir = getNormalVideoDir()
        val allVideos = mutableListOf<File>()

        // 收集所有普通视频文件
        normalDir.listFiles()?.forEach { cameraDir ->
            if (cameraDir.isDirectory) {
                cameraDir.listFiles()?.forEach { videoFile ->
                    if (videoFile.isFile && videoFile.extension == "mp4") {
                        allVideos.add(videoFile)
                    }
                }
            }
        }

        // 按修改时间排序（最旧的在前）
        allVideos.sortBy { it.lastModified() }

        // 删除最旧的视频直到有足够空间
        var deletedCount = 0
        for (video in allVideos) {
            if (!needsCleanup()) break

            val deleted = video.delete()
            if (deleted) {
                deletedCount++
                Log.d(TAG, "删除旧视频: ${video.name}")
            }
        }

        Log.d(TAG, "清理完成，删除了 $deletedCount 个视频文件")
    }

    /**
     * 获取所有视频文件信息
     */
    fun getAllVideos(): List<VideoFileInfo> {
        val videos = mutableListOf<VideoFileInfo>()

        // 获取普通视频
        getNormalVideoDir().listFiles()?.forEach { cameraDir ->
            if (cameraDir.isDirectory) {
                val position = CameraPosition.valueOf(cameraDir.name)
                cameraDir.listFiles()?.forEach { videoFile ->
                    if (videoFile.isFile && videoFile.extension == "mp4") {
                        videos.add(
                            VideoFileInfo(
                                fileName = videoFile.name,
                                filePath = videoFile.absolutePath,
                                cameraPosition = position,
                                startTime = videoFile.lastModified(),
                                endTime = videoFile.lastModified() + SEGMENT_DURATION_MS,
                                fileSize = videoFile.length(),
                                isProtected = false
                            )
                        )
                    }
                }
            }
        }

        // 获取受保护视频
        getProtectedVideoDir().listFiles()?.forEach { cameraDir ->
            if (cameraDir.isDirectory) {
                val position = CameraPosition.valueOf(cameraDir.name)
                cameraDir.listFiles()?.forEach { videoFile ->
                    if (videoFile.isFile && videoFile.extension == "mp4") {
                        videos.add(
                            VideoFileInfo(
                                fileName = videoFile.name,
                                filePath = videoFile.absolutePath,
                                cameraPosition = position,
                                startTime = videoFile.lastModified(),
                                endTime = videoFile.lastModified() + SEGMENT_DURATION_MS,
                                fileSize = videoFile.length(),
                                isProtected = true
                            )
                        )
                    }
                }
            }
        }

        return videos.sortedByDescending { it.startTime }
    }

    /**
     * 保护当前视频（移动到受保护目录）
     */
    fun protectVideo(videoFile: File): Boolean {
        return try {
            val protectedDir = getProtectedVideoDir()
            val cameraName = videoFile.parentFile?.name ?: return false
            val targetDir = File(protectedDir, cameraName)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val targetFile = File(targetDir, videoFile.name.replace("NORMAL", "PROTECTED"))
            val success = videoFile.renameTo(targetFile)

            if (success) {
                Log.d(TAG, "视频已保护: ${targetFile.absolutePath}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "保护视频失败", e)
            false
        }
    }

    /**
     * 获取存储使用情况摘要
     */
    fun getStorageSummary(): String {
        val totalSpace = getAvailableSpaceMB()
        val normalVideos = getNormalVideoDir().walkTopDown()
            .filter { it.isFile && it.extension == "mp4" }
            .count()
        val protectedVideos = getProtectedVideoDir().walkTopDown()
            .filter { it.isFile && it.extension == "mp4" }
            .count()

        return """
            可用空间: ${totalSpace}MB
            普通视频: $normalVideos 个
            保护视频: $protectedVideos 个
        """.trimIndent()
    }
}
