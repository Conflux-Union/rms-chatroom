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
│   ├── model/Models.kt           # 数据模型 (User, Server, Channel, Message, VoiceUser, VoiceInviteInfo)
│   ├── api/ApiService.kt         # Retrofit API接口
│   ├── repository/
│   │   ├── AuthRepository.kt     # 认证逻辑
│   │   ├── ChatRepository.kt     # 聊天数据管理
│   │   └── VoiceRepository.kt    # 语音状态管理 + LiveKit集成
│   ├── livekit/
│   │   └── LiveKitManager.kt     # LiveKit Room连接管理
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
│   │   ├── VoiceViewModel.kt     # 语音状态管理
│   │   ├── VoiceScreen.kt        # 语音界面 (说话指示+权限请求+音乐FAB)
│   │   ├── VoiceInviteViewModel.kt  # 语音邀请状态管理
│   │   └── VoiceInviteScreen.kt  # 访客语音邀请界面
│   ├── music/
│   │   ├── MusicViewModel.kt     # 音乐状态管理+API调用
│   │   ├── MusicBottomSheet.kt   # 音乐面板 (播放信息+控制+队列)
│   │   ├── MusicSearchDialog.kt  # 音乐搜索对话框
│   │   └── MusicLoginDialog.kt   # QQ音乐登录对话框
│   └── common/
│       └── CommonComponents.kt   # 通用UI组件 (Loading/Error/Empty/Shimmer)
└── service/
    └── VoiceCallService.kt       # 语音通话前台服务 (通知控制+WakeLock)

# 测试目录
app/src/test/java/com/rms/discord/
├── AuthViewModelTest.kt          # AuthViewModel单元测试
└── MainViewModelTest.kt          # MainViewModel单元测试

app/src/androidTest/java/com/rms/discord/
├── LoginScreenTest.kt            # LoginScreen UI测试
└── CommonComponentsTest.kt       # 通用组件UI测试
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

### ✅ Phase 2: 文字聊天 + WebSocket实时消息 (已完成)

- [x] 完善 ChatWebSocket 连接管理
  - 自动重连机制 (指数退避, 最大10次尝试)
  - 心跳保活 (30秒间隔ping)
  - 连接状态监听 (ConnectionState Flow)
- [x] ChatScreen 功能完善
  - 消息加载状态 (isLoading + CircularProgressIndicator)
  - 下拉刷新历史消息 (PullToRefreshBox)
  - 消息发送状态指示 (SendingState enum + 按钮状态)
  - 新消息自动滚动 (LaunchedEffect + animateScrollToItem)
  - WebSocket连接状态横幅 (ConnectionBanner)
- [x] 消息本地缓存 (Room)
  - MessageEntity (data/local/MessageEntity.kt)
  - MessageDao (data/local/MessageDao.kt)
  - AppDatabase (data/local/AppDatabase.kt)
  - 离线消息支持 (先加载缓存, 网络失败时回退)
  - 7天缓存自动清理
- [x] 消息通知
  - NotificationChannel配置 (notification/NotificationHelper.kt)
  - 新消息推送通知 (后台运行时触发)
  - 通知权限请求 (Android 13+)
  - 前台时自动取消通知

### ✅ Phase 3: 语音通话 + LiveKit集成 (已完成)

- [x] LiveKit SDK集成
  - LiveKitManager (data/livekit/LiveKitManager.kt)
  - Room连接管理 (connect/disconnect)
  - 音频轨道发布/订阅
  - 连接状态监听 (ConnectionState Flow)
  - 参与者状态更新 (ParticipantInfo)
- [x] API接口修正
  - 修改ApiService匹配后端API
  - 添加VoiceTokenResponse.roomName字段
  - 添加VoiceInviteInfo模型
  - 添加访客加入API
- [x] VoiceViewModel 完善
  - 实际连接LiveKit Room
  - 音频静音/取消静音
  - 扬声器静音/取消静音
  - 参与者列表管理
- [x] VoiceScreen 功能完善
  - 用户说话状态指示 (脉冲动画+边框)
  - 连接状态横幅 (重连中/错误)
  - 参与者网格显示
- [x] VoiceCallService 完善
  - 前台服务通知
  - 通知控制按钮 (静音/挂断)
  - WakeLock保持 (10小时)
  - 静音状态同步更新通知
- [x] 音频权限处理
  - RECORD_AUDIO权限请求
  - 权限拒绝对话框提示
- [x] VoiceInviteScreen
  - VoiceInviteViewModel (邀请状态管理)
  - VoiceInviteScreen (访客加入界面)
  - Deep Link处理 (rmsdiscord://voice-invite/{token})
  - 邀请确认界面 (用户名输入+加入)

### ✅ Phase 4: 音乐面板 (已完成)

- [x] MusicViewModel (ui/music/MusicViewModel.kt)
  - QQ音乐登录状态管理
  - 音乐搜索功能
  - 播放队列管理
  - 播放控制 (播放/暂停/跳过/进度条)
  - 机器人状态管理
- [x] MusicBottomSheet (ui/music/MusicBottomSheet.kt)
  - 当前播放信息 (封面/歌名/歌手)
  - 播放控制按钮
  - 进度条 (支持拖动跳转)
  - 播放队列列表
  - QQ VIP登录/机器人状态显示
- [x] MusicSearchDialog (ui/music/MusicSearchDialog.kt)
  - 搜索界面
  - 搜索结果列表
  - 添加到队列
- [x] MusicLoginDialog (ui/music/MusicLoginDialog.kt)
  - QQ音乐二维码登录
  - 登录状态实时显示
  - 二维码刷新
- [x] 集成到VoiceScreen
  - FAB打开音乐面板 (语音连接后显示)
  - ModalBottomSheet展示音乐面板

### ✅ Phase 5: 测试 + 优化 + 发布 (已完成)

- [x] 代码优化
  - 修复deprecation警告 (AutoMirrored icons)
  - kotlinOptions迁移到kotlin.compilerOptions
  - 移除废弃的Window API调用
- [x] UI/UX优化
  - 通用加载状态组件 (LoadingContent with pulse animation)
  - 通用错误状态组件 (ErrorContent with retry)
  - 通用空状态组件 (EmptyContent)
  - 网络错误组件 (NetworkErrorContent)
  - Shimmer骨架屏组件 (ShimmerBox)
- [x] 性能优化
  - Coil图片加载优化 (内存/磁盘缓存配置)
  - LazyColumn已使用key参数优化
  - ImageLoaderFactory全局配置
- [x] 测试
  - 单元测试 (AuthViewModelTest, MainViewModelTest)
  - UI测试 (LoginScreenTest, CommonComponentsTest)
  - 测试依赖配置 (JUnit, MockK, Turbine, Compose Testing)
- [x] 发布准备
  - 签名配置 (release.keystore)
  - ProGuard规则完善 (Retrofit/OkHttp/Gson/LiveKit/Room/Hilt/Compose/Coroutines)
  - 版本号: 1.0.0 (versionCode: 1)
  - Release APK构建成功

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
| `components/ChatArea.vue` | `ui/chat/ChatScreen.kt` | ✅ |
| `components/VoicePanel.vue` | `ui/voice/VoiceScreen.kt` | ✅ |
| `views/VoiceInvite.vue` | `ui/voice/VoiceInviteScreen.kt` | ✅ |
| `components/VoiceControls.vue` | 集成在 VoiceScreen | ✅ |
| `components/MusicPanel.vue` | `ui/music/MusicBottomSheet.kt` | ✅ |
| `stores/auth.ts` | `data/repository/AuthRepository.kt` | ✅ |
| `stores/chat.ts` | `data/repository/ChatRepository.kt` | ✅ |
| `stores/voice.ts` | `data/repository/VoiceRepository.kt` + `data/livekit/LiveKitManager.kt` | ✅ |
| `stores/music.ts` | `ui/music/MusicViewModel.kt` | ✅ |
| `composables/useWebSocket.ts` | `data/websocket/ChatWebSocket.kt` | ✅ |
