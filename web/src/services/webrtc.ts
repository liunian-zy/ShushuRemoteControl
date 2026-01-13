/**
 * WebRTC 客户端 - 用于接收设备端的屏幕共享流
 */

export interface IceServerConfig {
  urls: string | string[]
  username?: string
  credential?: string
}

export interface WebRTCStats {
  bytesReceived: number
  packetsReceived: number
  packetsLost: number
  jitter: number
  frameWidth: number
  frameHeight: number
  framesPerSecond: number
}

export class WebRTCClient {
  private pc: RTCPeerConnection | null = null
  private remoteStream: MediaStream | null = null

  // ICE 服务器配置
  private readonly config: RTCConfiguration = {
    iceServers: [
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: 'stun:stun1.l.google.com:19302' },
      { urls: 'stun:stun2.l.google.com:19302' },
      { urls: 'stun:stun.stunprotocol.org:3478' }
    ],
    iceCandidatePoolSize: 10,
    bundlePolicy: 'max-bundle',
    rtcpMuxPolicy: 'require'
  }

  // 回调
  onRemoteStream: ((stream: MediaStream) => void) | null = null
  onIceCandidate: ((candidate: RTCIceCandidate) => void) | null = null
  onConnectionStateChange: ((state: RTCPeerConnectionState) => void) | null = null
  onIceConnectionStateChange: ((state: RTCIceConnectionState) => void) | null = null
  onError: ((error: string) => void) | null = null

  /**
   * 添加自定义 TURN 服务器
   */
  addTurnServer(url: string, username: string, credential: string) {
    this.config.iceServers?.push({
      urls: url,
      username,
      credential
    })
  }

  /**
   * 初始化 PeerConnection
   */
  async initialize(): Promise<boolean> {
    try {
      this.pc = new RTCPeerConnection(this.config)

      // 接收远程流
      this.pc.ontrack = (event) => {
        console.log('Remote track received:', event.track.kind)
        if (event.streams && event.streams[0]) {
          this.remoteStream = event.streams[0]
          this.onRemoteStream?.(this.remoteStream)
        }
      }

      // ICE 候选
      this.pc.onicecandidate = (event) => {
        if (event.candidate) {
          console.log('ICE candidate:', event.candidate.candidate.substring(0, 50))
          this.onIceCandidate?.(event.candidate)
        }
      }

      // 连接状态变化
      this.pc.onconnectionstatechange = () => {
        const state = this.pc?.connectionState
        console.log('Connection state:', state)
        if (state) {
          this.onConnectionStateChange?.(state)
        }

        if (state === 'failed') {
          this.onError?.('WebRTC connection failed')
        }
      }

      // ICE 连接状态变化
      this.pc.oniceconnectionstatechange = () => {
        const state = this.pc?.iceConnectionState
        console.log('ICE connection state:', state)
        if (state) {
          this.onIceConnectionStateChange?.(state)
        }
      }

      // ICE 收集状态
      this.pc.onicegatheringstatechange = () => {
        console.log('ICE gathering state:', this.pc?.iceGatheringState)
      }

      console.log('WebRTC client initialized')
      return true
    } catch (e) {
      console.error('Failed to initialize WebRTC:', e)
      this.onError?.(`Failed to initialize WebRTC: ${e}`)
      return false
    }
  }

  /**
   * 处理来自设备的 Offer，生成 Answer
   */
  async handleOffer(sdp: RTCSessionDescriptionInit): Promise<RTCSessionDescriptionInit | null> {
    if (!this.pc) {
      console.error('PeerConnection not initialized')
      return null
    }

    try {
      console.log('Setting remote description (offer)')
      await this.pc.setRemoteDescription(new RTCSessionDescription(sdp))

      console.log('Creating answer')
      const answer = await this.pc.createAnswer()

      console.log('Setting local description (answer)')
      await this.pc.setLocalDescription(answer)

      return answer
    } catch (e) {
      console.error('Failed to handle offer:', e)
      this.onError?.(`Failed to handle offer: ${e}`)
      return null
    }
  }

  /**
   * 添加 ICE 候选
   */
  async addIceCandidate(candidate: RTCIceCandidateInit): Promise<boolean> {
    if (!this.pc) {
      console.error('PeerConnection not initialized')
      return false
    }

    try {
      await this.pc.addIceCandidate(new RTCIceCandidate(candidate))
      console.log('ICE candidate added')
      return true
    } catch (e) {
      console.error('Failed to add ICE candidate:', e)
      return false
    }
  }

  /**
   * 获取连接统计信息
   */
  async getStats(): Promise<WebRTCStats | null> {
    if (!this.pc) return null

    try {
      const stats = await this.pc.getStats()
      let result: WebRTCStats = {
        bytesReceived: 0,
        packetsReceived: 0,
        packetsLost: 0,
        jitter: 0,
        frameWidth: 0,
        frameHeight: 0,
        framesPerSecond: 0
      }

      stats.forEach((report) => {
        if (report.type === 'inbound-rtp' && report.kind === 'video') {
          result.bytesReceived = report.bytesReceived || 0
          result.packetsReceived = report.packetsReceived || 0
          result.packetsLost = report.packetsLost || 0
          result.jitter = report.jitter || 0
          result.frameWidth = report.frameWidth || 0
          result.frameHeight = report.frameHeight || 0
          result.framesPerSecond = report.framesPerSecond || 0
        }
      })

      return result
    } catch (e) {
      console.error('Failed to get stats:', e)
      return null
    }
  }

  /**
   * 获取当前连接状态
   */
  getConnectionState(): RTCPeerConnectionState | null {
    return this.pc?.connectionState || null
  }

  /**
   * 获取 ICE 连接状态
   */
  getIceConnectionState(): RTCIceConnectionState | null {
    return this.pc?.iceConnectionState || null
  }

  /**
   * 获取远程流
   */
  getRemoteStream(): MediaStream | null {
    return this.remoteStream
  }

  /**
   * 关闭连接
   */
  close() {
    console.log('Closing WebRTC connection')

    if (this.remoteStream) {
      this.remoteStream.getTracks().forEach(track => track.stop())
      this.remoteStream = null
    }

    if (this.pc) {
      this.pc.close()
      this.pc = null
    }
  }
}

/**
 * WebRTC 信令消息类型
 */
export const WebRTCMessageTypes = {
  OFFER: 'webrtc.offer',
  ANSWER: 'webrtc.answer',
  ICE: 'webrtc.ice',
  READY: 'webrtc.ready'
} as const

/**
 * 创建 WebRTC Offer 消息
 */
export function createOfferMessage(sdp: RTCSessionDescriptionInit, targetId: string) {
  return {
    type: WebRTCMessageTypes.OFFER,
    targetId,
    sdp: {
      type: sdp.type,
      sdp: sdp.sdp
    }
  }
}

/**
 * 创建 WebRTC Answer 消息
 */
export function createAnswerMessage(sdp: RTCSessionDescriptionInit, targetId: string) {
  return {
    type: WebRTCMessageTypes.ANSWER,
    targetId,
    sdp: {
      type: sdp.type,
      sdp: sdp.sdp
    }
  }
}

/**
 * 创建 ICE Candidate 消息
 */
export function createIceCandidateMessage(candidate: RTCIceCandidate, targetId: string) {
  return {
    type: WebRTCMessageTypes.ICE,
    targetId,
    candidate: {
      sdpMid: candidate.sdpMid,
      sdpMLineIndex: candidate.sdpMLineIndex,
      candidate: candidate.candidate
    }
  }
}

/**
 * 创建 WebRTC Ready 消息
 */
export function createReadyMessage(targetId: string) {
  return {
    type: WebRTCMessageTypes.READY,
    targetId
  }
}
