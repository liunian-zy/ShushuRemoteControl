<template>
  <div class="container">
    <div class="header">
      <h1>商擎动力</h1>
      <div class="connection-status">
        <span class="status-dot" :class="{ online: connected }"></span>
        {{ connected ? '已连接' : '未连接' }}
      </div>
    </div>

    <div v-if="!connected" class="loading">
      <div class="spinner"></div>
    </div>

    <div v-else-if="devices.length === 0" class="empty-state">
      <p>暂无在线设备</p>
      <p class="hint">请确保设备已启动并连接到服务器</p>
    </div>

    <div v-else class="device-grid">
      <div
        v-for="device in devices"
        :key="device.deviceId"
        class="card device-card"
        :class="{ offline: !device.online }"
      >
        <div class="device-info">
          <div class="device-name">
            <span class="status-dot" :class="{ online: device.online }"></span>
            {{ device.deviceName }}
          </div>
          <div class="device-meta">
            <span>ID: {{ device.deviceId }}</span>
            <span>分辨率: {{ device.screenWidth }}x{{ device.screenHeight }}</span>
          </div>
        </div>
        <button
          class="btn btn-primary"
          :disabled="!device.online"
          @click="connectDevice(device.deviceId)"
        >
          {{ device.online ? '远程控制' : '离线' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { WebSocketService, type DeviceInfo } from '../services/websocket'

const router = useRouter()
const connected = ref(false)
const devices = ref<DeviceInfo[]>([])

let ws: WebSocketService | null = null

onMounted(() => {
  ws = new WebSocketService()

  ws.on('open', () => {
    connected.value = true
  })

  ws.on('close', () => {
    connected.value = false
  })

  ws.on('device.list', (data: { devices: DeviceInfo[] }) => {
    devices.value = data.devices
  })

  ws.on('device.online', (data: DeviceInfo) => {
    const index = devices.value.findIndex(d => d.deviceId === data.deviceId)
    if (index >= 0) {
      devices.value[index].online = true
    } else {
      devices.value.push({ ...data, online: true })
    }
  })

  ws.on('device.offline', (data: { deviceId: string }) => {
    const index = devices.value.findIndex(d => d.deviceId === data.deviceId)
    if (index >= 0) {
      devices.value[index].online = false
    }
  })

  ws.connect('/ws/controller')
})

onUnmounted(() => {
  ws?.disconnect()
})

function connectDevice(deviceId: string) {
  router.push(`/control/${deviceId}`)
}
</script>

<style scoped>
.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: #888;
}

.empty-state .hint {
  margin-top: 10px;
  font-size: 14px;
}

.device-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
  gap: 16px;
}

.device-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.device-card.offline {
  opacity: 0.6;
}

.device-info {
  flex: 1;
}

.device-name {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
}

.device-meta {
  font-size: 13px;
  color: #888;
  display: flex;
  gap: 16px;
}

.connection-status {
  display: flex;
  align-items: center;
  font-size: 14px;
  color: #888;
}
</style>
