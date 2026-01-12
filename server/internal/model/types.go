package model

import (
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const writeWait = 10 * time.Second // 写超时

// Device 设备实体
type Device struct {
	ID           string
	Name         string
	ScreenWidth  int
	ScreenHeight int
	Token        string
	Conn         *websocket.Conn
	ConnMutex    sync.Mutex
	LastSeen     time.Time
	Online       bool
}

// Controller 控制端实体
type Controller struct {
	ID        string
	Conn      *websocket.Conn
	ConnMutex sync.Mutex
	SessionID string // 当前控制的会话ID
	DeviceID  string // 当前控制的设备ID
}

// Session 控制会话
type Session struct {
	ID           string
	DeviceID     string
	ControllerID string
	Device       *Device
	Controller   *Controller
	CreatedAt    time.Time
	Active       bool
}

// SendJSON 线程安全地发送JSON消息
func (d *Device) SendJSON(v interface{}) error {
	d.ConnMutex.Lock()
	defer d.ConnMutex.Unlock()
	d.Conn.SetWriteDeadline(time.Now().Add(writeWait))
	return d.Conn.WriteJSON(v)
}

// SendBinary 线程安全地发送二进制消息
func (d *Device) SendBinary(data []byte) error {
	d.ConnMutex.Lock()
	defer d.ConnMutex.Unlock()
	d.Conn.SetWriteDeadline(time.Now().Add(writeWait))
	return d.Conn.WriteMessage(websocket.BinaryMessage, data)
}

// SendJSON 线程安全地发送JSON消息
func (c *Controller) SendJSON(v interface{}) error {
	c.ConnMutex.Lock()
	defer c.ConnMutex.Unlock()
	c.Conn.SetWriteDeadline(time.Now().Add(writeWait))
	return c.Conn.WriteJSON(v)
}

// SendBinary 线程安全地发送二进制消息
func (c *Controller) SendBinary(data []byte) error {
	c.ConnMutex.Lock()
	defer c.ConnMutex.Unlock()
	c.Conn.SetWriteDeadline(time.Now().Add(writeWait))
	return c.Conn.WriteMessage(websocket.BinaryMessage, data)
}
