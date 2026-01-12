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
      <button class="btn btn-icon" @click="toggleClipboard" title="剪贴板">
        📋
      </button>
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
      <canvas
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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { WebSocketService } from '../services/websocket'

const props = defineProps<{
  deviceId: string
}>()

const router = useRouter()

const status = ref<'connecting' | 'connected' | 'error'>('connecting')
const errorMessage = ref('')
const deviceName = ref('')
const screenWidth = ref(1920)
const screenHeight = ref(1080)

const screenContainer = ref<HTMLDivElement>()
const screenCanvas = ref<HTMLCanvasElement>()
let ctx: CanvasRenderingContext2D | null = null

const showClipboard = ref(false)
const clipboardText = ref('')
const deviceClipboard = ref('')

let ws: WebSocketService | null = null
let isMouseDown = false
let mouseDownPos = { x: 0, y: 0 }
let mouseDownTime = 0
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
  })

  ws.on('control.granted', (data) => {
    status.value = 'connected'
    deviceName.value = data.deviceName || props.deviceId
    screenWidth.value = data.screenWidth
    screenHeight.value = data.screenHeight

    nextTick(() => {
      initCanvas()
    })
  })

  ws.on('error', (data) => {
    status.value = 'error'
    errorMessage.value = data.message || '连接失败'
  })

  ws.on('clipboard.update', (data) => {
    deviceClipboard.value = data.text
  })

  ws.onBinary((data: ArrayBuffer) => {
    renderFrame(data)
  })

  ws.connect('/ws/controller')

  // 监听键盘事件
  window.addEventListener('keydown', onKeyDown)
})

onUnmounted(() => {
  ws?.send({ type: 'control.release' })
  ws?.disconnect()
  window.removeEventListener('keydown', onKeyDown)
})

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
  if (!screenCanvas.value) return { x: 0, y: 0 }

  const rect = screenCanvas.value.getBoundingClientRect()
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
  // 如果剪贴板面板打开且焦点在输入框，不拦截
  if (showClipboard.value) return

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
</style>
