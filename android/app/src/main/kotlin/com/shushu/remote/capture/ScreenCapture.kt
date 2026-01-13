package com.shushu.remote.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 屏幕采集器 - 支持 MJPEG 和 H264 双模式
 */
class ScreenCapture(
    private val context: Context,
    private val mediaProjection: MediaProjection?,
    private val originalWidth: Int,
    private val originalHeight: Int,
    private val density: Int
) {
    companion object {
        private const val TAG = "ScreenCapture"

        // 质量档位
        const val QUALITY_HIGH = 0    // 高质量：原始分辨率，80%质量，30fps
        const val QUALITY_MEDIUM = 1  // 中质量：0.75分辨率，60%质量，20fps
        const val QUALITY_LOW = 2     // 低质量：0.5分辨率，40%质量，10fps
        const val QUALITY_VERY_LOW = 3 // 极低质量：0.25分辨率，30%质量，5fps

        // 流模式
        const val MODE_MJPEG = "mjpeg"
        const val MODE_H264 = "h264"

        // 二进制消息类型
        const val FRAME_TYPE_MJPEG: Byte = 0x01
        const val FRAME_TYPE_H264: Byte = 0x02
        const val FRAME_TYPE_H264_CONFIG: Byte = 0x03
    }

    // 当前采集参数
    private var currentWidth = originalWidth
    private var currentHeight = originalHeight
    private var quality = 80
    private var maxFps = 30
    private var qualityLevel = QUALITY_HIGH

    // 当前模式
    private var currentMode = MODE_MJPEG

    // MJPEG 模式组件
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameCallback: ((ByteArray) -> Unit)? = null

    // H264 模式组件
    private var h264Encoder: H264Encoder? = null
    private var h264VirtualDisplay: VirtualDisplay? = null
    private var h264ConfigData: ByteArray? = null  // 缓存 SPS/PPS

    private val isCapturing = AtomicBoolean(false)
    private var lastFrameTime = 0L

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // 网络状态监控
    private val pendingFrames = AtomicInteger(0)  // 待发送帧数
    private val lastAckTime = AtomicLong(System.currentTimeMillis())
    private var lastBandwidthCheckTime = 0L
    private var framesSentSinceCheck = 0
    private var bytesSentSinceCheck = 0L

    // 自适应参数
    private var adaptiveEnabled = true
    private var consecutiveSlowFrames = 0
    private var consecutiveFastFrames = 0

    fun setFrameCallback(callback: (ByteArray) -> Unit) {
        frameCallback = callback
    }

    fun startCapture(quality: Int = 80, maxFps: Int = 30) {
        if (isCapturing.get()) {
            Log.d(TAG, "Already capturing")
            return
        }

        this.quality = quality
        this.maxFps = maxFps

        Log.d(TAG, "Starting capture: ${currentWidth}x${currentHeight}, quality=$quality, maxFps=$maxFps")

        // 创建 HandlerThread
        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        handler = Handler(handlerThread!!.looper)

        createImageReader()

        // 尝试使用系统权限模式创建 VirtualDisplay
        var success = tryCreateVirtualDisplayWithSystemPermission()

        // 如果系统权限模式失败，回退到 MediaProjection 模式
        if (!success && mediaProjection != null) {
            success = tryCreateVirtualDisplayWithMediaProjection()
        }

        if (success) {
            isCapturing.set(true)
            lastBandwidthCheckTime = System.currentTimeMillis()
            Log.d(TAG, "Screen capture started successfully")
        } else {
            Log.e(TAG, "Failed to start screen capture")
            cleanup()
        }
    }

    private fun createImageReader() {
        imageReader?.close()
        imageReader = ImageReader.newInstance(currentWidth, currentHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            processImage(reader)
        }, handler)
    }

    private fun tryCreateVirtualDisplayWithSystemPermission(): Boolean {
        return try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            virtualDisplay = displayManager.createVirtualDisplay(
                "ScreenCapture",
                currentWidth,
                currentHeight,
                density,
                imageReader?.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            )

            if (virtualDisplay != null) {
                Log.d(TAG, "VirtualDisplay created with system permission")
                true
            } else {
                Log.w(TAG, "Failed to create VirtualDisplay with system permission")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "System permission mode not available: ${e.message}")
            false
        }
    }

    private fun tryCreateVirtualDisplayWithMediaProjection(): Boolean {
        return try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                currentWidth,
                currentHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

            if (virtualDisplay != null) {
                Log.d(TAG, "VirtualDisplay created with MediaProjection")
                true
            } else {
                Log.w(TAG, "Failed to create VirtualDisplay with MediaProjection")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection mode failed: ${e.message}")
            false
        }
    }

    fun stopCapture() {
        Log.d(TAG, "Stopping capture, mode=$currentMode")
        isCapturing.set(false)
        cleanup()
    }

    private fun cleanup() {
        // 清理 MJPEG 组件
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        // 清理 H264 组件
        h264Encoder?.release()
        h264Encoder = null
        h264VirtualDisplay?.release()
        h264VirtualDisplay = null

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    /**
     * 启动 H264 模式采集
     */
    fun startH264Capture(bitrate: Int = 2_000_000, fps: Int = 30) {
        if (isCapturing.get()) {
            Log.d(TAG, "Already capturing, stopping first")
            stopCapture()
        }

        currentMode = MODE_H264
        this.maxFps = fps

        // 计算采集分辨率（保持宽高比，限制最大分辨率）
        val scale = when {
            originalWidth > 1920 -> 0.5f
            originalWidth > 1080 -> 0.75f
            else -> 1.0f
        }
        currentWidth = (originalWidth * scale).toInt()
        currentHeight = (originalHeight * scale).toInt()

        // 确保宽高是 16 的倍数（H264 编码要求）
        currentWidth = (currentWidth / 16) * 16
        currentHeight = (currentHeight / 16) * 16

        Log.d(TAG, "Starting H264 capture: ${currentWidth}x${currentHeight}, bitrate=$bitrate, fps=$fps")

        // 创建 HandlerThread
        handlerThread = HandlerThread("H264CaptureThread").apply { start() }
        handler = Handler(handlerThread!!.looper)

        // 创建 H264 编码器
        h264Encoder = H264Encoder(currentWidth, currentHeight, bitrate, fps) { data, isConfig, isKeyFrame ->
            // 打包并发送
            val packet = packH264Frame(data, isConfig, isKeyFrame)
            pendingFrames.incrementAndGet()
            frameCallback?.invoke(packet)
        }

        // 启动编码器获取 Surface
        val encoderSurface = h264Encoder?.start()
        if (encoderSurface == null) {
            Log.e(TAG, "Failed to start H264 encoder")
            cleanup()
            return
        }

        // 创建 VirtualDisplay 输出到编码器 Surface
        val success = createH264VirtualDisplay(encoderSurface)
        if (success) {
            isCapturing.set(true)
            lastBandwidthCheckTime = System.currentTimeMillis()
            Log.d(TAG, "H264 capture started successfully")
        } else {
            Log.e(TAG, "Failed to create VirtualDisplay for H264")
            cleanup()
        }
    }

    /**
     * 创建 H264 模式的 VirtualDisplay
     */
    private fun createH264VirtualDisplay(surface: Surface): Boolean {
        return try {
            // 优先使用 MediaProjection
            if (mediaProjection != null) {
                h264VirtualDisplay = mediaProjection.createVirtualDisplay(
                    "H264Capture",
                    currentWidth,
                    currentHeight,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    handler
                )
            } else {
                // 尝试系统权限模式
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                h264VirtualDisplay = displayManager.createVirtualDisplay(
                    "H264Capture",
                    currentWidth,
                    currentHeight,
                    density,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                )
            }

            h264VirtualDisplay != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create H264 VirtualDisplay", e)
            false
        }
    }

    /**
     * 打包 H264 帧为二进制协议格式
     * 格式: [Type(1B)][Flags(1B)][Payload]
     */
    private fun packH264Frame(data: ByteArray, isConfig: Boolean, isKeyFrame: Boolean): ByteArray {
        val type: Byte = if (isConfig) FRAME_TYPE_H264_CONFIG else FRAME_TYPE_H264
        val flags: Byte = if (isKeyFrame) 0x01 else 0x00

        return ByteArray(2 + data.size).apply {
            this[0] = type
            this[1] = flags
            System.arraycopy(data, 0, this, 2, data.size)
        }
    }

    /**
     * 请求 H264 关键帧
     */
    fun requestKeyFrame() {
        if (currentMode == MODE_H264) {
            h264Encoder?.requestKeyFrame()
        }
    }

    /**
     * 动态调整 H264 码率
     */
    fun updateH264Bitrate(bitrate: Int) {
        if (currentMode == MODE_H264) {
            h264Encoder?.updateBitrate(bitrate)
        }
    }

    /**
     * 获取当前模式
     */
    fun getCurrentMode(): String = currentMode

    /**
     * 通知帧已发送完成（用于计算网络延迟）
     */
    fun onFrameSent(frameSize: Int) {
        pendingFrames.decrementAndGet()
        lastAckTime.set(System.currentTimeMillis())
        bytesSentSinceCheck += frameSize
        framesSentSinceCheck++

        // 每5秒检查一次带宽
        val now = System.currentTimeMillis()
        if (now - lastBandwidthCheckTime > 5000 && adaptiveEnabled) {
            checkAndAdaptQuality()
            lastBandwidthCheckTime = now
            framesSentSinceCheck = 0
            bytesSentSinceCheck = 0
        }
    }

    /**
     * 通知发送失败/超时
     */
    fun onFrameDropped() {
        pendingFrames.decrementAndGet()
        consecutiveSlowFrames++
        consecutiveFastFrames = 0

        // 连续丢帧，立即降级
        if (consecutiveSlowFrames >= 3 && adaptiveEnabled) {
            downgradeQuality()
            consecutiveSlowFrames = 0
        }
    }

    /**
     * 检查并自适应调整质量
     */
    private fun checkAndAdaptQuality() {
        val pending = pendingFrames.get()
        val timeSinceLastAck = System.currentTimeMillis() - lastAckTime.get()

        Log.d(TAG, "Bandwidth check: pending=$pending, timeSinceAck=${timeSinceLastAck}ms, " +
                "frames=$framesSentSinceCheck, bytes=$bytesSentSinceCheck")

        when {
            // 网络拥塞：待发送帧多或长时间无响应
            pending > 5 || timeSinceLastAck > 3000 -> {
                consecutiveSlowFrames++
                consecutiveFastFrames = 0
                if (consecutiveSlowFrames >= 2) {
                    downgradeQuality()
                    consecutiveSlowFrames = 0
                }
            }
            // 网络良好：可以尝试升级
            pending <= 1 && timeSinceLastAck < 500 -> {
                consecutiveFastFrames++
                consecutiveSlowFrames = 0
                if (consecutiveFastFrames >= 6) { // 30秒稳定后升级
                    upgradeQuality()
                    consecutiveFastFrames = 0
                }
            }
        }
    }

    /**
     * 降低质量
     */
    private fun downgradeQuality() {
        if (qualityLevel >= QUALITY_VERY_LOW) return

        qualityLevel++
        applyQualityLevel()
        Log.w(TAG, "Quality downgraded to level $qualityLevel")
    }

    /**
     * 提升质量
     */
    private fun upgradeQuality() {
        if (qualityLevel <= QUALITY_HIGH) return

        qualityLevel--
        applyQualityLevel()
        Log.i(TAG, "Quality upgraded to level $qualityLevel")
    }

    /**
     * 应用质量档位
     */
    private fun applyQualityLevel() {
        val (scale, q, fps) = when (qualityLevel) {
            QUALITY_HIGH -> Triple(1.0f, 80, 30)
            QUALITY_MEDIUM -> Triple(0.75f, 60, 20)
            QUALITY_LOW -> Triple(0.5f, 40, 10)
            QUALITY_VERY_LOW -> Triple(0.25f, 30, 5)
            else -> Triple(1.0f, 80, 30)
        }

        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()

        // 只有分辨率变化时才重建 VirtualDisplay
        if (newWidth != currentWidth || newHeight != currentHeight) {
            currentWidth = newWidth
            currentHeight = newHeight

            // 需要重建 VirtualDisplay
            handler?.post {
                recreateVirtualDisplay()
            }
        }

        quality = q
        maxFps = fps

        Log.d(TAG, "Applied quality level $qualityLevel: ${currentWidth}x${currentHeight}, q=$quality, fps=$maxFps")
    }

    /**
     * 重建 VirtualDisplay（分辨率变化时）
     */
    private fun recreateVirtualDisplay() {
        virtualDisplay?.release()
        createImageReader()

        if (mediaProjection != null) {
            tryCreateVirtualDisplayWithMediaProjection()
        } else {
            tryCreateVirtualDisplayWithSystemPermission()
        }
    }

    /**
     * 手动设置质量档位
     */
    fun setQualityLevel(level: Int) {
        if (level in QUALITY_HIGH..QUALITY_VERY_LOW) {
            qualityLevel = level
            applyQualityLevel()
        }
    }

    /**
     * 启用/禁用自适应
     */
    fun setAdaptiveEnabled(enabled: Boolean) {
        adaptiveEnabled = enabled
    }

    fun updateSettings(quality: Int, maxFps: Int) {
        this.quality = quality
        this.maxFps = maxFps
    }

    private fun processImage(reader: ImageReader) {
        if (!isCapturing.get()) return

        // 帧率控制
        val now = System.currentTimeMillis()
        val minInterval = 1000L / maxFps
        if (now - lastFrameTime < minInterval) {
            reader.acquireLatestImage()?.close()
            return
        }

        // 如果待发送帧太多，跳过这一帧（丢帧策略）
        if (pendingFrames.get() > 3) {
            reader.acquireLatestImage()?.close()
            Log.d(TAG, "Frame skipped due to network congestion")
            return
        }

        lastFrameTime = now

        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * currentWidth

            // 创建 Bitmap
            val bitmap = Bitmap.createBitmap(
                currentWidth + rowPadding / pixelStride,
                currentHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪到正确尺寸
            val croppedBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, currentWidth, currentHeight).also {
                    bitmap.recycle()
                }
            } else {
                bitmap
            }

            // 压缩为 JPEG
            val outputStream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            croppedBitmap.recycle()

            val jpegData = outputStream.toByteArray()

            // 标记待发送
            pendingFrames.incrementAndGet()
            frameCallback?.invoke(jpegData)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            image?.close()
        }
    }

    /**
     * 获取当前状态信息
     */
    fun getStats(): CaptureStats {
        return CaptureStats(
            qualityLevel = qualityLevel,
            width = currentWidth,
            height = currentHeight,
            quality = quality,
            fps = maxFps,
            pendingFrames = pendingFrames.get()
        )
    }

    data class CaptureStats(
        val qualityLevel: Int,
        val width: Int,
        val height: Int,
        val quality: Int,
        val fps: Int,
        val pendingFrames: Int
    )
}
