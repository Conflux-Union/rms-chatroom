# RMS Discord Android APP 开发进度

## 项目总览

基于 Kotlin + Jetpack Compose 的原生 Android 客户端，对应 Web 前端功能。

### 技术栈

| 层级 | 技术 | 用途 |
|------|------|------|
| UI | Jetpack Compose + Material 3 | 声明式UI，原生动画 |
| 架构 | MVVM + Clean Architecture | ViewModel + Repository |
| 网络 | Retrofit + OkHttp | REST API |
| 实时通信 | OkHttp WebSocket | 聊天消息 |
| 语音 | LiveKit Android SDK | WebRTC语音通话 |
| DI | Hilt | 依赖注入 |
| 导航 | Navigation Compose | 页面路由 |
| 存储 | DataStore | Token持久化 |

### 项目结构

```
android/app/src/main/java/com/rms/discord/
├── RMSDiscordApp.kt              # Application入口
├── di/
│   └── AppModule.kt              # Hilt依赖注入模块
├── data/
│   ├── model/Models.kt           # 数据模型 (User, Server, Channel, Message, VoiceUser)
│   ├── api/ApiService.kt         # Retrofit API接口
│   ├── repository/
│   │   ├── AuthRepository.kt     # 认证逻辑
│   │   ├── ChatRepository.kt     # 聊天数据管理
│   │   └── VoiceRepository.kt    # 语音状态管理
│   └── websocket/
│       └── ChatWebSocket.kt      # WebSocket客户端
├── ui/
│   ├── MainActivity.kt           # 主Activity + Deep Link处理
│   ├── theme/                    # Material 3主题
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   ├── navigation/
│   │   └── NavGraph.kt           # 导航图
│   ├── auth/
│   │   ├── AuthViewModel.kt
│   │   └── LoginScreen.kt        # SSO登录页
│   ├── main/
│   │   ├── MainViewModel.kt
│   │   ├── MainScreen.kt         # 主界面
│   │   └── components/
│   │       ├── ServerListColumn.kt   # 服务器列表
│   │       └── ChannelListColumn.kt  # 频道列表
│   ├── chat/
│   │   └── ChatScreen.kt         # 聊天界面
│   ├── voice/
│   │   ├── VoiceViewModel.kt
│   │   └── VoiceScreen.kt        # 语音界面
│   └── music/                    # (待实现)
└── service/
    └── VoiceCallService.kt       # 语音通话前台服务
```

### 配置

- **API地址**: `https://preview-chatroom.rms.net.cn`
- **WebSocket**: `wss://preview-chatroom.rms.net.cn`
- **Deep Link**: `rmsdiscord://callback` (SSO回调)

---

## 开发进度

### ✅ Phase 1: 项目搭建 + 认证 + 服务器/频道列表 (已完成)

- [x] Gradle项目配置 (Version Catalog)
- [x] 数据模型定义 (User, Server, Channel, Message, VoiceUser)
- [x] Retrofit API接口
- [x] Hilt依赖注入配置
- [x] DataStore Token存储
- [x] AuthRepository + AuthViewModel
- [x] LoginScreen (SSO Custom Tabs)
- [x] Deep Link处理 (SSO回调)
- [x] MainScreen + NavigationDrawer
- [x] ServerListColumn (带选中动画)
- [x] ChannelListColumn + UserPanel
- [x] Material 3 主题 (Discord风格深色)
- [x] Navigation Compose 导航图
- [x] Splash Screen配置

### 🔄 Phase 2: 文字聊天 + WebSocket实时消息

- [x] 完善 ChatWebSocket 连接管理
  - 自动重连机制 (指数退避, 最大10次尝试)
  - 心跳保活 (30秒间隔ping)
  - 连接状态监听 (ConnectionState Flow)
- [ ] ChatScreen 功能完善
  - 消息加载状态
  - 下拉刷新历史消息
  - 消息发送状态指示
  - 新消息自动滚动
- [ ] 消息本地缓存 (Room)
  - MessageEntity
  - MessageDao
  - 离线消息支持
- [ ] 消息通知
  - NotificationChannel配置
  - 新消息推送通知

### 🔲 Phase 3: 语音通话 + LiveKit集成

- [ ] LiveKit SDK集成
  - Room连接管理
  - 音频轨道发布/订阅
  - 连接状态监听
- [ ] VoiceViewModel 完善
  - 实际连接LiveKit Room
  - 音频静音/取消静音
  - 扬声器静音/取消静音
- [ ] VoiceScreen 功能完善
  - 用户说话状态指示 (音量动画)
  - 连接质量指示
  - 网络状态显示
- [ ] VoiceCallService 完善
  - 前台服务通知
  - 通知控制按钮 (静音/挂断)
  - WakeLock保持
- [ ] 音频权限处理
  - RECORD_AUDIO权限请求
  - 权限拒绝提示
- [ ] VoiceInviteScreen
  - 语音邀请Deep Link处理
  - 邀请确认界面

### 🔲 Phase 4: 音乐面板

- [ ] MusicViewModel
  - 音乐播放状态
  - 播放队列管理
  - 播放控制 (播放/暂停/上一首/下一首)
- [ ] MusicBottomSheet
  - 当前播放信息
  - 播放控制按钮
  - 进度条
  - 播放队列列表
- [ ] 音乐搜索/添加
  - 搜索界面
  - 添加到队列

### 🔲 Phase 5: 测试 + 优化 + 发布

- [ ] 代码优化
  - 修复deprecation警告 (AutoMirrored icons)
  - kotlinOptions迁移到compilerOptions
- [ ] UI/UX优化
  - 加载状态动画
  - 错误处理和重试
  - 空状态界面
- [ ] 性能优化
  - 图片加载优化
  - 列表性能 (LazyColumn)
  - 内存泄漏检查
- [ ] 测试
  - 单元测试 (ViewModel, Repository)
  - UI测试 (Compose Testing)
- [ ] 发布准备
  - 签名配置
  - ProGuard规则完善
  - 版本号管理
  - Release APK构建

---

## 快速命令

```bash
# 构建Debug APK
./gradlew assembleDebug

# 构建Release APK
./gradlew assembleRelease

# 安装到设备
./gradlew installDebug

# 清理构建
./gradlew clean

# APK输出位置
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

---

## 对应Web前端组件

| Web (Vue) | Android (Compose) | 状态 |
|-----------|-------------------|------|
| `views/Login.vue` | `ui/auth/LoginScreen.kt` | ✅ |
| `views/Callback.vue` | Deep Link in MainActivity | ✅ |
| `views/Main.vue` | `ui/main/MainScreen.kt` | ✅ |
| `components/ServerList.vue` | `ui/main/components/ServerListColumn.kt` | ✅ |
| `components/ChannelList.vue` | `ui/main/components/ChannelListColumn.kt` | ✅ |
| `components/ChatArea.vue` | `ui/chat/ChatScreen.kt` | ✅ 基础 |
| `components/VoicePanel.vue` | `ui/voice/VoiceScreen.kt` | ✅ 基础 |
| `components/VoiceControls.vue` | 集成在 VoiceScreen | ✅ 基础 |
| `components/MusicPanel.vue` | `ui/music/MusicBottomSheet.kt` | 🔲 |
| `stores/auth.ts` | `data/repository/AuthRepository.kt` | ✅ |
| `stores/chat.ts` | `data/repository/ChatRepository.kt` | ✅ |
| `stores/voice.ts` | `data/repository/VoiceRepository.kt` | ✅ 基础 |
| `stores/music.ts` | `ui/music/MusicViewModel.kt` | 🔲 |
| `composables/useWebSocket.ts` | `data/websocket/ChatWebSocket.kt` | ✅ 基础 |
