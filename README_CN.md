# RMS Chat

**中文** | [English](./README.md)

一个现代化的通讯平台，支持实时聊天、语音通话和音乐分享功能。使用 Vue3、FastAPI 和 Kotlin 构建。

## ✨ 功能特性

- 🔐 **SSO 认证** - 集成 RMSSSO 安全认证系统
- 💬 **实时聊天** - 基于 WebSocket 的即时消息
- 🎙️ **语音通话** - 使用 LiveKit 的 WebRTC 语音通信
- 🎵 **音乐分享** - QQ 音乐集成与播放队列管理
- 📱 **多平台支持** - Web、桌面端（Electron）和 Android 应用
- 🎨 **现代化界面** - 精美的暗色主题与 Material 3 设计
- 👥 **服务器与频道** - 通过服务器和频道组织对话
- 🔊 **语音管理控制** - 静音参与者、主持人模式、访客邀请

## 🏗️ 架构

```
rms-discord/
├── backend/              # Python FastAPI 后端
├── frontend/             # Vue3 + TypeScript Web 应用
├── electron/             # Electron 桌面端封装
└── android/              # Kotlin + Jetpack Compose 移动应用
```

### 技术栈

**后端：**
- FastAPI (Python 3.11+)
- SQLAlchemy (异步 ORM)
- WebSocket 实时消息
- LiveKit 语音基础设施

**前端：**
- Vue 3 (组合式 API)
- TypeScript
- Pinia (状态管理)
- Vite (构建工具)
- LiveKit Client SDK

**Android：**
- Kotlin
- Jetpack Compose + Material 3
- MVVM + Clean Architecture
- Hilt (依赖注入)
- LiveKit Android SDK

## 🚀 快速开始

### 环境要求

- Python 3.11+
- Node.js 18+
- Android Studio（用于 Android 开发）
- JDK 17+（用于 Android 构建）

### 后端设置

```bash
cd backend
python -m venv .venv
source .venv/bin/activate  # Windows 系统: .venv\Scripts\activate
pip install -r requirements.txt
python -m backend
```

后端运行在 `http://localhost:8000`

### 前端设置

```bash
cd frontend
npm install
npm run dev
```

前端运行在 `http://localhost:5173`

### Android 设置

```bash
cd android
./gradlew assembleDebug
./gradlew installDebug  # 安装到已连接的设备
```

## ⚙️ 配置

### 后端配置 (`backend/config.json`)

```json
{
  "sso_base_url": "https://your-sso-server.com",
  "database_url": "sqlite+aiosqlite:///./chatroom.db",
  "cors_origins": ["http://localhost:5173"]
}
```

### 前端配置 (`frontend/.env`)

```env
VITE_API_BASE=http://localhost:8000
VITE_WS_BASE=ws://localhost:8000
```

### Android 配置 (`android/app/build.gradle.kts`)

构建变体自动配置 API 端点：
- **Debug**：指向 localhost/开发服务器
- **Release**：指向生产服务器

## 📦 生产构建

### Web 应用

```bash
cd frontend
npm run build
# 输出: frontend/dist/
```

### 桌面应用 (Electron)

```bash
cd electron
npm install
npm run build
# 输出: electron/dist/
```

### Android APK

```bash
cd android
./gradlew assembleRelease
# 输出: android/app/build/outputs/apk/release/
```

## 🔄 CI/CD

推送 git 标签时自动触发构建：

```bash
python deploy.py --release  # 创建标签并触发 GitHub Actions
```

GitHub Actions 将构建：
- Android APK（已签名）
- Electron 应用（Windows、macOS、Linux）
- GitHub Release 及所有构建产物

### 设置要求

配置 GitHub Secrets：
- `ANDROID_KEYSTORE_BASE64`：Base64 编码的密钥库
- `KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`：签名凭据

详细说明请参阅 `.github/SETUP.md`。

## 🎯 核心功能说明

### 语音通话

- 基于 LiveKit 基础设施的 WebRTC
- 管理员控制：静音参与者、主持人模式
- 为未认证用户提供访客邀请链接
- Android 上的前台服务支持后台通话

### 音乐分享

- QQ 音乐集成
- 共享播放队列
- 搜索和添加歌曲
- 跨用户同步播放

### 认证

- RMSSSO 集成（无本地用户存储）
- 权限级别（>=3 为管理员功能）
- 移动端 SSO 流程的深度链接处理

## 📱 平台支持

| 平台 | 状态 | 备注 |
|----------|--------|-------|
| Web | ✅ 生产环境 | Chrome、Firefox、Safari |
| 桌面端 | ✅ 生产环境 | Windows、macOS、Linux |
| Android | ✅ 生产环境 | Android 8.0+ |
| iOS | ❌ 计划中 | 未来开发 |

## 🛠️ 开发

### 项目结构

```
backend/
├── core/            # 配置、数据库
├── models/          # SQLAlchemy 模型
├── routers/         # API 端点
├── services/        # 业务逻辑
└── websocket/       # WebSocket 处理器

frontend/src/
├── components/      # Vue 组件
├── composables/     # 组合式 API 钩子
├── stores/          # Pinia 状态存储
├── types/           # TypeScript 类型定义
└── views/           # 页面组件

android/app/src/main/java/cn/net/rms/chatroom/
├── data/            # Repository、API、WebSocket
├── ui/              # Compose 界面
└── service/         # 后台服务
```

### 编码规范

- **Python**：PEP8、类型提示、async/await
- **TypeScript**：严格模式、组合式 API
- **Kotlin**：官方规范、Jetpack Compose
- **提交**：约定式提交（feat/fix/chore）

## 📄 许可证

本项目为专有软件。保留所有权利。

## 🤝 贡献

这是一个私有项目。如需贡献，请联系维护者获取指南。

## 📞 支持

如有问题和疑问，请联系开发团队。

---

由 RMS 团队用 ❤️ 构建
