# 远控系统改造计划（简化版）

## 架构调整

**改造前：**
- 控制端：登录 → 设备列表 → 选择设备 → 远控
- 鉴权：统一 token

**改造后：**
- 控制端：直接访问 `/remote/:deviceId?token=xxx` → 远控
- 鉴权：每个设备独立 token（带有效期），由外部系统管理
- 管理：设备列表/分组/别名由外部系统处理，远控系统读取展示
- 被控端 token：写死在配置中，不存数据库

## 系统职责划分

| 功能 | 远控系统 | 外部系统 |
|------|---------|---------|
| 设备连接/心跳 | ✅ | - |
| 屏幕推流/输入转发 | ✅ | - |
| 控制端 token 校验 | ✅ | - |
| 设备在线状态更新 | ✅ | - |
| 用户登录 | ❌ | ✅ |
| 设备 token 生成/刷新 | ❌ | ✅ |
| 设备别名/分组管理 | ❌ | ✅ |

---

## 数据库设计（MySQL）

```sql
-- 设备表
CREATE TABLE `rc_devices` (
    `id` VARCHAR(64) PRIMARY KEY COMMENT '设备唯一ID（Android端生成）',
    `name` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '设备上报的原始名称',
    `alias` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '自定义别名',
    `group_id` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '分组ID',
    `screen_width` INT DEFAULT 0 COMMENT '屏幕宽度',
    `screen_height` INT DEFAULT 0 COMMENT '屏幕高度',
    `token` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '控制端访问token',
    `token_expires` DATETIME DEFAULT NULL COMMENT 'token过期时间（NULL=永不过期）',
    `online` TINYINT(1) DEFAULT 0 COMMENT '在线状态',
    `last_seen` DATETIME DEFAULT NULL COMMENT '最后心跳时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_token` (`token`),
    INDEX `idx_group` (`group_id`),
    INDEX `idx_online` (`online`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='远控设备表';

-- 分组表
CREATE TABLE `rc_groups` (
    `id` VARCHAR(64) PRIMARY KEY COMMENT '分组ID',
    `name` VARCHAR(128) NOT NULL COMMENT '分组名称',
    `sort_order` INT DEFAULT 0 COMMENT '排序',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备分组表';
```

**字段说明：**
- `token`：控制端访问用，由外部系统生成和管理
- `token_expires`：token 过期时间，过期后需要外部系统刷新
- `alias`：自定义别名，由外部系统管理
- `group_id`：分组ID，关联 rc_groups 表
- 被控端 token：写死在服务端启动参数中，不存数据库

---

## 鉴权流程

### 被控端（Android）连接
```
Android → WS /ws/device
         → 发送 device.register { deviceId, token, ... }
         → 服务端验证 token == 启动参数中的 device_token
         → 验证通过 → 查询/创建设备记录，更新 online=1
         → 验证失败 → 断开连接
```

### 控制端（Web）连接
```
外部系统生成 URL: https://远控地址/remote/{deviceId}?token={token}

Web 页面加载 → 从 URL 获取 deviceId 和 token
            → WS /ws/controller?deviceId=xxx&token=xxx
            → 服务端查询 MySQL:
               SELECT * FROM rc_devices
               WHERE id=? AND token=?
               AND (token_expires IS NULL OR token_expires > NOW())
            → 验证通过 → 检查设备在线 → 建立控制会话
            → token 过期 → 返回 TOKEN_EXPIRED 错误
            → token 无效 → 返回 INVALID_TOKEN 错误
            → 设备离线 → 返回 DEVICE_OFFLINE 错误
```

---

## 外部系统集成

### 1. 创建设备和分组

```sql
-- 创建分组
INSERT INTO rc_groups (id, name, sort_order)
VALUES ('group_001', '生产线A', 1);

-- 创建设备（外部系统需要知道设备ID，可从 Android 端获取）
INSERT INTO rc_devices (id, name, alias, group_id, token, token_expires)
VALUES (
    'DEVICE_001',
    'RK3588',
    '1号生产线主控',
    'group_001',
    'ct_随机字符串',
    DATE_ADD(NOW(), INTERVAL 7 DAY)
);
```

### 2. 生成控制链接

```python
def generate_control_url(device_id):
    device = db.query("SELECT token FROM rc_devices WHERE id = ?", device_id)
    return f"https://远控地址/remote/{device_id}?token={device.token}"
```

### 3. 刷新 token

```sql
-- token 过期后刷新
UPDATE rc_devices
SET token = 'ct_新随机字符串',
    token_expires = DATE_ADD(NOW(), INTERVAL 7 DAY)
WHERE id = 'DEVICE_001';
```

### 4. 查询设备状态

```sql
-- 查询在线设备（带分组信息）
SELECT d.*, g.name as group_name
FROM rc_devices d
LEFT JOIN rc_groups g ON d.group_id = g.id
WHERE d.online = 1;
```

---

## 服务端改造

### 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `server/go.mod` | 修改 | 添加 MySQL 驱动 |
| `server/internal/store/mysql.go` | 新建 | MySQL 连接和设备查询 |
| `server/internal/service/device.go` | 修改 | 集成 MySQL |
| `server/internal/handler/websocket.go` | 修改 | 控制端按设备 token 鉴权 |
| `server/internal/handler/api.go` | 简化 | 移除登录 API，保留健康检查 |
| `server/cmd/server/main.go` | 修改 | 添加 MySQL 配置 |

### 配置参数

```bash
./server \
  -mysql "user:pass@tcp(127.0.0.1:3306)/dbname" \
  -device-token "shushu123" \
  -port 9222
```

- `-mysql`：MySQL 连接字符串
- `-device-token`：被控端连接用的固定 token
- `-port`：服务端口

### 核心代码改动

**1. MySQL 存储层** (`server/internal/store/mysql.go`)

```go
type DeviceStore struct {
    db *sql.DB
}

// 获取或创建设备（被控端注册时调用）
func (s *DeviceStore) UpsertDevice(device *Device) error

// 验证控制 token（控制端连接时调用）
// 返回: device, error
// error 类型: ErrInvalidToken, ErrTokenExpired, ErrDeviceNotFound
func (s *DeviceStore) ValidateControlToken(deviceID, token string) (*Device, error)

// 更新设备在线状态
func (s *DeviceStore) SetOnline(deviceID string, online bool) error

// 更新设备信息（屏幕尺寸、名称等）
func (s *DeviceStore) UpdateDeviceInfo(device *Device) error
```

**2. 错误码定义**

```go
const (
    ErrCodeInvalidToken  = "INVALID_TOKEN"   // token 无效
    ErrCodeTokenExpired  = "TOKEN_EXPIRED"   // token 已过期
    ErrCodeDeviceOffline = "DEVICE_OFFLINE"  // 设备离线
    ErrCodeDeviceNotFound = "DEVICE_NOT_FOUND" // 设备不存在
)
```

**3. WebSocket Handler 改动**

```go
// 控制端连接
func (h *WebSocketHandler) HandleController(c *gin.Context) {
    deviceID := c.Query("deviceId")
    token := c.Query("token")

    device, err := h.store.ValidateControlToken(deviceID, token)
    if err != nil {
        switch err {
        case store.ErrTokenExpired:
            c.JSON(401, gin.H{"error": "TOKEN_EXPIRED", "message": "链接已过期，请重新获取"})
        case store.ErrInvalidToken:
            c.JSON(401, gin.H{"error": "INVALID_TOKEN", "message": "无效的访问链接"})
        default:
            c.JSON(401, gin.H{"error": "UNAUTHORIZED"})
        }
        return
    }

    if !device.Online {
        c.JSON(400, gin.H{"error": "DEVICE_OFFLINE", "message": "设备不在线"})
        return
    }

    // 建立 WebSocket 连接...
    // 保持现有的多人控制逻辑不变
}
```

---

## Web 端改造

### 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `web/src/views/Login.vue` | 删除 | 不再需要 |
| `web/src/views/DeviceList.vue` | 删除 | 不再需要 |
| `web/src/views/RemoteControl.vue` | 修改 | 从 URL 获取参数，处理错误 |
| `web/src/router/index.ts` | 修改 | 简化路由 |
| `web/src/services/websocket.ts` | 修改 | 连接时带 deviceId 和 token |

### 路由配置

```typescript
const routes = [
  {
    path: '/remote/:deviceId',
    name: 'RemoteControl',
    component: () => import('../views/RemoteControl.vue'),
    props: true
  },
  {
    path: '/error',
    name: 'Error',
    component: () => import('../views/Error.vue')
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/error'
  }
]
```

### RemoteControl.vue 改动

```typescript
const route = useRoute()
const deviceId = route.params.deviceId as string
const token = route.query.token as string

const errorMessage = ref('')

// 连接 WebSocket
const wsUrl = `ws://${host}/ws/controller?deviceId=${deviceId}&token=${token}`

// 错误处理
ws.onclose = (event) => {
  if (event.code === 4001) {
    errorMessage.value = '链接已过期，请重新获取访问链接'
  } else if (event.code === 4002) {
    errorMessage.value = '无效的访问链接'
  } else if (event.code === 4003) {
    errorMessage.value = '设备不在线'
  }
}
```

---

## 完整访问流程

```
1. 外部系统在 rc_devices 表创建设备记录（含 token 和有效期）
                ↓
2. Android 设备使用固定 device_token 连接服务端
                ↓
3. 服务端验证 device_token，更新 rc_devices.online=1
                ↓
4. 外部系统生成控制链接：/remote/{deviceId}?token={token}
                ↓
5. 用户点击链接，Web 页面加载
                ↓
6. Web 使用 deviceId + token 连接 WebSocket
                ↓
7. 服务端验证 token 和有效期
   - 过期 → 返回 TOKEN_EXPIRED，页面提示"链接已过期"
   - 无效 → 返回 INVALID_TOKEN，页面提示"无效链接"
   - 离线 → 返回 DEVICE_OFFLINE，页面提示"设备不在线"
                ↓
8. 验证通过 → 建立控制会话，开始远控
```

---

## 验证方案

1. **设备连接验证**
   - Android 使用正确 device_token 连接 → 成功，rc_devices.online=1
   - Android 使用错误 token 连接 → 失败

2. **控制端验证**
   - 访问 `/remote/DEVICE_ID?token=正确token` → 进入远控
   - 访问 `/remote/DEVICE_ID?token=错误token` → 显示"无效链接"
   - 访问 `/remote/DEVICE_ID?token=过期token` → 显示"链接已过期"
   - 访问 `/remote/DEVICE_ID`（无 token）→ 显示"无效链接"
   - 设备离线时访问 → 显示"设备不在线"

3. **多人控制验证**
   - 多个控制端使用相同 token 同时连接 → 保持现有逻辑（一个设备同时只能被一人控制）
