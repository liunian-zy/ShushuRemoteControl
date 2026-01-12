package service

import (
	"sync"
	"time"

	"shushu-remote-control/internal/model"
	"shushu-remote-control/internal/protocol"
)

// DeviceManager 设备管理器
type DeviceManager struct {
	devices map[string]*model.Device
	mutex   sync.RWMutex
}

// NewDeviceManager 创建设备管理器
func NewDeviceManager() *DeviceManager {
	return &DeviceManager{
		devices: make(map[string]*model.Device),
	}
}

// Register 注册设备
func (dm *DeviceManager) Register(device *model.Device) {
	dm.mutex.Lock()
	defer dm.mutex.Unlock()

	device.Online = true
	device.LastSeen = time.Now()
	dm.devices[device.ID] = device
}

// Unregister 注销设备
func (dm *DeviceManager) Unregister(deviceID string) {
	dm.mutex.Lock()
	defer dm.mutex.Unlock()

	if device, ok := dm.devices[deviceID]; ok {
		device.Online = false
		device.Conn = nil
	}
}

// Get 获取设备
func (dm *DeviceManager) Get(deviceID string) *model.Device {
	dm.mutex.RLock()
	defer dm.mutex.RUnlock()

	return dm.devices[deviceID]
}

// GetOnline 获取在线设备
func (dm *DeviceManager) GetOnline(deviceID string) *model.Device {
	dm.mutex.RLock()
	defer dm.mutex.RUnlock()

	if device, ok := dm.devices[deviceID]; ok && device.Online {
		return device
	}
	return nil
}

// List 获取所有设备列表
func (dm *DeviceManager) List() []protocol.DeviceInfo {
	dm.mutex.RLock()
	defer dm.mutex.RUnlock()

	list := make([]protocol.DeviceInfo, 0, len(dm.devices))
	for _, device := range dm.devices {
		list = append(list, protocol.DeviceInfo{
			DeviceID:     device.ID,
			DeviceName:   device.Name,
			ScreenWidth:  device.ScreenWidth,
			ScreenHeight: device.ScreenHeight,
			Online:       device.Online,
		})
	}
	return list
}

// UpdateHeartbeat 更新心跳
func (dm *DeviceManager) UpdateHeartbeat(deviceID string) {
	dm.mutex.Lock()
	defer dm.mutex.Unlock()

	if device, ok := dm.devices[deviceID]; ok {
		device.LastSeen = time.Now()
	}
}
