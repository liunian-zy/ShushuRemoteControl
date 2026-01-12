package protocol

// 消息类型常量
const (
	// 设备消息
	TypeDeviceRegister   = "device.register"
	TypeDeviceHeartbeat  = "device.heartbeat"
	TypeScreenFrame      = "screen.frame" // 二进制消息用 0x01 标识
	TypeClipboardUpdate  = "clipboard.update"

	// 控制端消息
	TypeControlRequest   = "control.request"
	TypeControlRelease   = "control.release"
	TypeInputTouch       = "input.touch"
	TypeInputKey         = "input.key"
	TypeInputText        = "input.text"
	TypeClipboardSet     = "clipboard.set"
	TypeStreamStart      = "stream.start"
	TypeStreamStop       = "stream.stop"

	// 服务端消息
	TypeControlGranted   = "control.granted"
	TypeControlDenied    = "control.denied"
	TypeDeviceList       = "device.list"
	TypeDeviceOnline     = "device.online"
	TypeDeviceOffline    = "device.offline"
	TypeError            = "error"
)

// 二进制消息类型
const (
	BinaryTypeScreenFrame byte = 0x01
)

// BaseMessage 基础消息结构
type BaseMessage struct {
	Type string `json:"type"`
}

// DeviceRegisterMessage 设备注册消息
type DeviceRegisterMessage struct {
	Type         string `json:"type"`
	DeviceID     string `json:"deviceId"`
	DeviceName   string `json:"deviceName"`
	ScreenWidth  int    `json:"screenWidth"`
	ScreenHeight int    `json:"screenHeight"`
	Token        string `json:"token"`
}

// DeviceInfo 设备信息
type DeviceInfo struct {
	DeviceID     string `json:"deviceId"`
	DeviceName   string `json:"deviceName"`
	ScreenWidth  int    `json:"screenWidth"`
	ScreenHeight int    `json:"screenHeight"`
	Online       bool   `json:"online"`
}

// ControlRequestMessage 控制请求消息
type ControlRequestMessage struct {
	Type     string `json:"type"`
	DeviceID string `json:"deviceId"`
}

// ControlGrantedMessage 控制授权消息
type ControlGrantedMessage struct {
	Type         string `json:"type"`
	DeviceID     string `json:"deviceId"`
	SessionID    string `json:"sessionId"`
	ScreenWidth  int    `json:"screenWidth"`
	ScreenHeight int    `json:"screenHeight"`
}

// TouchMessage 触摸消息
type TouchMessage struct {
	Type      string  `json:"type"`
	SessionID string  `json:"sessionId,omitempty"`
	Action    string  `json:"action"` // down, move, up
	X         float64 `json:"x"`
	Y         float64 `json:"y"`
	PointerID int     `json:"pointerId"`
}

// KeyMessage 按键消息
type KeyMessage struct {
	Type      string `json:"type"`
	SessionID string `json:"sessionId,omitempty"`
	KeyCode   int    `json:"keyCode"`
	Action    string `json:"action"` // down, up
}

// TextMessage 文本输入消息
type TextMessage struct {
	Type      string `json:"type"`
	SessionID string `json:"sessionId,omitempty"`
	Text      string `json:"text"`
}

// ClipboardMessage 剪贴板消息
type ClipboardMessage struct {
	Type      string `json:"type"`
	SessionID string `json:"sessionId,omitempty"`
	Text      string `json:"text"`
	AutoPaste bool   `json:"autoPaste,omitempty"`
}

// StreamControlMessage 推流控制消息
type StreamControlMessage struct {
	Type    string `json:"type"`
	Quality int    `json:"quality,omitempty"` // JPEG 质量 1-100
	MaxFPS  int    `json:"maxFps,omitempty"`
}

// ErrorMessage 错误消息
type ErrorMessage struct {
	Type    string `json:"type"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

// DeviceListMessage 设备列表消息
type DeviceListMessage struct {
	Type    string       `json:"type"`
	Devices []DeviceInfo `json:"devices"`
}
