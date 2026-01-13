package com.shushu.remote.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * H264 硬件编码器
 * 使用 MediaCodec 进行硬件加速的 H264 编码
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val onFrame: (data: ByteArray, isConfig: Boolean, isKeyFrame: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 2  // 关键帧间隔（秒）
        private const val TIMEOUT_US = 10000L   // 10ms 超时
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    @Volatile
    private var isRunning = false

    /**
     * 启动编码器
     * @return 用于渲染的 Surface
     */
    fun start(): Surface? {
        try {
            Log.d(TAG, "Starting H264 encoder: ${width}x${height}, bitrate=$bitrate, fps=$fps")

            // 创建编码格式
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                // 低延迟模式
                setInteger(MediaFormat.KEY_LATENCY, 0)
                // CBR 模式（恒定码率，更稳定）
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }

            // 创建编码器
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // 创建输入 Surface
            inputSurface = mediaCodec?.createInputSurface()

            // 设置异步回调
            mediaCodec?.setCallback(EncoderCallback())

            // 启动编码器
            mediaCodec?.start()
            isRunning = true

            Log.d(TAG, "H264 encoder started successfully")
            return inputSurface

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H264 encoder", e)
            release()
            return null
        }
    }

    /**
     * 请求关键帧（用于新客户端连接或错误恢复）
     */
    fun requestKeyFrame() {
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            mediaCodec?.setParameters(params)
            Log.d(TAG, "Keyframe requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request keyframe", e)
        }
    }

    /**
     * 动态调整码率
     */
    fun updateBitrate(newBitrate: Int) {
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
            mediaCodec?.setParameters(params)
            Log.d(TAG, "Bitrate updated to $newBitrate")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update bitrate", e)
        }
    }

    /**
     * 停止并释放编码器
     */
    fun release() {
        Log.d(TAG, "Releasing H264 encoder")
        isRunning = false

        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping codec", e)
        }

        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing codec", e)
        }

        inputSurface?.release()
        inputSurface = null
        mediaCodec = null

        Log.d(TAG, "H264 encoder released")
    }

    /**
     * 编码器回调
     */
    private inner class EncoderCallback : MediaCodec.Callback() {

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface 模式不需要处理输入缓冲区
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            if (!isRunning) {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    // 忽略
                }
                return
            }

            try {
                val buffer = codec.getOutputBuffer(index) ?: return

                // 跳过空帧
                if (info.size <= 0) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }

                // 读取数据
                val data = ByteArray(info.size)
                buffer.get(data)

                // 判断帧类型
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                // 回调输出
                onFrame(data, isConfig, isKeyFrame)

                if (isConfig) {
                    Log.d(TAG, "Config frame (SPS/PPS): ${data.size} bytes")
                } else if (isKeyFrame) {
                    Log.d(TAG, "Keyframe: ${data.size} bytes")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing output buffer", e)
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Codec error: ${e.message}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.d(TAG, "Output format changed: $format")
        }
    }
}
