package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// APIHandler REST API处理器
type APIHandler struct {
	wsHandler *WebSocketHandler
}

// NewAPIHandler 创建API处理器
func NewAPIHandler(wsHandler *WebSocketHandler) *APIHandler {
	return &APIHandler{
		wsHandler: wsHandler,
	}
}

// GetDevices 获取设备列表
func (h *APIHandler) GetDevices(c *gin.Context) {
	devices := h.wsHandler.GetDeviceManager().List()
	c.JSON(http.StatusOK, gin.H{
		"code":    0,
		"message": "success",
		"data":    devices,
	})
}

// HealthCheck 健康检查
func (h *APIHandler) HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status": "ok",
	})
}
