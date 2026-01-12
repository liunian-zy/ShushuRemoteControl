export interface DeviceInfo {
  deviceId: string
  deviceName: string
  screenWidth: number
  screenHeight: number
  online: boolean
}

type MessageHandler = (data: any) => void
type BinaryHandler = (data: ArrayBuffer) => void

export class WebSocketService {
  private ws: WebSocket | null = null
  private handlers: Map<string, MessageHandler[]> = new Map()
  private binaryHandler: BinaryHandler | null = null
  private reconnectTimer: number | null = null
  private url: string = ''
  private shouldReconnect: boolean = true
  private reconnectAttempts: number = 0
  private maxReconnectAttempts: number = 50
  private reconnectDelay: number = 2000

  on(event: string, handler: MessageHandler) {
    if (!this.handlers.has(event)) {
      this.handlers.set(event, [])
    }
    this.handlers.get(event)!.push(handler)
  }

  onBinary(handler: BinaryHandler) {
    this.binaryHandler = handler
  }

  private emit(event: string, data?: any) {
    const handlers = this.handlers.get(event)
    if (handlers) {
      handlers.forEach(h => h(data))
    }
  }

  connect(path: string) {
    // 构建WebSocket URL
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    this.url = `${protocol}//${host}${path}`
    this.shouldReconnect = true
    this.reconnectAttempts = 0

    this.doConnect()
  }

  private doConnect() {
    try {
      this.ws = new WebSocket(this.url)
      this.ws.binaryType = 'arraybuffer'

      this.ws.onopen = () => {
        console.log('WebSocket connected')
        this.reconnectAttempts = 0
        this.emit('open')
      }

      this.ws.onclose = () => {
        console.log('WebSocket closed')
        this.emit('close')
        this.scheduleReconnect()
      }

      this.ws.onerror = (error) => {
        console.error('WebSocket error:', error)
      }

      this.ws.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) {
          // 二进制消息（屏幕帧）
          this.binaryHandler?.(event.data)
        } else {
          // JSON消息
          try {
            const msg = JSON.parse(event.data)
            this.emit(msg.type, msg)
          } catch (e) {
            console.error('Failed to parse message:', e)
          }
        }
      }
    } catch (e) {
      console.error('Failed to connect:', e)
      this.scheduleReconnect()
    }
  }

  private scheduleReconnect() {
    if (!this.shouldReconnect) return
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.log('Max reconnect attempts reached')
      this.emit('error', { message: '无法连接到服务器，请刷新页面重试' })
      return
    }

    this.reconnectAttempts++
    console.log(`Reconnecting in ${this.reconnectDelay}ms (attempt ${this.reconnectAttempts})`)

    this.reconnectTimer = window.setTimeout(() => {
      this.doConnect()
    }, this.reconnectDelay)
  }

  send(data: object) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data))
    }
  }

  disconnect() {
    this.shouldReconnect = false
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.ws?.close()
    this.ws = null
  }

  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }
}
