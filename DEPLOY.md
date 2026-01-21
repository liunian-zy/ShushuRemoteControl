# 舒舒远程控制系统 - 部署文档

本文档详细说明如何部署服务端和 Web 端到服务器。

## 目录

- [环境要求](#环境要求)
- [Docker 部署（推荐）](#docker-部署推荐)
- [手动部署](#手动部署)
- [Nginx 反向代理配置](#nginx-反向代理配置)
- [Android 设备连接配置](#android-设备连接配置)
- [常见问题排查](#常见问题排查)
- [安全建议](#安全建议)

---

## 环境要求

### 服务器要求

| 项目 | 最低要求 | 推荐配置 |
|------|----------|----------|
| 操作系统 | Linux (Ubuntu 20.04+/CentOS 7+) | Ubuntu 22.04 LTS |
| CPU | 1 核 | 2 核+ |
| 内存 | 512MB | 1GB+ |
| 磁盘 | 1GB | 5GB+ |
| 网络 | 公网IP或内网可访问 | 带宽 5Mbps+ |

### Docker 部署依赖

| 软件 | 版本要求 |
|------|----------|
| Docker | 20.10+ |
| Docker Compose | 2.0+ |

### 手动部署依赖

| 软件 | 版本要求 | 用途 |
|------|----------|------|
| Go | 1.21+ | 编译服务端 |
| Node.js | 18+ | 构建 Web 端 |
| npm | 9+ | 包管理 |

---

## Docker 部署（推荐）

### 1. 安装 Docker

#### Ubuntu/Debian

```bash
# 更新包索引
sudo apt update

# 安装依赖
sudo apt install -y apt-transport-https ca-certificates curl gnupg lsb-release

# 添加 Docker 官方 GPG 密钥
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg

# 添加 Docker 仓库
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# 安装 Docker
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# 启动 Docker
sudo systemctl start docker
sudo systemctl enable docker

# 将当前用户添加到 docker 组（可选，避免每次使用 sudo）
sudo usermod -aG docker $USER
newgrp docker

# 验证安装
docker --version
docker compose version
```

#### CentOS/RHEL

```bash
# 安装依赖
sudo yum install -y yum-utils

# 添加 Docker 仓库
sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo

# 安装 Docker
sudo yum install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# 启动 Docker
sudo systemctl start docker
sudo systemctl enable docker

# 验证安装
docker --version
docker compose version
```

### 2. 上传项目到服务器

```bash
# 在服务器上创建目录
mkdir -p /opt/shushu-remote
cd /opt/shushu-remote

# 方式1: 使用 scp 从本地上传整个项目
# 在本地机器执行:
scp -r /path/to/ShushuRemoteControl/* user@your-server:/opt/shushu-remote/

# 方式2: 如果项目在 Git 仓库
git clone <your-repo-url> .

# 方式3: 使用 rsync（推荐，支持增量同步）
rsync -avz --progress /path/to/ShushuRemoteControl/ user@your-server:/opt/shushu-remote/
```

### 3. 创建 Dockerfile

项目中已包含 `Dockerfile`，如果没有，创建 `/opt/shushu-remote/Dockerfile`:

```dockerfile
# ============================================
# 阶段1: 构建 Web 前端
# ============================================
FROM node:18-alpine AS web-builder

WORKDIR /app/web

# 复制 package.json 并安装依赖（利用缓存）
COPY web/package*.json ./
RUN npm install

# 复制源码并构建
COPY web/ ./
RUN npm run build

# ============================================
# 阶段2: 构建 Go 服务端
# ============================================
FROM golang:1.21-alpine AS server-builder

WORKDIR /app/server

# 安装构建依赖
RUN apk add --no-cache git

# 复制 go.mod 并下载依赖（利用缓存）
COPY server/go.mod server/go.sum ./
RUN go mod download

# 复制源码并编译
COPY server/ ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-w -s" -o remote-server cmd/server/main.go

# ============================================
# 阶段3: 运行环境（最小化镜像）
# ============================================
FROM alpine:3.19

# 安装运行时依赖
RUN apk --no-cache add ca-certificates tzdata

# 设置时区
ENV TZ=Asia/Shanghai

WORKDIR /app

# 从构建阶段复制产物
COPY --from=server-builder /app/server/remote-server .
COPY --from=web-builder /app/web/dist ./web/dist

# 暴露端口
EXPOSE 9222

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD-SHELL wget -q --spider "http://localhost:${SERVER_PORT:-9222}/api/health" || exit 1

# 启动命令
ENTRYPOINT ["./remote-server"]
```

### 4. 创建 docker-compose.yml

创建 `/opt/shushu-remote/docker-compose.yml`:

```yaml
version: '3.8'

services:
  shushu-remote:
    build:
      context: .
      dockerfile: Dockerfile
    image: shushu-remote:latest
    container_name: shushu-remote
    ports:
      - "9222:9222"
    environment:
      - TZ=Asia/Shanghai
      - MYSQL_DSN=shushu:shushu123@tcp(host.docker.internal:3306)/shushu?parseTime=true
      - DEVICE_TOKEN=your-secure-token-here
    extra_hosts:
      - "host.docker.internal:host-gateway"
    # 修改下面的 device token 为你的安全密钥
    command: ["-port", "9222", "-mysql", "shushu:shushu123@tcp(host.docker.internal:3306)/shushu?parseTime=true", "-device-token", "your-secure-token-here", "-web", "./web/dist"]
    restart: unless-stopped
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
    healthcheck:
      test: ["CMD", "wget", "-q", "--spider", "http://localhost:9222/api/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 10s
```

### 5. 创建环境变量文件（可选）

创建 `/opt/shushu-remote/.env` 用于管理配置:

```bash
# 服务端口
SERVER_PORT=9222

# MySQL 连接字符串（需要包含 parseTime=true）
# Linux 宿主机 MySQL 示例:
# shushu:shushu123@tcp(host.docker.internal:3306)/shushu?parseTime=true
MYSQL_DSN=shushu:shushu123@tcp(host.docker.internal:3306)/shushu?parseTime=true

# 设备连接 Token（务必修改为强密码）
DEVICE_TOKEN=your-secure-token-here

# Web 静态目录
WEB_DIR=./web/dist

# 时区
TZ=Asia/Shanghai
```

更新 `docker-compose.yml` 使用环境变量:

```yaml
version: '3.8'

services:
  shushu-remote:
    build:
      context: .
      dockerfile: Dockerfile
    image: shushu-remote:latest
    container_name: shushu-remote
    ports:
      - "${SERVER_PORT:-9222}:${SERVER_PORT:-9222}"
    env_file:
      - .env
    command: ["-port", "${SERVER_PORT:-9222}", "-mysql", "${MYSQL_DSN}", "-device-token", "${DEVICE_TOKEN:-shushu123}", "-web", "${WEB_DIR:-./web/dist}"]
    restart: unless-stopped
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

### 6. 构建和启动

```bash
cd /opt/shushu-remote

# 构建镜像
docker compose build

# 启动服务（后台运行）
docker compose up -d

# 查看运行状态
docker compose ps

# 查看日志
docker compose logs -f

# 查看实时日志（最近100行）
docker compose logs -f --tail=100
```

### 7. 验证部署

```bash
# 检查容器状态
docker compose ps

# 检查健康状态
docker inspect --format='{{.State.Health.Status}}' shushu-remote

# 测试 API
curl http://localhost:9222/api/health

# 测试设备列表接口
curl http://localhost:9222/api/devices
```

浏览器访问: `http://your-server-ip:9222`

### 8. 常用 Docker 命令

```bash
# 停止服务
docker compose down

# 重启服务
docker compose restart

# 重新构建并启动（代码更新后）
docker compose up -d --build

# 查看容器资源使用
docker stats shushu-remote

# 进入容器调试
docker exec -it shushu-remote sh

# 清理未使用的镜像
docker image prune -f
```

### 9. 更新部署

当代码更新后，执行以下命令:

```bash
cd /opt/shushu-remote

# 拉取最新代码（如果使用 Git）
git pull

# 重新构建并启动
docker compose up -d --build

# 查看新容器日志
docker compose logs -f
```

---

## 手动部署

如果不使用 Docker，可以手动部署。

### 1. 安装依赖

#### 安装 Go 1.21+

```bash
# 下载 Go
wget https://go.dev/dl/go1.21.5.linux-amd64.tar.gz

# 解压到 /usr/local
sudo tar -C /usr/local -xzf go1.21.5.linux-amd64.tar.gz

# 配置环境变量
echo 'export PATH=$PATH:/usr/local/go/bin' >> ~/.bashrc
echo 'export GOPATH=$HOME/go' >> ~/.bashrc
source ~/.bashrc

# 验证安装
go version
```

#### 安装 Node.js 18+

```bash
# 使用 NodeSource 仓库
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt install -y nodejs

# 验证安装
node -v
npm -v
```

### 2. 构建 Web 端

```bash
cd /opt/shushu-remote/web

# 安装依赖
npm install

# 生产构建
npm run build

# 验证构建产物
ls -la dist/
```

### 3. 构建服务端

```bash
cd /opt/shushu-remote/server

# 下载依赖
go mod tidy

# 编译
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-w -s" -o remote-server cmd/server/main.go

# 验证编译结果
./remote-server --help
```

### 4. 创建 Systemd 服务

```bash
# 创建服务用户
sudo useradd -r -s /bin/false shushu

# 设置目录权限
sudo chown -R shushu:shushu /opt/shushu-remote

# 创建服务文件
sudo tee /etc/systemd/system/shushu-remote.service << 'EOF'
[Unit]
Description=Shushu Remote Control Server
After=network.target

[Service]
Type=simple
User=shushu
Group=shushu
WorkingDirectory=/opt/shushu-remote/server

# 修改 device token 为你的安全密钥
ExecStart=/opt/shushu-remote/server/remote-server -port 9222 -mysql "user:pass@tcp(127.0.0.1:3306)/dbname?parseTime=true" -device-token "your-secure-token-here" -web /opt/shushu-remote/web/dist

Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

# 安全限制
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/shushu-remote

[Install]
WantedBy=multi-user.target
EOF

# 重载配置
sudo systemctl daemon-reload

# 启动服务
sudo systemctl start shushu-remote

# 设置开机自启
sudo systemctl enable shushu-remote

# 查看状态
sudo systemctl status shushu-remote

# 查看日志
sudo journalctl -u shushu-remote -f
```

---

## Nginx 反向代理配置

生产环境推荐使用 Nginx 作为反向代理，支持 HTTPS 和负载均衡。

### 1. 安装 Nginx

```bash
sudo apt install -y nginx
```

### 2. 创建配置文件

```bash
sudo tee /etc/nginx/sites-available/shushu-remote << 'EOF'
upstream shushu_backend {
    server 127.0.0.1:9222;
    keepalive 64;
}

server {
    listen 80;
    server_name your-domain.com;  # 替换为你的域名或服务器IP

    # 如果配置了 HTTPS，取消下面注释以重定向
    # return 301 https://$server_name$request_uri;

    # 客户端最大请求体大小
    client_max_body_size 10M;

    # 静态文件和 API
    location / {
        proxy_pass http://shushu_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 缓冲设置
        proxy_buffering on;
        proxy_buffer_size 4k;
        proxy_buffers 8 4k;
    }

    # WebSocket 连接
    location /ws/ {
        proxy_pass http://shushu_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

        # WebSocket 超时设置（保持长连接）
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;

        # 禁用缓冲
        proxy_buffering off;
    }
}
EOF
```

### 3. 启用配置

```bash
# 创建软链接启用站点
sudo ln -s /etc/nginx/sites-available/shushu-remote /etc/nginx/sites-enabled/

# 删除默认站点（可选）
sudo rm -f /etc/nginx/sites-enabled/default

# 测试配置
sudo nginx -t

# 重载 Nginx
sudo systemctl reload nginx
```

### 4. 配置 HTTPS（推荐）

使用 Let's Encrypt 免费证书:

```bash
# 安装 Certbot
sudo apt install -y certbot python3-certbot-nginx

# 获取证书（替换为你的域名）
sudo certbot --nginx -d your-domain.com

# 自动续期测试
sudo certbot renew --dry-run
```

配置 HTTPS 后的 Nginx 配置:

```bash
sudo tee /etc/nginx/sites-available/shushu-remote << 'EOF'
upstream shushu_backend {
    server 127.0.0.1:9222;
    keepalive 64;
}

# HTTP 重定向到 HTTPS
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$server_name$request_uri;
}

# HTTPS 服务
server {
    listen 443 ssl http2;
    server_name your-domain.com;

    # SSL 证书（由 Certbot 自动配置）
    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;

    # SSL 安全配置
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    client_max_body_size 10M;

    location / {
        proxy_pass http://shushu_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /ws/ {
        proxy_pass http://shushu_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
        proxy_buffering off;
    }
}
EOF

sudo nginx -t
sudo systemctl reload nginx
```

---

## 防火墙配置

### UFW (Ubuntu)

```bash
# 允许 SSH
sudo ufw allow 22/tcp

# 允许 HTTP/HTTPS
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp

# 如果直接访问（不使用 Nginx），允许 9222 端口
sudo ufw allow 9222/tcp

# 启用防火墙
sudo ufw enable

# 查看状态
sudo ufw status
```

### firewalld (CentOS)

```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --permanent --add-port=9222/tcp
sudo firewall-cmd --reload
```

---

## 配置参数说明

| 参数 | 说明 | 默认值 | 示例 |
|------|------|--------|------|
| `-mysql` | MySQL 连接字符串 | (必填) | `-mysql "user:pass@tcp(127.0.0.1:3306)/dbname?parseTime=true"` |
| `-device-token` | 设备连接 Token | shushu123 | `-device-token "MySecureToken123!"` |
| `-port` | 服务监听端口 | 9222 | `-port 8080` |
| `-web` | Web 静态文件目录 | ./web/dist | `-web /opt/shushu-remote/web/dist` |

**重要**: 生产环境务必修改默认设备 Token！建议使用 16 位以上的随机字符串。

生成随机 Token:
```bash
openssl rand -base64 24
```

---

## Android 设备连接配置

部署完成后，在 Android 设备上配置以下信息:

### 直接连接（无 Nginx）

| 配置项 | 值 |
|--------|-----|
| 服务器地址 | `ws://your-server-ip:9222/ws/device` |
| 设备 ID | 自定义唯一标识，如 `DEVICE_001` |
| 设备名称 | 自定义显示名称，如 `工业设备-A01` |
| 设备 Token | 与服务端 `-device-token` 参数一致 |

### 通过 Nginx（HTTP）

| 配置项 | 值 |
|--------|-----|
| 服务器地址 | `ws://your-domain.com/ws/device` |
| 设备 Token | 与服务端配置一致 |

### 通过 Nginx（HTTPS）

| 配置项 | 值 |
|--------|-----|
| 服务器地址 | `wss://your-domain.com/ws/device` |
| 设备 Token | 与服务端配置一致 |

---

## 常见问题排查

### 1. Docker 容器无法启动

```bash
# 查看容器日志
docker compose logs

# 检查容器状态
docker compose ps -a

# 检查镜像构建日志
docker compose build --no-cache
```

### 2. 端口被占用

```bash
# 查看端口占用
sudo lsof -i :9222
sudo netstat -tlnp | grep 9222

# 杀死占用进程
sudo kill -9 <PID>
```

### 3. WebSocket 连接失败

```bash
# 测试 WebSocket 端点
curl -i -N \
  -H "Connection: Upgrade" \
  -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Key: test" \
  -H "Sec-WebSocket-Version: 13" \
  http://localhost:9222/ws/controller

# 检查 Nginx WebSocket 配置
sudo nginx -t
```

### 4. Web 页面空白或 404

```bash
# 检查静态文件是否存在
ls -la /opt/shushu-remote/web/dist/

# Docker 环境检查
docker exec -it shushu-remote ls -la /app/web/dist/

# 检查 index.html
docker exec -it shushu-remote cat /app/web/dist/index.html
```

### 5. 设备无法连接

1. **检查网络连通性**:
   ```bash
   # 从设备网络测试
   telnet your-server-ip 9222
   nc -zv your-server-ip 9222
   ```

2. **检查 Token 是否一致**: 服务端和设备端的 Token 必须完全相同

3. **检查防火墙**: 确保端口已开放

4. **查看服务端日志**:
   ```bash
   # Docker
   docker compose logs -f

   # Systemd
   sudo journalctl -u shushu-remote -f
   ```

### 6. 屏幕传输卡顿

1. 检查服务器带宽
2. 检查设备网络质量
3. 考虑降低屏幕捕获质量（在 Android 端配置）

---

## 安全建议

1. **修改默认 Token**
   - 使用 16 位以上的随机字符串
   - 定期更换 Token

2. **启用 HTTPS**
   - 使用 Let's Encrypt 免费证书
   - 强制 HTTP 重定向到 HTTPS

3. **限制访问来源**
   - 配合 VPN 使用
   - 使用 Nginx 配置 IP 白名单:
     ```nginx
     allow 192.168.1.0/24;
     deny all;
     ```

4. **定期更新**
   - 保持系统和 Docker 更新
   - 定期更新项目依赖

5. **日志监控**
   - 定期检查访问日志
   - 配置日志轮转避免磁盘占满

6. **资源限制**
   - Docker 中配置内存和 CPU 限制:
     ```yaml
     services:
       shushu-remote:
         deploy:
           resources:
             limits:
               cpus: '1'
               memory: 512M
     ```

---

## 快速部署检查清单

- [ ] 服务器已安装 Docker 和 Docker Compose
- [ ] 项目代码已上传到服务器
- [ ] 已创建 Dockerfile
- [ ] 已创建 docker-compose.yml
- [ ] 已修改默认 Token
- [ ] 防火墙已开放相应端口
- [ ] 服务已成功启动 (`docker compose up -d`)
- [ ] 健康检查通过 (`curl http://localhost:9222/api/health`)
- [ ] Web 页面可正常访问
- [ ] Android 设备可正常连接
- [ ] (可选) 已配置 Nginx 反向代理
- [ ] (可选) 已配置 HTTPS

---

## 联系支持

如有问题，请检查日志并提供以下信息:
- 服务器操作系统版本
- Docker 版本
- 错误日志内容
- 网络环境描述
