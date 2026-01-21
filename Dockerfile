# ============================================
# 舒舒远程控制系统 - Docker 构建文件
# 针对国内网络优化，使用国内镜像源
# ============================================

# ============================================
# 阶段1: 构建 Web 前端
# ============================================
FROM node:18-alpine AS web-builder

WORKDIR /app/web

# 设置 npm 国内镜像源
RUN npm config set registry https://registry.npmmirror.com

# 复制 package.json 并安装依赖（利用 Docker 缓存）
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

# 设置 Alpine 国内镜像源
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories

# 设置 Go 国内代理
ENV GOPROXY=https://goproxy.cn,direct
ENV GO111MODULE=on

# 安装构建依赖
RUN apk add --no-cache git

# 复制 go.mod 并下载依赖（利用 Docker 缓存）
COPY server/go.mod server/go.sum ./
RUN go mod download

# 复制源码并编译
COPY server/ ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-w -s" -o remote-server cmd/server/main.go

# ============================================
# 阶段3: 运行环境（最小化镜像）
# ============================================
FROM alpine:3.19

LABEL maintainer="Shushu Remote Control"
LABEL description="工业设备远程维护解决方案"

# 设置 Alpine 国内镜像源
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories

# 安装运行时依赖
RUN apk --no-cache add ca-certificates tzdata wget

# 设置时区
ENV TZ=Asia/Shanghai

# 创建非 root 用户
RUN adduser -D -s /bin/sh shushu

WORKDIR /app

# 从构建阶段复制产物
COPY --from=server-builder /app/server/remote-server .
COPY --from=web-builder /app/web/dist ./web/dist

# 设置文件权限
RUN chown -R shushu:shushu /app

# 切换到非 root 用户
USER shushu

# 暴露端口
EXPOSE 9222

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD /bin/sh -c 'wget -q --spider "http://localhost:${SERVER_PORT:-9222}/api/health" || exit 1'

# 启动命令
ENTRYPOINT ["./remote-server"]
