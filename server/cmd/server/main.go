package main

import (
	"flag"
	"log"
	"net/http"
	"os"
	"path/filepath"

	"github.com/gin-gonic/gin"

	"shushu-remote-control/internal/handler"
	"shushu-remote-control/internal/store"
)

const (
	defaultPort        = "9222"
	defaultDeviceToken = "shushu123"
	defaultWebDir      = "./web/dist"

	envPort        = "SERVER_PORT"
	envMySQL       = "MYSQL_DSN"
	envDeviceToken = "DEVICE_TOKEN"
	envWebDir      = "WEB_DIR"
	envAuthToken   = "AUTH_TOKEN"
)

type stringFlag struct {
	value string
	set   bool
}

func (f *stringFlag) String() string {
	return f.value
}

func (f *stringFlag) Set(v string) error {
	f.value = v
	f.set = true
	return nil
}

func resolveString(flagValue *stringFlag, envKey, fallback string) string {
	if flagValue != nil && flagValue.set {
		return flagValue.value
	}
	if envValue := os.Getenv(envKey); envValue != "" {
		return envValue
	}
	return fallback
}

func main() {
	// 命令行参数
	portFlag := &stringFlag{value: defaultPort}
	mysqlFlag := &stringFlag{value: ""}
	deviceTokenFlag := &stringFlag{value: defaultDeviceToken}
	webDirFlag := &stringFlag{value: defaultWebDir}

	flag.Var(portFlag, "port", "服务端口")
	flag.Var(mysqlFlag, "mysql", "MySQL 连接字符串")
	flag.Var(deviceTokenFlag, "device-token", "设备连接Token")
	flag.Var(webDirFlag, "web", "Web静态文件目录")
	flag.Parse()

	port := resolveString(portFlag, envPort, defaultPort)
	mysqlDSN := resolveString(mysqlFlag, envMySQL, "")
	deviceToken := resolveString(deviceTokenFlag, envDeviceToken, defaultDeviceToken)
	if !deviceTokenFlag.set && os.Getenv(envDeviceToken) == "" {
		if legacyToken := os.Getenv(envAuthToken); legacyToken != "" {
			deviceToken = legacyToken
		}
	}
	webDir := resolveString(webDirFlag, envWebDir, defaultWebDir)

	log.Printf("启动服务器...")
	log.Printf("端口: %s", port)
	log.Printf("MySQL: configured")
	log.Printf("Web目录: %s", webDir)

	if mysqlDSN == "" {
		log.Fatal("MySQL 连接字符串不能为空")
	}

	if deviceToken == "" {
		log.Fatal("设备连接Token不能为空")
	}

	deviceStore, err := store.NewDeviceStore(mysqlDSN)
	if err != nil {
		log.Fatalf("MySQL 连接失败: %v", err)
	}
	defer deviceStore.Close()

	// 创建处理器
	wsHandler := handler.NewWebSocketHandler(deviceToken, deviceStore)
	apiHandler := handler.NewAPIHandler()

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

	// 公开API（无需认证）
	r.GET("/api/health", apiHandler.HealthCheck)

	// WebSocket路由（带token验证）
	r.GET("/ws/device", wsHandler.HandleDevice)
	r.GET("/ws/controller", wsHandler.HandleController)

	// 静态文件服务（Web控制端）
	absWebDir, _ := filepath.Abs(webDir)
	r.Static("/assets", filepath.Join(absWebDir, "assets"))
	r.StaticFile("/", filepath.Join(absWebDir, "index.html"))
	r.StaticFile("/favicon.ico", filepath.Join(absWebDir, "favicon.ico"))
	r.NoRoute(func(c *gin.Context) {
		c.File(filepath.Join(absWebDir, "index.html"))
	})

	log.Printf("服务器启动成功: http://0.0.0.0:%s", port)
	log.Printf("设备连接地址: ws://服务器IP:%s/ws/device", port)
	log.Printf("控制端连接地址: ws://服务器IP:%s/ws/controller", port)

	if err := r.Run(":" + port); err != nil {
		log.Fatalf("服务器启动失败: %v", err)
	}
}
