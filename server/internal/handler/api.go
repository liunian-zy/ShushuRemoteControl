package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// APIHandler REST API处理器
type APIHandler struct {
	wsHandler *WebSocketHandler
	authToken string
}

// NewAPIHandler 创建API处理器
func NewAPIHandler(wsHandler *WebSocketHandler, authToken string) *APIHandler {
	return &APIHandler{
		wsHandler: wsHandler,
		authToken: authToken,
	}
}

// LoginRequest 登录请求
type LoginRequest struct {
	Token string `json:"token"`
}

// Login 登录验证
func (h *APIHandler) Login(c *gin.Context) {
	var req LoginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"success": false,
			"message": "请求格式错误",
		})
		return
	}

	if req.Token == h.authToken {
		c.JSON(http.StatusOK, gin.H{
			"success": true,
			"message": "登录成功",
		})
	} else {
		c.JSON(http.StatusUnauthorized, gin.H{
			"success": false,
			"message": "密码错误",
		})
	}
}

// AuthMiddleware Token验证中间件
func (h *APIHandler) AuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		token := c.GetHeader("Authorization")
		if token == "" {
			token = c.Query("token")
		}

		if token != h.authToken {
			c.JSON(http.StatusUnauthorized, gin.H{
				"success": false,
				"message": "未授权访问",
			})
			c.Abort()
			return
		}
		c.Next()
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
