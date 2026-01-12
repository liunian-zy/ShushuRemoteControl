package main

import (
	"flag"
	"log"
	"net/http"
	"path/filepath"

	"github.com/gin-gonic/gin"

	"shushu-remote-control/internal/handler"
)

func main() {
	// 命令行参数
	port := flag.String("port", "9222", "服务端口")
	token := flag.String("token", "shushu123", "认证Token")
	webDir := flag.String("web", "./web/dist", "Web静态文件目录")
	flag.Parse()

	log.Printf("启动服务器...")
	log.Printf("端口: %s", *port)
	log.Printf("Web目录: %s", *webDir)

	// 创建处理器
	wsHandler := handler.NewWebSocketHandler(*token)
	apiHandler := handler.NewAPIHandler(wsHandler)

	// 设置Gin
	gin.SetMode(gin.ReleaseMode)
	r := gin.Default()

	// CORS中间件
	r.Use(func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	})

	// API路由
	api := r.Group("/api")
	{
		api.GET("/health", apiHandler.HealthCheck)
		api.GET("/devices", apiHandler.GetDevices)
	}

	// WebSocket路由
	r.GET("/ws/device", wsHandler.HandleDevice)
	r.GET("/ws/controller", wsHandler.HandleController)

	// 静态文件服务（Web控制端）
	absWebDir, _ := filepath.Abs(*webDir)
	r.Static("/assets", filepath.Join(absWebDir, "assets"))
	r.StaticFile("/", filepath.Join(absWebDir, "index.html"))
	r.StaticFile("/favicon.ico", filepath.Join(absWebDir, "favicon.ico"))
	r.NoRoute(func(c *gin.Context) {
		c.File(filepath.Join(absWebDir, "index.html"))
	})

	log.Printf("服务器启动成功: http://0.0.0.0:%s", *port)
	log.Printf("设备连接地址: ws://服务器IP:%s/ws/device", *port)
	log.Printf("控制端连接地址: ws://服务器IP:%s/ws/controller", *port)

	if err := r.Run(":" + *port); err != nil {
		log.Fatalf("服务器启动失败: %v", err)
	}
}
