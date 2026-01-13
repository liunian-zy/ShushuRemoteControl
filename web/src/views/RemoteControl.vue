<template>
  <div class="remote-control">
    <!-- 顶部标题栏 -->
    <div class="header">
      <button class="btn btn-icon" @click="goBack" title="返回">
        ←
      </button>
      <div class="device-title">
        <span class="status-dot" :class="status === 'connected' ? 'online' : 'offline'"></span>
        {{ deviceName || deviceId }}
      </div>
      <div class="header-actions">
        <button class="btn btn-icon" @click="toggleSettings" title="设置">
          ⚙
        </button>
        <button class="btn btn-icon" @click="toggleClipboard" title="剪贴板">
          📋
        </button>
      </div>
    </div>

    <!-- 连接信息栏 -->
    <div v-show="status === 'connected'" class="connection-bar">
      <div class="conn-info">
        <span class="conn-mode" :class="streamMode">
          {{ streamMode === 'webrtc' ? 'WebRTC' : streamMode === 'h264' ? 'H264' : 'MJPEG' }}
        </span>
        <span class="conn-stats" v-if="connectionStats.fps > 0">
          {{ connectionStats.fps }} FPS
        </span>
        <span class="conn-stats" v-if="connectionStats.bitrate > 0">
          {{ formatBitrate(connectionStats.bitrate) }}
        </span>
        <span class="conn-stats" v-if="connectionStats.resolution">
          {{ connectionStats.resolution }}
        </span>
        <span class="conn-latency" v-if="connectionStats.latency > 0" :class="getLatencyClass(connectionStats.latency)">
          {{ connectionStats.latency }}ms
        </span>
      </div>
      <div class="conn-quality">
        <span class="quality-label">画质:</span>
        <select v-model="currentQuality" @change="changeQuality" class="quality-select">
          <option value="low">低 (省流)</option>
          <option value="medium">中</option>
          <option value="high">高</option>
          <option value="ultra">超清</option>
        </select>
      </div>
    </div>

    <!-- 连接状态 -->
    <div v-if="status === 'connecting'" class="status-overlay">
      <div class="spinner"></div>
      <p>正在连接设备...</p>
    </div>

    <div v-else-if="status === 'error'" class="status-overlay error">
      <p>{{ errorMessage }}</p>
      <button class="btn btn-primary" @click="reconnect">重新连接</button>
    </div>

    <!-- 屏幕显示区域 -->
    <div
      v-show="status === 'connected'"
      class="screen-container"
      ref="screenContainer"
    >
      <!-- WebRTC / H264 视频流 -->
      <video
        v-show="streamMode === 'webrtc' || streamMode === 'h264'"
        ref="remoteVideo"
        class="screen-video"
        autoplay
        playsinline
        muted
        @mousedown="onMouseDown"
        @mousemove="onMouseMove"
        @mouseup="onMouseUp"
        @mouseleave="onMouseUp"
        @wheel.prevent="onWheel"
        @touchstart.prevent="onTouchStart"
        @touchmove.prevent="onTouchMove"
        @touchend.prevent="onTouchEnd"
      ></video>
      <!-- MJPEG Canvas 回退 -->
      <canvas
        v-show="streamMode === 'mjpeg'"
        ref="screenCanvas"
        class="screen-canvas"
        @mousedown="onMouseDown"
        @mousemove="onMouseMove"
        @mouseup="onMouseUp"
        @mouseleave="onMouseUp"
        @wheel.prevent="onWheel"
        @touchstart.prevent="onTouchStart"
        @touchmove.prevent="onTouchMove"
        @touchend.prevent="onTouchEnd"
      ></canvas>
    </div>

    <!-- 底部工具栏 -->
    <div v-show="status === 'connected'" class="bottom-toolbar">
      <!-- 导航按钮组 -->
      <div class="toolbar-group">
        <button class="tool-btn" @click="sendKey(4)" title="返回">
          <span class="icon">◀</span>
          <span class="label">返回</span>
        </button>
        <button class="tool-btn" @click="sendKey(3)" title="主页">
          <span class="icon">●</span>
          <span class="label">主页</span>
        </button>
        <button class="tool-btn" @click="sendKey(187)" title="最近应用">
          <span class="icon">▢</span>
          <span class="label">最近</span>
        </button>
      </div>

      <!-- 分隔线 -->
      <div class="toolbar-divider"></div>

      <!-- 音量控制 -->
      <div class="toolbar-group">
        <button class="tool-btn" @click="sendKey(24)" title="音量+">
          <span class="icon">🔊</span>
          <span class="label">音量+</span>
        </button>
        <button class="tool-btn" @click="sendKey(25)" title="音量-">
          <span class="icon">🔉</span>
          <span class="label">音量-</span>
        </button>
        <button class="tool-btn" @click="sendKey(164)" title="静音">
          <span class="icon">🔇</span>
          <span class="label">静音</span>
        </button>
      </div>

      <!-- 分隔线 -->
      <div class="toolbar-divider"></div>

      <!-- 电源和系统 -->
      <div class="toolbar-group">
        <button class="tool-btn" @click="sendKey(26)" title="电源键">
          <span class="icon">⏻</span>
          <span class="label">电源</span>
        </button>
        <button class="tool-btn" @click="sendKey(223)" title="锁屏">
          <span class="icon">🔒</span>
          <span class="label">锁屏</span>
        </button>
        <button class="tool-btn" @click="sendKey(220)" title="亮度-">
          <span class="icon">🔅</span>
          <span class="label">亮度-</span>
        </button>
        <button class="tool-btn" @click="sendKey(221)" title="亮度+">
          <span class="icon">🔆</span>
          <span class="label">亮度+</span>
        </button>
      </div>

      <!-- 分隔线 -->
      <div class="toolbar-divider"></div>

      <!-- 其他功能 -->
      <div class="toolbar-group">
        <button class="tool-btn" @click="sendKey(27)" title="相机">
          <span class="icon">📷</span>
          <span class="label">相机</span>
        </button>
        <button class="tool-btn" @click="takeScreenshot" title="截图">
          <span class="icon">📸</span>
          <span class="label">截图</span>
        </button>
        <button class="tool-btn" @click="rotateScreen" title="旋转">
          <span class="icon">🔄</span>
          <span class="label">旋转</span>
        </button>
      </div>
    </div>

    <!-- 剪贴板面板 -->
    <div v-if="showClipboard" class="clipboard-panel">
      <div class="clipboard-header">
        <span>剪贴板同步</span>
        <button class="btn-close" @click="showClipboard = false">×</button>
      </div>
      <textarea
        v-model="clipboardText"
        class="clipboard-input"
        placeholder="输入要发送到设备的文本..."
      ></textarea>
      <div class="clipboard-actions">
        <button class="btn btn-secondary" @click="pasteFromLocal">从本地粘贴</button>
        <button class="btn btn-primary" @click="sendClipboard(true)">发送并输入</button>
        <button class="btn btn-secondary" @click="sendClipboard(false)">仅设置剪贴板</button>
      </div>
      <div v-if="deviceClipboard" class="device-clipboard">
        <p class="label">设备剪贴板内容:</p>
        <div class="content">{{ deviceClipboard }}</div>
        <button class="btn btn-secondary" @click="copyToLocal">复制到本地</button>
      </div>
    </div>

    <!-- 设置面板 -->
    <div v-if="showSettings" class="settings-panel">
      <div class="settings-header">
        <span>连接设置</span>
        <button class="btn-close" @click="showSettings = false">×</button>
      </div>

      <div class="settings-section">
        <h4>连接状态</h4>
        <div class="settings-info">
          <div class="info-row">
            <span class="info-label">传输模式</span>
            <span class="info-value" :class="streamMode">{{ streamMode === 'webrtc' ? 'WebRTC (P2P)' : streamMode === 'h264' ? 'H264 (服务器中转)' : 'MJPEG (服务器中转)' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">连接状态</span>
            <span class="info-value" :class="getConnectionStateClass()">{{ getConnectionStateText() }}</span>
          </div>
          <div class="info-row" v-if="connectionStats.resolution">
            <span class="info-label">分辨率</span>
            <span class="info-value">{{ connectionStats.resolution }}</span>
          </div>
          <div class="info-row" v-if="connectionStats.fps > 0">
            <span class="info-label">帧率</span>
            <span class="info-value">{{ connectionStats.fps }} FPS</span>
          </div>
          <div class="info-row" v-if="connectionStats.bitrate > 0">
            <span class="info-label">码率</span>
            <span class="info-value">{{ formatBitrate(connectionStats.bitrate) }}</span>
          </div>
          <div class="info-row" v-if="connectionStats.latency > 0">
            <span class="info-label">延迟</span>
            <span class="info-value" :class="getLatencyClass(connectionStats.latency)">{{ connectionStats.latency }}ms</span>
          </div>
          <div class="info-row" v-if="connectionStats.packetsLost > 0">
            <span class="info-label">丢包</span>
            <span class="info-value warning">{{ connectionStats.packetsLost }}</span>
          </div>
        </div>
      </div>

      <div class="settings-section">
        <h4>画质设置</h4>
        <div class="quality-options">
          <label class="quality-option" v-for="q in qualityOptions" :key="q.value">
            <input type="radio" :value="q.value" v-model="currentQuality" @change="changeQuality">
            <div class="quality-card" :class="{ active: currentQuality === q.value }">
              <span class="quality-name">{{ q.label }}</span>
              <span class="quality-desc">{{ q.desc }}</span>
            </div>
          </label>
        </div>
      </div>

      <div class="settings-section">
        <h4>高级选项</h4>
        <div class="settings-options">
          <label class="option-row">
            <input type="checkbox" v-model="autoQuality" @change="toggleAutoQuality">
            <span>自动调整画质</span>
          </label>
          <label class="option-row">
            <input type="checkbox" v-model="showStatsBar">
            <span>显示状态栏</span>
          </label>
        </div>
      </div>

      <div class="settings-actions">
        <button class="btn btn-secondary" @click="forceReconnect">重新连接</button>
        <button class="btn btn-secondary" @click="switchStreamMode">
          切换到 {{ streamMode === 'h264' ? 'MJPEG' : 'H264' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { WebSocketService } from '../services/websocket'
import { WebRTCClient, createAnswerMessage, createIceCandidateMessage } from '../services/webrtc'
import { MSEPlayer } from '../services/mse'

const props = defineProps<{
  deviceId: string
}>()

const router = useRouter()

const status = ref<'connecting' | 'connected' | 'error'>('connecting')
const errorMessage = ref('')
const deviceName = ref('')
const screenWidth = ref(1920)
const screenHeight = ref(1080)
const streamMode = ref<'webrtc' | 'h264' | 'mjpeg'>('mjpeg')  // 当前流模式

const screenContainer = ref<HTMLDivElement>()
const screenCanvas = ref<HTMLCanvasElement>()
const remoteVideo = ref<HTMLVideoElement>()
let ctx: CanvasRenderingContext2D | null = null

// MSE 播放器
let msePlayer: MSEPlayer | null = null

const showClipboard = ref(false)
const showSettings = ref(false)
const clipboardText = ref('')
const deviceClipboard = ref('')

// 连接状态
const webrtcState = ref<string>('disconnected')
const connectionStats = ref({
  fps: 0,
  bitrate: 0,
  latency: 0,
  resolution: '',
  packetsLost: 0,
  bytesReceived: 0
})

// 画质设置
const currentQuality = ref('medium')
const autoQuality = ref(true)
const showStatsBar = ref(true)
const qualityOptions = [
  { value: 'low', label: '低画质', desc: '省流量，适合弱网' },
  { value: 'medium', label: '中画质', desc: '平衡画质和流量' },
  { value: 'high', label: '高画质', desc: '清晰，需要较好网络' },
  { value: 'ultra', label: '超清', desc: '最佳画质，高带宽' }
]

// 画质参数映射 - 支持 H264 和 MJPEG
const qualityParams: Record<string, { mode: string; quality: number; maxFps: number; bitrate: number; fps: number }> = {
  low: { mode: 'h264', quality: 40, maxFps: 15, bitrate: 500000, fps: 15 },
  medium: { mode: 'h264', quality: 60, maxFps: 24, bitrate: 1000000, fps: 24 },
  high: { mode: 'h264', quality: 80, maxFps: 30, bitrate: 2000000, fps: 30 },
  ultra: { mode: 'h264', quality: 95, maxFps: 60, bitrate: 4000000, fps: 30 }
}

let ws: WebSocketService | null = null
let webrtcClient: WebRTCClient | null = null
let statsInterval: number | null = null
let isMouseDown = false
let mouseDownPos = { x: 0, y: 0 }
let mouseDownTime = 0
let lastBytesReceived = 0
let lastStatsTime = 0
const LONG_PRESS_DURATION = 500 // 长按阈值 500ms
const MOVE_THRESHOLD = 10 // 移动阈值，超过则视为滑动

onMounted(() => {
  ws = new WebSocketService()

  ws.on('open', () => {
    // 请求控制设备
    ws?.send({
      type: 'control.request',
      deviceId: props.deviceId
    })
  })

  ws.on('close', () => {
    // WebSocket 会自动重连，重连后会重新触发 open 事件
    if (status.value === 'connected') {
      status.value = 'connecting'
    }
    // 关闭 WebRTC 连接
    webrtcClient?.close()
    webrtcClient = null
  })

  ws.on('control.granted', (data) => {
    status.value = 'connected'
    deviceName.value = data.deviceName || props.deviceId
    screenWidth.value = data.screenWidth
    screenHeight.value = data.screenHeight

    nextTick(() => {
      initCanvas()
      initVideo()
      startStatsUpdate()

      // 请求 H264 流（服务器已经发送了 stream.start，这里不需要再发送）
      // 如果需要手动切换画质，可以调用 changeQuality()
      console.log('Control granted, waiting for H264 stream')
    })
  })

  ws.on('error', (data) => {
    status.value = 'error'
    errorMessage.value = data.message || '连接失败'
  })

  ws.on('clipboard.update', (data) => {
    deviceClipboard.value = data.text
  })

  // WebRTC 信令处理
  ws.on('webrtc.offer', async (data) => {
    console.log('Received WebRTC offer')
    await handleWebRTCOffer(data)
  })

  ws.on('webrtc.ice', async (data) => {
    console.log('Received ICE candidate')
    if (webrtcClient && data.candidate) {
      await webrtcClient.addIceCandidate(data.candidate)
    }
  })

  // MJPEG 回退
  ws.onBinary(async (data: ArrayBuffer) => {
    // 使用 MSE 播放器处理二进制消息
    const bytes = new Uint8Array(data)

    // 检查消息类型
    if (bytes.length >= 2) {
      const type = bytes[0]

      // H264 帧 (0x02) 或 H264 配置 (0x03)
      if (type === 0x02 || type === 0x03) {
        // 初始化 MSE 播放器
        if (!msePlayer && remoteVideo.value) {
          msePlayer = new MSEPlayer(remoteVideo.value)
          msePlayer.onError = (error) => {
            console.error('MSE error:', error)
            // H264 失败，回退到 MJPEG
            fallbackToMJPEG()
          }
          msePlayer.onStateChange = (state) => {
            console.log('MSE state:', state)
          }
          const success = await msePlayer.init()
          if (!success) {
            console.error('MSE init failed, falling back to MJPEG')
            fallbackToMJPEG()
            return
          }
        }

        // 推送 H264 数据
        if (msePlayer) {
          const h264Data = bytes.slice(2)
          msePlayer.pushH264(h264Data)

          if (streamMode.value !== 'h264') {
            console.log('Switching to H264 mode')
            streamMode.value = 'h264'
          }
        }
        return
      }
    }

    // MJPEG 模式（旧格式或 type=0x01）
    if (streamMode.value !== 'mjpeg') {
      console.log('Switching to MJPEG mode')
      streamMode.value = 'mjpeg'
    }

    // 如果有类型头，去掉
    const frameData = bytes[0] === 0x01 ? data.slice(2) : data
    renderFrame(frameData)
  })

  ws.connect('/ws/controller')

  // 监听键盘事件
  window.addEventListener('keydown', onKeyDown)
})

onUnmounted(() => {
  ws?.send({ type: 'control.release' })
  ws?.disconnect()
  webrtcClient?.close()
  msePlayer?.close()
  msePlayer = null
  stopStatsUpdate()
  window.removeEventListener('keydown', onKeyDown)
})

/**
 * 处理 WebRTC Offer
 */
async function handleWebRTCOffer(data: any) {
  try {
    // 初始化 WebRTC 客户端
    if (!webrtcClient) {
      webrtcClient = new WebRTCClient()

      webrtcClient.onRemoteStream = (stream) => {
        console.log('Remote stream received')
        if (remoteVideo.value) {
          remoteVideo.value.srcObject = stream
          streamMode.value = 'webrtc'
          console.log('Switched to WebRTC mode')
        }
      }

      webrtcClient.onIceCandidate = (candidate) => {
        ws?.send(createIceCandidateMessage(candidate, props.deviceId))
      }

      webrtcClient.onConnectionStateChange = (state) => {
        console.log('WebRTC connection state:', state)
        webrtcState.value = state
        if (state === 'failed' || state === 'disconnected') {
          console.log('WebRTC failed, falling back to MJPEG')
          streamMode.value = 'mjpeg'
        }
      }

      webrtcClient.onIceConnectionStateChange = (state) => {
        console.log('WebRTC ICE state:', state)
        if (state === 'connected' || state === 'completed') {
          webrtcState.value = 'connected'
        }
      }

      webrtcClient.onError = (error) => {
        console.error('WebRTC error:', error)
      }

      await webrtcClient.initialize()
    }

    // 处理 Offer 并生成 Answer
    const answer = await webrtcClient.handleOffer(data.sdp)
    if (answer) {
      ws?.send(createAnswerMessage(answer, props.deviceId))
      console.log('WebRTC answer sent')
    }
  } catch (e) {
    console.error('Failed to handle WebRTC offer:', e)
    streamMode.value = 'mjpeg'
  }
}

function initCanvas() {
  if (!screenCanvas.value || !screenContainer.value) return

  const canvas = screenCanvas.value
  const container = screenContainer.value
  ctx = canvas.getContext('2d')

  // 计算适合容器的尺寸，保持宽高比
  const containerWidth = container.clientWidth
  const containerHeight = container.clientHeight
  const aspectRatio = screenWidth.value / screenHeight.value

  let canvasWidth = containerWidth
  let canvasHeight = containerWidth / aspectRatio

  if (canvasHeight > containerHeight) {
    canvasHeight = containerHeight
    canvasWidth = containerHeight * aspectRatio
  }

  canvas.style.width = `${canvasWidth}px`
  canvas.style.height = `${canvasHeight}px`
  canvas.width = screenWidth.value
  canvas.height = screenHeight.value
}

function initVideo() {
  if (!remoteVideo.value || !screenContainer.value) return

  const video = remoteVideo.value
  const container = screenContainer.value

  // 计算适合容器的尺寸，保持宽高比
  const containerWidth = container.clientWidth
  const containerHeight = container.clientHeight
  const aspectRatio = screenWidth.value / screenHeight.value

  let videoWidth = containerWidth
  let videoHeight = containerWidth / aspectRatio

  if (videoHeight > containerHeight) {
    videoHeight = containerHeight
    videoWidth = containerHeight * aspectRatio
  }

  video.style.width = `${videoWidth}px`
  video.style.height = `${videoHeight}px`
}

function renderFrame(data: ArrayBuffer) {
  if (!ctx || !screenCanvas.value) return

  const blob = new Blob([data], { type: 'image/jpeg' })
  const url = URL.createObjectURL(blob)
  const img = new Image()

  img.onload = () => {
    ctx!.drawImage(img, 0, 0)
    URL.revokeObjectURL(url)
  }

  img.src = url
}

function getRelativePosition(e: MouseEvent | Touch): { x: number; y: number } {
  // 根据当前模式选择元素（webrtc 和 h264 都使用 video 元素）
  const useVideo = streamMode.value === 'webrtc' || streamMode.value === 'h264'
  const element = useVideo ? remoteVideo.value : screenCanvas.value

  if (!element) {
    console.warn('getRelativePosition: element is null, streamMode=', streamMode.value)
    return { x: 0, y: 0 }
  }

  const rect = element.getBoundingClientRect()

  // 防止除以零
  if (rect.width === 0 || rect.height === 0) {
    console.warn('getRelativePosition: element has zero size', rect)
    return { x: 0, y: 0 }
  }

  const x = ((e.clientX - rect.left) / rect.width) * screenWidth.value
  const y = ((e.clientY - rect.top) / rect.height) * screenHeight.value

  return { x: Math.round(x), y: Math.round(y) }
}

function onMouseDown(e: MouseEvent) {
  isMouseDown = true
  const pos = getRelativePosition(e)
  mouseDownPos = pos
  mouseDownTime = Date.now()
}

function onMouseMove(_e: MouseEvent) {
  // 鼠标移动时不做处理，松开时根据位置判断
}

function onMouseUp(e: MouseEvent) {
  if (!isMouseDown) return
  isMouseDown = false

  const pos = e instanceof MouseEvent ? getRelativePosition(e) : mouseDownPos
  const pressDuration = Date.now() - mouseDownTime

  // 判断是滑动、长按还是点击
  const dx = Math.abs(pos.x - mouseDownPos.x)
  const dy = Math.abs(pos.y - mouseDownPos.y)

  if (dx > MOVE_THRESHOLD || dy > MOVE_THRESHOLD) {
    // 滑动
    ws?.send({
      type: 'input.touch',
      action: 'swipe',
      startX: mouseDownPos.x,
      startY: mouseDownPos.y,
      endX: pos.x,
      endY: pos.y,
      duration: Math.min(pressDuration, 500),
      pointerId: 0
    })
  } else if (pressDuration >= LONG_PRESS_DURATION) {
    // 长按
    ws?.send({
      type: 'input.touch',
      action: 'longpress',
      x: mouseDownPos.x,
      y: mouseDownPos.y,
      pointerId: 0
    })
  } else {
    // 点击
    ws?.send({
      type: 'input.touch',
      action: 'tap',
      x: mouseDownPos.x,
      y: mouseDownPos.y,
      pointerId: 0
    })
  }
}

let touchStartPos = { x: 0, y: 0 }
let touchStartTime = 0

function onTouchStart(e: TouchEvent) {
  const touch = e.touches[0]
  const pos = getRelativePosition(touch)
  touchStartPos = pos
  touchStartTime = Date.now()
}

function onTouchMove(_e: TouchEvent) {
  // 触摸移动时不做处理，等松开时判断
}

function onTouchEnd(e: TouchEvent) {
  const lastTouch = e.changedTouches[0]
  const pos = lastTouch ? getRelativePosition(lastTouch) : touchStartPos
  const pressDuration = Date.now() - touchStartTime

  // 判断是滑动、长按还是点击
  const dx = Math.abs(pos.x - touchStartPos.x)
  const dy = Math.abs(pos.y - touchStartPos.y)

  if (dx > MOVE_THRESHOLD || dy > MOVE_THRESHOLD) {
    // 滑动
    ws?.send({
      type: 'input.touch',
      action: 'swipe',
      startX: touchStartPos.x,
      startY: touchStartPos.y,
      endX: pos.x,
      endY: pos.y,
      duration: Math.min(pressDuration, 500),
      pointerId: 0
    })
  } else if (pressDuration >= LONG_PRESS_DURATION) {
    // 长按
    ws?.send({
      type: 'input.touch',
      action: 'longpress',
      x: touchStartPos.x,
      y: touchStartPos.y,
      pointerId: 0
    })
  } else {
    // 点击
    ws?.send({
      type: 'input.touch',
      action: 'tap',
      x: touchStartPos.x,
      y: touchStartPos.y,
      pointerId: 0
    })
  }
}

// 鼠标滚轮事件
function onWheel(e: WheelEvent) {
  const pos = getRelativePosition(e)
  // deltaY > 0 表示向下滚动，vScroll 应为负值
  // deltaY < 0 表示向上滚动，vScroll 应为正值
  const vScroll = -Math.sign(e.deltaY)
  const hScroll = -Math.sign(e.deltaX)

  ws?.send({
    type: 'input.touch',
    action: 'scroll',
    x: pos.x,
    y: pos.y,
    hScroll: hScroll,
    vScroll: vScroll
  })
}

function toggleClipboard() {
  showClipboard.value = !showClipboard.value
}

async function pasteFromLocal() {
  try {
    clipboardText.value = await navigator.clipboard.readText()
  } catch {
    alert('无法访问剪贴板，请手动粘贴')
  }
}

function sendClipboard(autoPaste: boolean = true) {
  if (!clipboardText.value) return
  ws?.send({
    type: 'clipboard.set',
    text: clipboardText.value,
    autoPaste: autoPaste
  })
}

async function copyToLocal() {
  try {
    await navigator.clipboard.writeText(deviceClipboard.value)
    alert('已复制到本地剪贴板')
  } catch {
    alert('无法写入剪贴板')
  }
}

function goBack() {
  router.push('/')
}

function reconnect() {
  status.value = 'connecting'
  ws?.disconnect()
  ws?.connect('/ws/controller')
}

// 发送按键
function sendKey(keyCode: number) {
  ws?.send({
    type: 'input.key',
    keyCode: keyCode,
    action: 'down'
  })
}

// 截图（保存当前画面）
function takeScreenshot() {
  if (!screenCanvas.value) return
  const link = document.createElement('a')
  link.download = `screenshot_${Date.now()}.png`
  link.href = screenCanvas.value.toDataURL('image/png')
  link.click()
}

// 旋转屏幕（发送旋转命令）
function rotateScreen() {
  // 发送旋转快捷键组合或者通过 shell 命令
  // 这里暂时用 Ctrl+Alt+方向键模拟
  ws?.send({
    type: 'input.key',
    keyCode: 112, // F1 作为旋转触发
    action: 'down'
  })
}

// Android KeyCode 映射 - 键盘按键到 Android KeyCode
const keyCodeMap: Record<string, number> = {
  'Backspace': 67,    // KEYCODE_DEL
  'Enter': 66,        // KEYCODE_ENTER
  'Escape': 4,        // KEYCODE_BACK (ESC 映射到返回)
  'ArrowUp': 19,      // KEYCODE_DPAD_UP
  'ArrowDown': 20,    // KEYCODE_DPAD_DOWN
  'ArrowLeft': 21,    // KEYCODE_DPAD_LEFT
  'ArrowRight': 22,   // KEYCODE_DPAD_RIGHT
  'Tab': 61,          // KEYCODE_TAB
  'Delete': 67,       // KEYCODE_DEL (Delete 也映射到删除)
  'PageUp': 92,       // KEYCODE_PAGE_UP
  'PageDown': 93,     // KEYCODE_PAGE_DOWN
}

// 键盘事件处理
function onKeyDown(e: KeyboardEvent) {
  // 如果剪贴板面板或设置面板打开且焦点在输入框，不拦截
  if (showClipboard.value || showSettings.value) return

  // 特殊按键
  if (keyCodeMap[e.key]) {
    e.preventDefault()
    sendKey(keyCodeMap[e.key])
    return
  }

  // 普通字符输入 - 发送文本
  if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
    e.preventDefault()
    ws?.send({
      type: 'input.text',
      text: e.key
    })
  }
}

// 切换设置面板
function toggleSettings() {
  showSettings.value = !showSettings.value
  showClipboard.value = false
}

// 格式化码率
function formatBitrate(bps: number): string {
  if (bps >= 1000000) {
    return (bps / 1000000).toFixed(1) + ' Mbps'
  } else if (bps >= 1000) {
    return (bps / 1000).toFixed(0) + ' Kbps'
  }
  return bps + ' bps'
}

// 获取延迟等级样式
function getLatencyClass(latency: number): string {
  if (latency < 100) return 'good'
  if (latency < 200) return 'medium'
  return 'poor'
}

// 获取连接状态文本
function getConnectionStateText(): string {
  // H264 或 MJPEG 模式：只要 status 是 connected 就表示已连接
  if (streamMode.value === 'h264' || streamMode.value === 'mjpeg') {
    return status.value === 'connected' ? '已连接' : '未连接'
  }

  // WebRTC 模式
  const stateMap: Record<string, string> = {
    'new': '初始化',
    'connecting': '连接中',
    'connected': '已连接',
    'disconnected': '已断开',
    'failed': '连接失败',
    'closed': '已关闭'
  }
  return stateMap[webrtcState.value] || webrtcState.value
}

// 获取连接状态样式类
function getConnectionStateClass(): string {
  if (streamMode.value === 'h264' || streamMode.value === 'mjpeg') {
    return status.value === 'connected' ? 'connected' : 'disconnected'
  }
  return webrtcState.value
}

// 切换画质
function changeQuality() {
  const params = qualityParams[currentQuality.value]

  // 优先使用 H264 模式
  ws?.send({
    type: 'stream.start',
    mode: params.mode,
    bitrate: params.bitrate,
    fps: params.fps,
    quality: params.quality,
    maxFps: params.maxFps
  })
  console.log('Quality changed to:', currentQuality.value, params)
}

// 回退到 MJPEG 模式
function fallbackToMJPEG() {
  console.log('Falling back to MJPEG mode')

  // 关闭 MSE 播放器
  msePlayer?.close()
  msePlayer = null

  streamMode.value = 'mjpeg'

  // 请求 MJPEG 流
  const params = qualityParams[currentQuality.value]
  ws?.send({
    type: 'stream.start',
    mode: 'mjpeg',
    quality: params.quality,
    maxFps: params.maxFps
  })
}

// 切换自动画质
function toggleAutoQuality() {
  // 发送自动画质设置到设备
  ws?.send({
    type: 'stream.config',
    autoQuality: autoQuality.value
  })
}

// 强制重新连接
function forceReconnect() {
  webrtcClient?.close()
  webrtcClient = null
  msePlayer?.close()
  msePlayer = null
  streamMode.value = 'mjpeg'
  webrtcState.value = 'disconnected'

  // 请求 H264 流
  changeQuality()
}

// 切换流模式
function switchStreamMode() {
  if (streamMode.value === 'h264') {
    // 从 H264 切换到 MJPEG
    fallbackToMJPEG()
  } else if (streamMode.value === 'mjpeg') {
    // 从 MJPEG 切换到 H264
    msePlayer?.close()
    msePlayer = null

    const params = qualityParams[currentQuality.value]
    ws?.send({
      type: 'stream.start',
      mode: 'h264',
      bitrate: params.bitrate,
      fps: params.fps
    })
  } else {
    // WebRTC 模式，切换到 H264
    webrtcClient?.close()
    webrtcClient = null
    webrtcState.value = 'disconnected'

    const params = qualityParams[currentQuality.value]
    ws?.send({
      type: 'stream.start',
      mode: 'h264',
      bitrate: params.bitrate,
      fps: params.fps
    })
  }
}

// 启动统计信息更新
function startStatsUpdate() {
  if (statsInterval) return

  statsInterval = window.setInterval(async () => {
    if (streamMode.value === 'webrtc' && webrtcClient) {
      const stats = await webrtcClient.getStats()
      if (stats) {
        const now = Date.now()
        const timeDiff = (now - lastStatsTime) / 1000

        if (lastStatsTime > 0 && timeDiff > 0) {
          const bytesDiff = stats.bytesReceived - lastBytesReceived
          connectionStats.value.bitrate = Math.round((bytesDiff * 8) / timeDiff)
        }

        connectionStats.value.fps = stats.framesPerSecond || 0
        connectionStats.value.packetsLost = stats.packetsLost || 0
        connectionStats.value.latency = Math.round((stats.jitter || 0) * 1000)

        if (stats.frameWidth && stats.frameHeight) {
          connectionStats.value.resolution = `${stats.frameWidth}x${stats.frameHeight}`
        }

        lastBytesReceived = stats.bytesReceived
        lastStatsTime = now
      }
    }
  }, 1000)
}

// 停止统计信息更新
function stopStatsUpdate() {
  if (statsInterval) {
    clearInterval(statsInterval)
    statsInterval = null
  }
}
</script>

<style scoped>
.remote-control {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: #0a0a0a;
}

/* 顶部标题栏 */
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background-color: #1a1a2e;
  border-bottom: 1px solid #333;
}

.device-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-dot.online {
  background-color: #22c55e;
}

.status-dot.offline {
  background-color: #ef4444;
}

.btn-icon {
  width: 36px;
  height: 36px;
  padding: 0;
  font-size: 18px;
  background-color: transparent;
  border: 1px solid #444;
  border-radius: 6px;
  color: #eee;
  cursor: pointer;
}

.btn-icon:hover {
  background-color: #333;
}

/* 状态覆盖层 */
.status-overlay {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 20px;
  color: #888;
}

.status-overlay.error {
  color: #ef4444;
}

.spinner {
  width: 40px;
  height: 40px;
  border: 3px solid #333;
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 屏幕容器 */
.screen-container {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  overflow: hidden;
  padding: 10px;
}

.screen-canvas {
  background-color: #000;
  cursor: pointer;
  box-shadow: 0 0 30px rgba(0, 0, 0, 0.5);
}

.screen-video {
  background-color: #000;
  cursor: pointer;
  box-shadow: 0 0 30px rgba(0, 0, 0, 0.5);
  object-fit: contain;
}

/* 底部工具栏 */
.bottom-toolbar {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background-color: #1a1a2e;
  border-top: 1px solid #333;
  flex-wrap: wrap;
}

.toolbar-group {
  display: flex;
  gap: 4px;
}

.toolbar-divider {
  width: 1px;
  height: 40px;
  background-color: #444;
  margin: 0 8px;
}

.tool-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 52px;
  padding: 4px;
  background-color: #252542;
  border: 1px solid #3a3a5c;
  border-radius: 8px;
  color: #ddd;
  cursor: pointer;
  transition: all 0.2s;
}

.tool-btn:hover {
  background-color: #3a3a5c;
  border-color: #4a4a7c;
}

.tool-btn:active {
  background-color: #4a4a7c;
  transform: scale(0.95);
}

.tool-btn .icon {
  font-size: 18px;
  line-height: 1;
}

.tool-btn .label {
  font-size: 10px;
  margin-top: 2px;
  color: #aaa;
}

/* 剪贴板面板 */
.clipboard-panel {
  position: fixed;
  right: 20px;
  top: 60px;
  width: 320px;
  background-color: #16213e;
  border-radius: 12px;
  border: 1px solid #1f3460;
  padding: 16px;
  z-index: 100;
}

.clipboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  font-weight: 600;
}

.btn-close {
  background: none;
  border: none;
  color: #888;
  font-size: 24px;
  cursor: pointer;
}

.btn-close:hover {
  color: #fff;
}

.clipboard-input {
  width: 100%;
  height: 100px;
  padding: 12px;
  border: 1px solid #333;
  border-radius: 6px;
  background-color: #0f0f23;
  color: #eee;
  font-size: 14px;
  resize: none;
  margin-bottom: 12px;
}

.clipboard-input:focus {
  outline: none;
  border-color: #3b82f6;
}

.clipboard-actions {
  display: flex;
  gap: 10px;
}

.clipboard-actions .btn {
  flex: 1;
  padding: 8px;
  font-size: 13px;
}

.device-clipboard {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #333;
}

.device-clipboard .label {
  font-size: 12px;
  color: #888;
  margin-bottom: 8px;
}

.device-clipboard .content {
  background-color: #0f0f23;
  padding: 10px;
  border-radius: 6px;
  font-size: 13px;
  max-height: 80px;
  overflow-y: auto;
  margin-bottom: 10px;
  word-break: break-all;
}

/* 通用按钮样式 */
.btn {
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  border: none;
  transition: all 0.2s;
}

.btn-primary {
  background-color: #3b82f6;
  color: white;
}

.btn-primary:hover {
  background-color: #2563eb;
}

.btn-secondary {
  background-color: #374151;
  color: #eee;
}

.btn-secondary:hover {
  background-color: #4b5563;
}

/* 响应式 */
@media (max-width: 600px) {
  .bottom-toolbar {
    padding: 8px;
    gap: 4px;
  }

  .toolbar-divider {
    display: none;
  }

  .tool-btn {
    width: 48px;
    height: 44px;
  }

  .tool-btn .icon {
    font-size: 16px;
  }

  .tool-btn .label {
    font-size: 9px;
  }
}

/* 头部操作按钮组 */
.header-actions {
  display: flex;
  gap: 8px;
}

/* 连接信息栏 */
.connection-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 16px;
  background-color: #12122a;
  border-bottom: 1px solid #2a2a4a;
  font-size: 12px;
}

.conn-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.conn-mode {
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
  font-size: 11px;
}

.conn-mode.webrtc {
  background-color: #22c55e;
  color: #000;
}

.conn-mode.h264 {
  background-color: #3b82f6;
  color: #fff;
}

.conn-mode.mjpeg {
  background-color: #f59e0b;
  color: #000;
}

.conn-stats {
  color: #888;
}

.conn-latency {
  padding: 2px 6px;
  border-radius: 4px;
}

.conn-latency.good {
  background-color: rgba(34, 197, 94, 0.2);
  color: #22c55e;
}

.conn-latency.medium {
  background-color: rgba(245, 158, 11, 0.2);
  color: #f59e0b;
}

.conn-latency.poor {
  background-color: rgba(239, 68, 68, 0.2);
  color: #ef4444;
}

.conn-quality {
  display: flex;
  align-items: center;
  gap: 8px;
}

.quality-label {
  color: #888;
}

.quality-select {
  padding: 4px 8px;
  border-radius: 4px;
  border: 1px solid #444;
  background-color: #1a1a2e;
  color: #eee;
  font-size: 12px;
  cursor: pointer;
}

.quality-select:focus {
  outline: none;
  border-color: #3b82f6;
}

/* 设置面板 */
.settings-panel {
  position: fixed;
  right: 20px;
  top: 60px;
  width: 360px;
  max-height: calc(100vh - 100px);
  overflow-y: auto;
  background-color: #16213e;
  border-radius: 12px;
  border: 1px solid #1f3460;
  padding: 16px;
  z-index: 100;
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  font-weight: 600;
  font-size: 16px;
}

.settings-section {
  margin-bottom: 20px;
}

.settings-section h4 {
  margin: 0 0 12px 0;
  font-size: 13px;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.settings-info {
  background-color: #0f0f23;
  border-radius: 8px;
  padding: 12px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px solid #1a1a3a;
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  color: #888;
  font-size: 13px;
}

.info-value {
  font-size: 13px;
  font-weight: 500;
}

.info-value.webrtc {
  color: #22c55e;
}

.info-value.h264 {
  color: #3b82f6;
}

.info-value.mjpeg {
  color: #f59e0b;
}

.info-value.connected {
  color: #22c55e;
}

.info-value.connecting {
  color: #f59e0b;
}

.info-value.disconnected,
.info-value.failed {
  color: #ef4444;
}

.info-value.good {
  color: #22c55e;
}

.info-value.medium {
  color: #f59e0b;
}

.info-value.poor,
.info-value.warning {
  color: #ef4444;
}

/* 画质选项 */
.quality-options {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.quality-option {
  cursor: pointer;
}

.quality-option input {
  display: none;
}

.quality-card {
  padding: 12px;
  border-radius: 8px;
  border: 2px solid #2a2a4a;
  background-color: #0f0f23;
  transition: all 0.2s;
}

.quality-card:hover {
  border-color: #3b82f6;
}

.quality-card.active {
  border-color: #3b82f6;
  background-color: rgba(59, 130, 246, 0.1);
}

.quality-name {
  display: block;
  font-weight: 600;
  margin-bottom: 4px;
}

.quality-desc {
  display: block;
  font-size: 11px;
  color: #888;
}

/* 设置选项 */
.settings-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.option-row {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 8px 12px;
  border-radius: 6px;
  background-color: #0f0f23;
}

.option-row:hover {
  background-color: #1a1a3a;
}

.option-row input[type="checkbox"] {
  width: 18px;
  height: 18px;
  accent-color: #3b82f6;
}

.settings-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #2a2a4a;
}

.settings-actions .btn {
  flex: 1;
  font-size: 13px;
}

/* 响应式 - 设置面板 */
@media (max-width: 600px) {
  .settings-panel,
  .clipboard-panel {
    right: 10px;
    left: 10px;
    width: auto;
  }

  .quality-options {
    grid-template-columns: 1fr;
  }

  .connection-bar {
    flex-direction: column;
    gap: 8px;
    padding: 8px 12px;
  }

  .conn-info {
    flex-wrap: wrap;
    justify-content: center;
  }
}
</style>
