package service

import (
	"sync"
	"time"

	"github.com/google/uuid"

	"shushu-remote-control/internal/model"
)

// SessionManager 会话管理器
type SessionManager struct {
	sessions           map[string]*model.Session // sessionID -> session
	deviceSessions     map[string]string         // deviceID -> sessionID
	controllerSessions map[string]string         // controllerID -> sessionID
	mutex              sync.RWMutex
}

// NewSessionManager 创建会话管理器
func NewSessionManager() *SessionManager {
	return &SessionManager{
		sessions:           make(map[string]*model.Session),
		deviceSessions:     make(map[string]string),
		controllerSessions: make(map[string]string),
	}
}

// Create 创建控制会话
func (sm *SessionManager) Create(device *model.Device, controller *model.Controller) *model.Session {
	sm.mutex.Lock()
	defer sm.mutex.Unlock()

	// 检查设备是否已被控制
	if existingSessionID, ok := sm.deviceSessions[device.ID]; ok {
		if session, ok := sm.sessions[existingSessionID]; ok && session.Active {
			return nil // 设备已被控制
		}
	}

	sessionID := uuid.New().String()
	session := &model.Session{
		ID:           sessionID,
		DeviceID:     device.ID,
		ControllerID: controller.ID,
		Device:       device,
		Controller:   controller,
		CreatedAt:    time.Now(),
		Active:       true,
	}

	sm.sessions[sessionID] = session
	sm.deviceSessions[device.ID] = sessionID
	sm.controllerSessions[controller.ID] = sessionID

	controller.SessionID = sessionID
	controller.DeviceID = device.ID

	return session
}

// Get 获取会话
func (sm *SessionManager) Get(sessionID string) *model.Session {
	sm.mutex.RLock()
	defer sm.mutex.RUnlock()

	return sm.sessions[sessionID]
}

// GetByDevice 通过设备ID获取会话
func (sm *SessionManager) GetByDevice(deviceID string) *model.Session {
	sm.mutex.RLock()
	defer sm.mutex.RUnlock()

	if sessionID, ok := sm.deviceSessions[deviceID]; ok {
		if session, ok := sm.sessions[sessionID]; ok && session.Active {
			return session
		}
	}
	return nil
}

// GetByController 通过控制端ID获取会话
func (sm *SessionManager) GetByController(controllerID string) *model.Session {
	sm.mutex.RLock()
	defer sm.mutex.RUnlock()

	if sessionID, ok := sm.controllerSessions[controllerID]; ok {
		if session, ok := sm.sessions[sessionID]; ok && session.Active {
			return session
		}
	}
	return nil
}

// Close 关闭会话
func (sm *SessionManager) Close(sessionID string) {
	sm.mutex.Lock()
	defer sm.mutex.Unlock()

	if session, ok := sm.sessions[sessionID]; ok {
		session.Active = false
		delete(sm.deviceSessions, session.DeviceID)
		delete(sm.controllerSessions, session.ControllerID)

		if session.Controller != nil {
			session.Controller.SessionID = ""
			session.Controller.DeviceID = ""
		}
	}
}

// CloseByDevice 通过设备ID关闭会话
func (sm *SessionManager) CloseByDevice(deviceID string) {
	sm.mutex.Lock()
	defer sm.mutex.Unlock()

	if sessionID, ok := sm.deviceSessions[deviceID]; ok {
		if session, ok := sm.sessions[sessionID]; ok {
			session.Active = false
			delete(sm.deviceSessions, session.DeviceID)
			delete(sm.controllerSessions, session.ControllerID)

			if session.Controller != nil {
				session.Controller.SessionID = ""
				session.Controller.DeviceID = ""
			}
		}
	}
}

// CloseByController 通过控制端ID关闭会话
func (sm *SessionManager) CloseByController(controllerID string) {
	sm.mutex.Lock()
	defer sm.mutex.Unlock()

	if sessionID, ok := sm.controllerSessions[controllerID]; ok {
		if session, ok := sm.sessions[sessionID]; ok {
			session.Active = false
			delete(sm.deviceSessions, session.DeviceID)
			delete(sm.controllerSessions, session.ControllerID)

			if session.Controller != nil {
				session.Controller.SessionID = ""
				session.Controller.DeviceID = ""
			}
		}
	}
}
