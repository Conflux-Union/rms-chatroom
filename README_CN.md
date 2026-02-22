# RMS Chat

**中文** | [English](./README.md)

现代化通讯平台，支持实时聊天、语音通话和音乐分享。使用 Vue 3、Go (Echo) 和 Kotlin 构建。

## 功能特性

- **OAuth 2.0 认证** - 集成 RMSSSO，本地 JWT + refresh token 轮换
- **实时聊天** - 基于 WebSocket 的即时消息，支持断线重连和心跳检测
- **语音通话** - 基于 LiveKit 的 WebRTC 语音通信
- **音乐分享** - QQ 音乐 + 网易云音乐，跨客户端同步播放
- **多平台** - Web、桌面端 (Electron)、Android
- **双维度权限** - 每个资源可配置 `permission_level` AND/OR `group_level`
- **降噪** - 通过 AudioWorklet 支持 RNNoise 和 DTLN
- **语音管理** - 静音参与者、主持人模式、访客邀请

## 架构

```
rms-discord/
├── packages/                # pnpm monorepo
│   ├── shared/             # 共享组件、状态管理、composables
│   ├── web/                # Web 入口
│   └── electron-renderer/  # Electron 渲染进程入口
├── electron/               # Electron 主进程
├── backend-go/             # Go 后端 (Echo 框架)
├── android/                # Kotlin + Jetpack Compose
└── pnpm-workspace.yaml
```

### 技术栈

**后端 (Go)：**
- Echo 框架
- MySQL + golang-migrate + sqlc
- gorilla/websocket 实时消息
- LiveKit 语音基础设施
- JSON 配置 + 环境变量覆盖

**前端 (pnpm monorepo)：**
- Vue 3 (组合式 API) + TypeScript
- Pinia (状态管理)
- Vite (构建工具)
- LiveKit Client SDK

**Android：**
- Kotlin + Jetpack Compose + Material 3
- MVVM + Clean Architecture
- Hilt (依赖注入)
- LiveKit Android SDK

## 快速开始

### 环境要求

- Go 1.24+
- Node.js 18+ 和 pnpm
- MySQL
- Android Studio + JDK 17+（Android 开发）

### 后端设置

```bash
cd backend-go
cp config.example.json config.json  # 编辑配置
go run ./cmd/server/main.go
```

后端运行在 `http://localhost:8000`

### 前端设置

```bash
pnpm install
pnpm dev:web
```

前端运行在 `http://localhost:5173`

### Android 设置

```bash
cd android
./gradlew assembleDebug
./gradlew installDebug
```

## 配置

### 后端 (`backend-go/config.json`)

```json
{
  "database_url": "mysql://user:password@localhost/rmschat?charset=utf8mb4",
  "oauth_base_url": "https://sso.rms.net.cn",
  "oauth_client_id": "your-client-id",
  "oauth_client_secret": "your-client-secret",
  "oauth_redirect_uri": "http://localhost:8000/api/auth/callback",
  "jwt_secret": "your-jwt-secret",
  "cors_origins": ["http://localhost:5173"],
  "host": "0.0.0.0",
  "port": 8000
}
```

### 前端 (`.env`)

```env
VITE_API_BASE=http://localhost:8000
VITE_WS_BASE=ws://localhost:8000
```

### Android (`android/app/build.gradle.kts`)

构建变体自动配置 API 端点：
- **Debug**：指向 localhost/开发服务器
- **Release**：指向生产服务器

## 认证

OAuth 2.0 流程 + 本地 JWT：

1. **登录** - 重定向到 RMSSSO，携带 JWT 编码的 CSRF state
2. **回调** - 验证 state，用 code 换取 SSO token，获取用户信息，签发本地 JWT (15分钟) + refresh token (30天)
3. **Token 传递** - Web: URL fragment (`#access_token=...`)；原生端: query string (`?access_token=...`)
4. **刷新** - `POST /api/auth/refresh`，token 轮换（新 token 先存后删旧 token，保证崩溃安全）
5. **自动刷新** - 前端/Android 拦截 401 响应，透明刷新 token

Redirect URL 验证防止开放重定向：仅允许 `cors_origins` 下的 `/callback`、localhost 回调服务器或 `rmschatroom://callback`。

## 权限

双维度模型，应用于服务器、频道组和频道：

| 模式 | 逻辑 |
|------|------|
| AND  | `permission_level >= 阈值` **且** `group_level >= 阈值` |
| OR   | `permission_level >= 阈值` **或** `group_level >= 阈值` |

向后兼容：默认值 (`perm_min_level=0`, `logic_operator=AND`) 退化为单维度 group_level 检查。

## 生产构建

```bash
pnpm build:web                # Web 前端
pnpm build:electron           # Electron 渲染进程
cd backend-go && go build ./cmd/server/main.go  # Go 二进制
cd android && ./gradlew assembleRelease          # Android APK
```

## 部署

```bash
python deploy.py --release   # 打标签 + CI/CD（Android、Electron、服务器）
python deploy.py --hot-fix   # 热修复版本
python deploy.py --debug     # 调试部署（不打标签）
```

GitHub Actions 构建 Android APK、Electron 应用 (Windows/macOS/Linux)，部署服务器二进制，创建 GitHub Release。

## 平台支持

| 平台 | 状态 | 备注 |
|------|------|------|
| Web | 生产环境 | Chrome、Firefox、Safari |
| 桌面端 | 生产环境 | Windows、macOS、Linux (Electron) |
| Android | 生产环境 | Android 8.0+ |
| iOS | 计划中 | - |

## 许可证

专有软件，保留所有权利。

---

由 RMS 团队构建
