# 舒舒远程控制系统

工业设备远程维护解决方案，支持 Web 端远程控制 Android 设备。

## 项目结构

```
ShushuRemoteControl/
├── server/          # Go 服务端
├── web/             # Vue3 Web 控制端
└── android/         # Android 被控端
```

## 功能特性

- 实时屏幕传输 (MJPEG)
- 远程触摸控制
- 剪贴板双向同步
- 设备在线状态管理
- 多设备支持

## 快速开始

### 1. 启动服务端

```bash
cd server

# 安装依赖
go mod tidy

# 运行服务
go run cmd/server/main.go -mysql "user:pass@tcp(127.0.0.1:3306)/dbname?parseTime=true" -device-token shushu123

# 或编译后运行
go build -o remote-server cmd/server/main.go
./remote-server -mysql "user:pass@tcp(127.0.0.1:3306)/dbname?parseTime=true" -device-token shushu123
```

服务端参数：
- `-mysql`: MySQL 连接字符串（必须包含 `parseTime=true`）
- `-device-token`: 设备连接 Token，默认 shushu123
- `-port`: 服务端口，默认 9222
- `-web`: Web 静态文件目录，默认 ./web/dist

支持环境变量（参数优先，未传读取环境变量）：
- `MYSQL_DSN`、`DEVICE_TOKEN`、`SERVER_PORT`、`WEB_DIR`

### 2. 构建 Web 控制端

```bash
cd web

# 安装依赖
npm install

# 开发模式
npm run dev

# 生产构建
npm run build
```

构建后将 `dist` 目录部署到服务端的 `web/dist` 目录。

### 3. 构建 Android 被控端

使用 Android Studio 打开 `android` 目录，构建 APK 并安装到目标设备。

**注意**：如需完整的输入注入功能，需要：
- 系统签名（推荐）
- 或 root 权限

### 4. 使用流程

1. 启动服务端
2. 在 Android 设备上安装并启动应用
3. 配置服务器地址（如 `ws://192.168.1.100:9222/ws/device`）
4. 点击"启动服务"，授权屏幕录制
5. 通过外部系统生成访问链接：`http://192.168.1.100:9222/remote/{deviceId}?token={token}`
6. 浏览器打开链接进入远程控制

## 架构说明

```
┌─────────────┐         WebSocket         ┌─────────────────┐
│ Web 控制端   │◄────────────────────────►│                 │
│ (浏览器)    │                           │    Go 服务端     │
└─────────────┘                           │                 │
                                          │  - 设备管理      │
┌─────────────┐         WebSocket         │  - 会话管理      │
│ Android     │◄────────────────────────►│  - 消息路由      │
│ 被控端      │                           │  - 媒体中继      │
└─────────────┘                           └─────────────────┘
```

## 通信协议

### 设备注册
```json
{
  "type": "device.register",
  "deviceId": "DEVICE_001",
  "deviceName": "工业设备-A01",
  "screenWidth": 1920,
  "screenHeight": 1080,
  "token": "shushu123"
}
```

### 触摸事件
```json
{
  "type": "input.touch",
  "action": "down|move|up",
  "x": 500,
  "y": 800,
  "pointerId": 0
}
```

### 剪贴板同步
```json
{
  "type": "clipboard.update",
  "text": "复制的内容"
}
```

### 屏幕帧
二进制消息，直接传输 JPEG 数据。

## 配置说明

### 服务端配置

通过命令行参数配置：

| 参数 | 说明 | 默认值 |
|------|------|--------|
| -mysql | MySQL 连接字符串 | (必填) |
| -device-token | 设备连接 Token | shushu123 |
| -port | 服务端口 | 9222 |
| -web | Web 静态文件目录 | ./web/dist |

### Android 端配置

在应用界面配置：

| 配置项 | 说明 | 示例 |
|--------|------|------|
| 服务器地址 | WebSocket 地址 | ws://192.168.1.100:9222/ws/device |
| 设备ID | 唯一标识 | DEVICE_001 |
| 设备名称 | 显示名称 | 工业设备-A01 |
| 设备Token | 与服务端 `-device-token` 一致 | shushu123 |

## 安全建议

1. **生产环境务必修改默认设备 Token**
2. 建议使用 HTTPS/WSS 加密传输
3. 可配合 VPN 使用，限制访问范围
4. 定期更换认证凭据

## 后续扩展

- [ ] H.264 视频编码（降低带宽）
- [ ] WebRTC P2P 传输
- [ ] 远程 Shell 终端
- [ ] 文件传输
- [ ] 多用户权限管理
- [ ] 操作审计日志

## 许可证

MIT License
