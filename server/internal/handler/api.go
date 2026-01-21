package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// APIHandler REST API处理器
type APIHandler struct {
}

// NewAPIHandler 创建API处理器
func NewAPIHandler() *APIHandler {
	return &APIHandler{
	}
}

// HealthCheck 健康检查
func (h *APIHandler) HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status": "ok",
	})
}
