package service

import (
	"sync"

	"shushu-remote-control/internal/model"
)

// ControllerManager 控制端管理器
type ControllerManager struct {
	controllers map[string]*model.Controller
	mutex       sync.RWMutex
}

// NewControllerManager 创建控制端管理器
func NewControllerManager() *ControllerManager {
	return &ControllerManager{
		controllers: make(map[string]*model.Controller),
	}
}

// Register 注册控制端
func (cm *ControllerManager) Register(controller *model.Controller) {
	cm.mutex.Lock()
	defer cm.mutex.Unlock()

	cm.controllers[controller.ID] = controller
}

// Unregister 注销控制端
func (cm *ControllerManager) Unregister(controllerID string) {
	cm.mutex.Lock()
	defer cm.mutex.Unlock()

	delete(cm.controllers, controllerID)
}

// Get 获取控制端
func (cm *ControllerManager) Get(controllerID string) *model.Controller {
	cm.mutex.RLock()
	defer cm.mutex.RUnlock()

	return cm.controllers[controllerID]
}

// BroadcastDeviceOnline 广播设备上线
func (cm *ControllerManager) BroadcastDeviceOnline(deviceID, deviceName string) {
	cm.mutex.RLock()
	defer cm.mutex.RUnlock()

	msg := map[string]interface{}{
		"type":       "device.online",
		"deviceId":   deviceID,
		"deviceName": deviceName,
	}

	for _, controller := range cm.controllers {
		controller.SendJSON(msg)
	}
}

// BroadcastDeviceOffline 广播设备离线
func (cm *ControllerManager) BroadcastDeviceOffline(deviceID string) {
	cm.mutex.RLock()
	defer cm.mutex.RUnlock()

	msg := map[string]interface{}{
		"type":     "device.offline",
		"deviceId": deviceID,
	}

	for _, controller := range cm.controllers {
		controller.SendJSON(msg)
	}
}
