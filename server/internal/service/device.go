package service

import (
	"log"
	"sync"
	"time"

	"shushu-remote-control/internal/model"
	"shushu-remote-control/internal/protocol"
	"shushu-remote-control/internal/store"
)

// DeviceManager 设备管理器
type DeviceManager struct {
	devices map[string]*model.Device
	mutex   sync.RWMutex
	store   *store.DeviceStore
}

// NewDeviceManager 创建设备管理器
func NewDeviceManager(deviceStore *store.DeviceStore) *DeviceManager {
	return &DeviceManager{
		devices: make(map[string]*model.Device),
		store:   deviceStore,
	}
}

// Register 注册设备
func (dm *DeviceManager) Register(device *model.Device) {
	dm.mutex.Lock()
	defer dm.mutex.Unlock()

	device.Online = true
	device.LastSeen = time.Now()
	dm.devices[device.ID] = device

	if dm.store != nil {
		if err := dm.store.UpsertDevice(device); err != nil {
			log.Printf("设备信息写入数据库失败: %v", err)
		}
		if err := dm.store.SyncExternalDeviceID(device.ID); err != nil {
			log.Printf("同步外部设备ID失败: %v", err)
		}
	}
}

// Unregister 注销设备
func (dm *DeviceManager) Unregister(deviceID string) {
	dm.mutex.Lock()
	defer dm.mutex.Unlock()

	if device, ok := dm.devices[deviceID]; ok {
		device.Online = false
		device.Conn = nil
	}

	if dm.store != nil {
		if err := dm.store.SetOnline(deviceID, false); err != nil {
			log.Printf("更新设备离线状态失败: %v", err)
		}
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

	if dm.store != nil {
		if err := dm.store.SetOnline(deviceID, true); err != nil {
			log.Printf("更新设备心跳失败: %v", err)
		}
	}
}
