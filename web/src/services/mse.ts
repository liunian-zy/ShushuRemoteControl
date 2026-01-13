/**
 * MSE 播放器 - 使用 jMuxer 将 H264 Annex-B 转换为 fMP4 并通过 MSE 播放
 */
import JMuxer from 'jmuxer'

// 二进制消息类型
const FRAME_TYPE_MJPEG = 0x01
const FRAME_TYPE_H264 = 0x02
const FRAME_TYPE_H264_CONFIG = 0x03

export class MSEPlayer {
  private video: HTMLVideoElement
  private jmuxer: JMuxer | null = null
  private isInitialized = false
  private frameCount = 0

  // 状态回调
  public onError: ((error: string) => void) | null = null
  public onStateChange: ((state: string) => void) | null = null

  constructor(video: HTMLVideoElement) {
    this.video = video
  }

  /**
   * 初始化 MSE
   */
  async init(): Promise<boolean> {
    if (this.isInitialized) {
      return true
    }

    try {
      this.jmuxer = new JMuxer({
        node: this.video,
        mode: 'video',
        flushingTime: 0,  // 立即刷新，降低延迟
        fps: 30,
        debug: false,
        onReady: () => {
          console.log('[MSE] jMuxer ready')
          this.onStateChange?.('ready')
        },
        onError: (error: any) => {
          console.error('[MSE] jMuxer error:', error)
          this.onError?.(error?.message || 'jMuxer error')
        }
      })

      this.isInitialized = true
      this.onStateChange?.('initialized')
      console.log('[MSE] Initialized successfully with jMuxer')
      return true

    } catch (e) {
      console.error('[MSE] Init failed:', e)
      this.onError?.(`MSE 初始化失败: ${e}`)
      return false
    }
  }

  /**
   * 处理二进制消息
   * @returns 'h264' | 'mjpeg' | null 表示消息类型
   */
  handleBinaryMessage(data: ArrayBuffer): 'h264' | 'mjpeg' | null {
    const bytes = new Uint8Array(data)
    if (bytes.length < 2) {
      return null
    }

    const type = bytes[0]

    if (type === FRAME_TYPE_H264 || type === FRAME_TYPE_H264_CONFIG) {
      // H264 数据，去掉头部 2 字节
      const h264Data = bytes.slice(2)
      this.pushH264(h264Data)
      return 'h264'
    } else if (type === FRAME_TYPE_MJPEG) {
      // MJPEG 数据
      return 'mjpeg'
    } else {
      // 旧格式 MJPEG（无类型头）
      return 'mjpeg'
    }
  }

  /**
   * 推送 H264 数据
   */
  pushH264(data: Uint8Array) {
    if (!this.isInitialized || !this.jmuxer) {
      console.warn('[MSE] Not initialized, dropping frame')
      return
    }

    this.frameCount++
    if (this.frameCount % 30 === 1) {
      console.log('[MSE] Pushing H264 frame #' + this.frameCount + ', size:', data.length)
    }

    try {
      this.jmuxer.feed({
        video: data
      })
    } catch (e) {
      console.error('[MSE] Feed error:', e)
    }
  }

  /**
   * 重置播放器
   */
  reset() {
    console.log('[MSE] Resetting')
    this.frameCount = 0
    // jMuxer 没有 reset 方法，需要重新创建
  }

  /**
   * 关闭播放器
   */
  close() {
    console.log('[MSE] Closing')
    this.isInitialized = false
    this.frameCount = 0

    if (this.jmuxer) {
      try {
        this.jmuxer.destroy()
      } catch (e) {
        // 忽略
      }
      this.jmuxer = null
    }

    this.onStateChange?.('closed')
  }

  /**
   * 获取状态信息
   */
  getStats(): { frameCount: number } {
    return {
      frameCount: this.frameCount
    }
  }
}
