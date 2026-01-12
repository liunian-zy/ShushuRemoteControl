package handler

import (
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"

	"shushu-remote-control/internal/model"
	"shushu-remote-control/internal/protocol"
	"shushu-remote-control/internal/service"
)

const (
	writeWait      = 10 * time.Second    // 写超时
	pongWait       = 60 * time.Second    // Pong等待时间
	pingPeriod     = (pongWait * 9) / 10 // Ping间隔（54秒）
	maxMessageSize = 1024 * 1024         // 最大消息大小 1MB
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		return true // 开发阶段允许所有来源
	},
	ReadBufferSize:  1024 * 64,  // 64KB 读缓冲区
	WriteBufferSize: 1024 * 256, // 256KB 写缓冲区（视频帧较大）
}

// WebSocketHandler WebSocket处理器
type WebSocketHandler struct {
	deviceMgr     *service.DeviceManager
	controllerMgr *service.ControllerManager
	sessionMgr    *service.SessionManager
	authToken     string // 简单的预共享密钥认证
}

// NewWebSocketHandler 创建WebSocket处理器
func NewWebSocketHandler(authToken string) *WebSocketHandler {
	return &WebSocketHandler{
		deviceMgr:     service.NewDeviceManager(),
		controllerMgr: service.NewControllerManager(),
		sessionMgr:    service.NewSessionManager(),
		authToken:     authToken,
	}
}

// HandleDevice 处理设备连接
func (h *WebSocketHandler) HandleDevice(c *gin.Context) {
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		log.Printf("设备WebSocket升级失败: %v", err)
		return
	}

	// 配置连接参数
	conn.SetReadLimit(maxMessageSize)
	conn.SetReadDeadline(time.Now().Add(pongWait))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	log.Println("新设备连接")

	// 等待设备注册消息
	_, message, err := conn.ReadMessage()
	if err != nil {
		log.Printf("读取设备注册消息失败: %v", err)
		conn.Close()
		return
	}

	var registerMsg protocol.DeviceRegisterMessage
	if err := json.Unmarshal(message, &registerMsg); err != nil {
		log.Printf("解析设备注册消息失败: %v", err)
		conn.Close()
		return
	}

	// 验证token
	if registerMsg.Token != h.authToken {
		log.Printf("设备认证失败: %s", registerMsg.DeviceID)
		conn.WriteJSON(protocol.ErrorMessage{
			Type:    protocol.TypeError,
			Code:    "AUTH_FAILED",
			Message: "认证失败",
		})
		conn.Close()
		return
	}

	device := &model.Device{
		ID:           registerMsg.DeviceID,
		Name:         registerMsg.DeviceName,
		ScreenWidth:  registerMsg.ScreenWidth,
		ScreenHeight: registerMsg.ScreenHeight,
		Token:        registerMsg.Token,
		Conn:         conn,
	}

	h.deviceMgr.Register(device)
	h.controllerMgr.BroadcastDeviceOnline(device.ID, device.Name)

	log.Printf("设备注册成功: %s (%s)", device.Name, device.ID)

	// 发送注册成功响应
	conn.WriteJSON(map[string]interface{}{
		"type":    "device.registered",
		"success": true,
	})

	// 处理设备消息
	h.handleDeviceMessages(device)
}

// handleDeviceMessages 处理设备消息循环
func (h *WebSocketHandler) handleDeviceMessages(device *model.Device) {
	defer func() {
		device.Conn.Close()
		h.deviceMgr.Unregister(device.ID)
		h.sessionMgr.CloseByDevice(device.ID)
		h.controllerMgr.BroadcastDeviceOffline(device.ID)
		log.Printf("设备断开: %s", device.ID)
	}()

	for {
		messageType, message, err := device.Conn.ReadMessage()
		if err != nil {
			log.Printf("读取设备消息失败: %v", err)
			return
		}

		// 二进制消息 - 屏幕帧
		if messageType == websocket.BinaryMessage {
			h.handleScreenFrame(device, message)
			continue
		}

		// JSON消息
		var baseMsg protocol.BaseMessage
		if err := json.Unmarshal(message, &baseMsg); err != nil {
			log.Printf("解析消息失败: %v", err)
			continue
		}

		switch baseMsg.Type {
		case protocol.TypeDeviceHeartbeat:
			h.deviceMgr.UpdateHeartbeat(device.ID)

		case protocol.TypeClipboardUpdate:
			var clipMsg protocol.ClipboardMessage
			json.Unmarshal(message, &clipMsg)
			h.handleClipboardFromDevice(device, clipMsg)
		}
	}
}

// handleScreenFrame 处理屏幕帧，转发给控制端
func (h *WebSocketHandler) handleScreenFrame(device *model.Device, frame []byte) {
	session := h.sessionMgr.GetByDevice(device.ID)
	if session == nil || session.Controller == nil {
		return
	}

	// 直接转发给控制端
	session.Controller.SendBinary(frame)
}

// handleClipboardFromDevice 处理来自设备的剪贴板更新
func (h *WebSocketHandler) handleClipboardFromDevice(device *model.Device, msg protocol.ClipboardMessage) {
	session := h.sessionMgr.GetByDevice(device.ID)
	if session == nil || session.Controller == nil {
		return
	}

	session.Controller.SendJSON(protocol.ClipboardMessage{
		Type: protocol.TypeClipboardUpdate,
		Text: msg.Text,
	})
}

// HandleController 处理控制端连接
func (h *WebSocketHandler) HandleController(c *gin.Context) {
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		log.Printf("控制端WebSocket升级失败: %v", err)
		return
	}

	// 配置连接参数
	conn.SetReadLimit(maxMessageSize)
	conn.SetReadDeadline(time.Now().Add(pongWait))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	controllerID := uuid.New().String()
	controller := &model.Controller{
		ID:   controllerID,
		Conn: conn,
	}

	h.controllerMgr.Register(controller)
	log.Printf("控制端连接: %s", controllerID)

	// 发送设备列表
	conn.WriteJSON(protocol.DeviceListMessage{
		Type:    protocol.TypeDeviceList,
		Devices: h.deviceMgr.List(),
	})

	// 处理控制端消息
	h.handleControllerMessages(controller)
}

// handleControllerMessages 处理控制端消息循环
func (h *WebSocketHandler) handleControllerMessages(controller *model.Controller) {
	defer func() {
		controller.Conn.Close()
		h.sessionMgr.CloseByController(controller.ID)
		h.controllerMgr.Unregister(controller.ID)
		log.Printf("控制端断开: %s", controller.ID)
	}()

	for {
		_, message, err := controller.Conn.ReadMessage()
		if err != nil {
			log.Printf("读取控制端消息失败: %v", err)
			return
		}

		var baseMsg protocol.BaseMessage
		if err := json.Unmarshal(message, &baseMsg); err != nil {
			log.Printf("解析消息失败: %v", err)
			continue
		}

		switch baseMsg.Type {
		case protocol.TypeControlRequest:
			var reqMsg protocol.ControlRequestMessage
			json.Unmarshal(message, &reqMsg)
			h.handleControlRequest(controller, reqMsg)

		case protocol.TypeControlRelease:
			h.sessionMgr.CloseByController(controller.ID)

		case protocol.TypeInputTouch:
			h.forwardToDevice(controller, message)

		case protocol.TypeInputKey:
			var keyMsg protocol.KeyMessage
			json.Unmarshal(message, &keyMsg)
			h.handleKeyInput(controller, keyMsg)

		case protocol.TypeInputText:
			var textMsg protocol.TextMessage
			json.Unmarshal(message, &textMsg)
			h.handleTextInput(controller, textMsg)

		case protocol.TypeClipboardSet:
			var clipMsg protocol.ClipboardMessage
			json.Unmarshal(message, &clipMsg)
			h.handleClipboardFromController(controller, clipMsg)

		case protocol.TypeStreamStart, protocol.TypeStreamStop:
			h.forwardToDevice(controller, message)
		}
	}
}

// handleControlRequest 处理控制请求
func (h *WebSocketHandler) handleControlRequest(controller *model.Controller, msg protocol.ControlRequestMessage) {
	device := h.deviceMgr.GetOnline(msg.DeviceID)
	if device == nil {
		controller.SendJSON(protocol.ErrorMessage{
			Type:    protocol.TypeError,
			Code:    "DEVICE_OFFLINE",
			Message: "设备不在线",
		})
		return
	}

	session := h.sessionMgr.Create(device, controller)
	if session == nil {
		controller.SendJSON(protocol.ErrorMessage{
			Type:    protocol.TypeError,
			Code:    "DEVICE_BUSY",
			Message: "设备正在被其他人控制",
		})
		return
	}

	// 通知控制端
	controller.SendJSON(protocol.ControlGrantedMessage{
		Type:         protocol.TypeControlGranted,
		DeviceID:     device.ID,
		SessionID:    session.ID,
		ScreenWidth:  device.ScreenWidth,
		ScreenHeight: device.ScreenHeight,
	})

	// 通知设备开始推流
	device.SendJSON(protocol.StreamControlMessage{
		Type:    protocol.TypeStreamStart,
		Quality: 80,
		MaxFPS:  30,
	})

	log.Printf("控制会话建立: %s -> %s", controller.ID, device.ID)
}

// handleTouchInput 处理触摸输入
func (h *WebSocketHandler) handleTouchInput(controller *model.Controller, msg protocol.TouchMessage) {
	session := h.sessionMgr.GetByController(controller.ID)
	if session == nil || session.Device == nil {
		return
	}

	// 转发给设备
	session.Device.SendJSON(msg)
}

// handleKeyInput 处理按键输入
func (h *WebSocketHandler) handleKeyInput(controller *model.Controller, msg protocol.KeyMessage) {
	session := h.sessionMgr.GetByController(controller.ID)
	if session == nil || session.Device == nil {
		return
	}

	session.Device.SendJSON(msg)
}

// handleTextInput 处理文本输入
func (h *WebSocketHandler) handleTextInput(controller *model.Controller, msg protocol.TextMessage) {
	session := h.sessionMgr.GetByController(controller.ID)
	if session == nil || session.Device == nil {
		return
	}

	session.Device.SendJSON(msg)
}

// handleClipboardFromController 处理来自控制端的剪贴板设置
func (h *WebSocketHandler) handleClipboardFromController(controller *model.Controller, msg protocol.ClipboardMessage) {
	session := h.sessionMgr.GetByController(controller.ID)
	if session == nil || session.Device == nil {
		return
	}

	session.Device.SendJSON(protocol.ClipboardMessage{
		Type: protocol.TypeClipboardSet,
		Text: msg.Text,
	})
}

// forwardToDevice 转发消息给设备
func (h *WebSocketHandler) forwardToDevice(controller *model.Controller, message []byte) {
	session := h.sessionMgr.GetByController(controller.ID)
	if session == nil || session.Device == nil {
		return
	}

	session.Device.Conn.WriteMessage(websocket.TextMessage, message)
}

// GetDeviceManager 获取设备管理器（供API使用）
func (h *WebSocketHandler) GetDeviceManager() *service.DeviceManager {
	return h.deviceMgr
}
