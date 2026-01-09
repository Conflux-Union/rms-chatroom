# 文字频道消息管理功能 - 待办事项

## 已完成 ✅

### 后端实现
- [x] 修改Message表结构，添加is_deleted、deleted_at、deleted_by、edited_at字段
- [x] 创建MuteRecord表和MuteScope枚举
- [x] 创建backend/services/moderation.py禁言检查服务
- [x] 创建backend/routers/moderation.py禁言管理API
- [x] 在messages.py添加消息撤回DELETE端点
- [x] 在messages.py添加消息编辑PATCH端点
- [x] 在messages.py更新MessageResponse模型和查询过滤
- [x] 在chat.py的WebSocket添加禁言检查
- [x] 在app.py注册moderation路由
- [x] 数据库初始化测试通过

### 前端类型定义
- [x] 更新types/index.ts的Message接口（添加is_deleted、deleted_by、deleted_by_username、edited_at）
- [x] 添加MuteRecord接口到types/index.ts

---

## 待实现 🚧

### 前端 Web/Electron (packages/shared)

#### 1. ChatArea.vue - 消息右键菜单
**文件**: `packages/shared/src/components/ChatArea.vue`

- [ ] 添加右键菜单HTML结构
  - [ ] 编辑消息选项（仅自己的消息）
  - [ ] 撤回消息选项（自己2分钟内 或 管理员无限制）
  - [ ] 禁言用户选项（仅管理员，且不能禁言自己）
- [ ] 实现右键菜单逻辑
  - [ ] `showContextMenu()` - 显示菜单
  - [ ] `canEdit()` - 判断是否可编辑
  - [ ] `canDelete()` - 判断是否可撤回（含2分钟时间判断）
  - [ ] `isOwnMessage()` - 判断是否自己的消息
- [ ] 点击外部关闭菜单

#### 2. ChatArea.vue - 消息编辑功能
**文件**: `packages/shared/src/components/ChatArea.vue`

- [ ] 添加编辑模式UI
  - [ ] textarea输入框
  - [ ] 保存/取消按钮
  - [ ] 支持Enter保存、Esc取消
- [ ] 实现编辑逻辑
  - [ ] `startEdit()` - 进入编辑模式
  - [ ] `saveEdit()` - 保存编辑（调用PATCH API）
  - [ ] `cancelEdit()` - 取消编辑
- [ ] 显示"(已编辑)"标记
  - [ ] 鼠标悬停显示编辑时间

#### 3. ChatArea.vue - 消息撤回功能
**文件**: `packages/shared/src/components/ChatArea.vue`

- [ ] 实现撤回逻辑
  - [ ] `deleteMessage()` - 调用DELETE API
  - [ ] 确认对话框
- [ ] 显示已撤回消息占位符
  - [ ] "你撤回了一条消息"
  - [ ] "XXX撤回了一条消息"
  - [ ] "管理员撤回了一条消息"

#### 4. ChatArea.vue - 禁言对话框
**文件**: `packages/shared/src/components/ChatArea.vue`

- [ ] 添加禁言对话框HTML
  - [ ] 范围选择（全局/服务器/频道）
  - [ ] 时长选择（永久/10分钟/1小时/1天/自定义）
  - [ ] 原因输入框（可选）
  - [ ] 确认/取消按钮
- [ ] 实现禁言逻辑
  - [ ] `showMuteDialog()` - 显示对话框
  - [ ] `confirmMute()` - 调用POST /api/mute
  - [ ] 根据scope自动填充server_id或channel_id

#### 5. ChatArea.vue - 禁言状态处理
**文件**: `packages/shared/src/components/ChatArea.vue`

- [ ] 检查用户禁言状态
  - [ ] `checkMuteStatus()` - 调用GET /api/mute/user/{user_id}
  - [ ] 频道切换时自动检查
- [ ] 禁用输入框
  - [ ] 输入框disabled状态
  - [ ] 占位符显示"你已被禁言"
  - [ ] 发送按钮禁用

#### 6. ChatArea.vue - WebSocket事件处理
**文件**: `packages/shared/src/components/ChatArea.vue`

- [ ] 处理message_deleted事件
  - [ ] 更新本地消息状态（is_deleted、deleted_by、deleted_by_username）
- [ ] 处理message_edited事件
  - [ ] 更新消息内容和edited_at
- [ ] 处理error事件（code: "muted"）
  - [ ] 显示禁言提示
  - [ ] 自动禁用输入框

#### 7. ChatArea.vue - CSS样式
**文件**: `packages/shared/src/components/ChatArea.vue`

- [ ] 右键菜单样式
- [ ] 编辑模式样式
- [ ] 已删除消息占位符样式
- [ ] 已编辑标记样式
- [ ] 禁言对话框样式（模态框）
- [ ] 禁言输入框样式

---

### Android端 (android/)

#### 1. 数据模型更新
**文件**: `android/app/src/main/java/com/rms/discord/data/model/Message.kt`

- [ ] 添加Message字段
  ```kotlin
  val isDeleted: Boolean = false
  val deletedBy: Int? = null
  val deletedByUsername: String? = null
  val editedAt: String? = null
  ```
- [ ] 创建MuteRecord数据类
  ```kotlin
  data class MuteRecord(
      val id: Int,
      val scope: String,  // "global" | "server" | "channel"
      val serverId: Int? = null,
      val channelId: Int? = null,
      val mutedUntil: String? = null,
      val reason: String? = null
  )
  ```

#### 2. API接口定义
**文件**: `android/app/src/main/java/com/rms/discord/data/api/ApiService.kt`

- [ ] 添加消息管理API
  ```kotlin
  @PATCH("api/channels/{channelId}/messages/{messageId}")
  suspend fun editMessage(@Path("channelId") channelId: Int, @Path("messageId") messageId: Int, @Body content: MessageEditRequest): Message

  @DELETE("api/channels/{channelId}/messages/{messageId}")
  suspend fun deleteMessage(@Path("channelId") channelId: Int, @Path("messageId") messageId: Int)
  ```
- [ ] 添加禁言管理API
  ```kotlin
  @POST("api/mute")
  suspend fun createMute(@Body request: MuteCreateRequest): MuteResponse

  @DELETE("api/mute/{muteId}")
  suspend fun removeMute(@Path("muteId") muteId: Int)

  @GET("api/mute/user/{userId}")
  suspend fun getUserMutes(@Path("userId") userId: Int): List<MuteRecord>
  ```

#### 3. WebSocket事件处理
**文件**: `android/app/src/main/java/com/rms/discord/data/websocket/ChatWebSocketManager.kt`

- [ ] 处理message_deleted事件
  ```kotlin
  "message_deleted" -> {
      val messageId = json.getInt("message_id")
      val deletedBy = json.getInt("deleted_by")
      val deletedByUsername = json.getString("deleted_by_username")
      // 更新本地消息状态
  }
  ```
- [ ] 处理message_edited事件
  ```kotlin
  "message_edited" -> {
      val messageId = json.getInt("message_id")
      val content = json.getString("content")
      val editedAt = json.getString("edited_at")
      // 更新消息内容
  }
  ```
- [ ] 处理error事件（muted）
  ```kotlin
  "error" -> {
      if (json.getString("code") == "muted") {
          val message = json.getString("message")
          // 显示禁言提示，禁用输入框
      }
  }
  ```

#### 4. UI组件 - 消息长按菜单
**文件**: `android/app/src/main/java/com/rms/discord/ui/chat/ChatScreen.kt`

- [ ] 添加消息长按监听
  ```kotlin
  LazyColumn {
      items(messages) { message ->
          MessageItem(
              message = message,
              onLongClick = { showMessageMenu(message) }
          )
      }
  }
  ```
- [ ] 实现BottomSheet菜单
  - [ ] 编辑消息（仅自己的消息）
  - [ ] 撤回消息（自己2分钟内 或 管理员）
  - [ ] 禁言用户（仅管理员）

#### 5. UI组件 - 消息编辑对话框
**文件**: `android/app/src/main/java/com/rms/discord/ui/chat/EditMessageDialog.kt`

- [ ] 创建编辑对话框Composable
  ```kotlin
  @Composable
  fun EditMessageDialog(
      message: Message,
      onDismiss: () -> Unit,
      onConfirm: (String) -> Unit
  )
  ```
- [ ] TextField输入框
- [ ] 保存/取消按钮

#### 6. UI组件 - 禁言对话框
**文件**: `android/app/src/main/java/com/rms/discord/ui/chat/MuteUserDialog.kt`

- [ ] 创建禁言对话框Composable
  ```kotlin
  @Composable
  fun MuteUserDialog(
      userId: Int,
      username: String,
      currentServerId: Int?,
      currentChannelId: Int?,
      onDismiss: () -> Unit,
      onConfirm: (MuteCreateRequest) -> Unit
  )
  ```
- [ ] 范围选择（RadioButton）
- [ ] 时长选择（Dropdown）
- [ ] 原因输入框
- [ ] 确认/取消按钮

#### 7. UI显示 - 已撤回消息
**文件**: `android/app/src/main/java/com/rms/discord/ui/chat/MessageItem.kt`

- [ ] 判断is_deleted
  ```kotlin
  if (message.isDeleted) {
      Text(
          text = when {
              message.deletedBy == currentUserId -> "你撤回了一条消息"
              message.deletedByUsername != null -> "${message.deletedByUsername}撤回了一条消息"
              else -> "管理员撤回了一条消息"
          },
          style = MaterialTheme.typography.bodySmall,
          color = Color.Gray
      )
  } else {
      // 正常消息显示
  }
  ```

#### 8. UI显示 - 已编辑标记
**文件**: `android/app/src/main/java/com/rms/discord/ui/chat/MessageItem.kt`

- [ ] 显示"(已编辑)"标记
  ```kotlin
  Row {
      Text(text = message.content)
      if (message.editedAt != null) {
          Text(
              text = "(已编辑)",
              style = MaterialTheme.typography.bodySmall,
              color = Color.Gray
          )
      }
  }
  ```

#### 9. 禁言状态处理
**文件**: `android/app/src/main/java/com/rms/discord/ui/chat/ChatScreen.kt`

- [ ] 检查用户禁言状态
  ```kotlin
  LaunchedEffect(currentChannelId) {
      val mutes = apiService.getUserMutes(currentUserId)
      isMuted = mutes.any { mute ->
          mute.scope == "global" ||
          (mute.scope == "server" && mute.serverId == currentServerId) ||
          (mute.scope == "channel" && mute.channelId == currentChannelId)
      }
  }
  ```
- [ ] 禁用输入框
  ```kotlin
  TextField(
      value = messageInput,
      onValueChange = { messageInput = it },
      enabled = !isMuted,
      placeholder = { Text(if (isMuted) "你已被禁言" else "发送消息") }
  )
  ```

#### 10. ViewModel逻辑
**文件**: `android/app/src/main/java/com/rms/discord/ui/chat/ChatViewModel.kt`

- [ ] 添加消息编辑方法
  ```kotlin
  fun editMessage(channelId: Int, messageId: Int, content: String)
  ```
- [ ] 添加消息撤回方法
  ```kotlin
  fun deleteMessage(channelId: Int, messageId: Int)
  ```
- [ ] 添加禁言方法
  ```kotlin
  fun muteUser(request: MuteCreateRequest)
  ```
- [ ] 添加检查禁言状态方法
  ```kotlin
  fun checkMuteStatus(userId: Int)
  ```

---

## 测试清单 🧪

### 后端测试
- [ ] 消息撤回测试
  - [ ] 普通用户2分钟内撤回自己的消息 ✓
  - [ ] 普通用户2分钟后撤回自己的消息 ✗ (应返回403)
  - [ ] 管理员撤回任何消息 ✓
  - [ ] 撤回已删除的消息 ✗ (应返回400)

- [ ] 消息编辑测试
  - [ ] 用户编辑自己的消息 ✓
  - [ ] 用户编辑他人的消息 ✗ (应返回403)
  - [ ] 编辑已删除的消息 ✗ (应返回400)
  - [ ] 编辑为空内容 ✗ (应返回400)

- [ ] 禁言功能测试
  - [ ] 管理员创建全局禁言 ✓
  - [ ] 管理员创建服务器禁言 ✓
  - [ ] 管理员创建频道禁言 ✓
  - [ ] 普通用户创建禁言 ✗ (应返回403)
  - [ ] 被禁言用户发送消息 ✗ (应返回error)
  - [ ] 临时禁言过期后发送消息 ✓

### 前端Web/Electron测试
- [ ] UI交互测试
  - [ ] 右键菜单显示正确的选项
  - [ ] 编辑模式正常工作
  - [ ] 禁言对话框正常工作
  - [ ] 已删除消息显示占位提示

- [ ] 实时更新测试
  - [ ] 消息撤回后其他用户实时看到
  - [ ] 消息编辑后其他用户实时看到
  - [ ] 被禁言后输入框自动禁用

- [ ] 权限测试
  - [ ] 普通用户只能撤回/编辑自己的消息
  - [ ] 管理员可以撤回任何消息
  - [ ] 管理员可以看到"禁言用户"选项

### Android端测试
- [ ] UI交互测试
  - [ ] 长按消息显示菜单
  - [ ] 编辑对话框正常工作
  - [ ] 禁言对话框正常工作
  - [ ] 已删除消息显示占位提示

- [ ] 实时更新测试
  - [ ] WebSocket事件正确处理
  - [ ] 消息状态实时更新
  - [ ] 禁言状态实时生效

- [ ] 权限测试
  - [ ] 菜单选项根据权限显示
  - [ ] API调用权限验证

---

## 注意事项 ⚠️

1. **时间判断**: 所有时间判断必须在服务器端完成，客户端时间不可信
2. **权限验证**: WebSocket和REST API都要独立验证权限，防止绕过
3. **软删除**: 消息撤回使用软删除，保留记录用于审计
4. **附件处理**: 消息撤回后附件文件保留，避免其他地方引用丢失
5. **实时通知**: 所有操作通过WebSocket实时通知其他在线用户
6. **过期处理**: 临时禁言自动过期，查询时过滤已过期记录
7. **错误处理**: 所有API调用都要有错误处理和用户提示

---

## 关键文件清单 📁

### 后端（已完成）
- `backend/models/server.py` - Message表和MuteRecord表
- `backend/services/moderation.py` - 禁言检查服务
- `backend/routers/moderation.py` - 禁言管理API
- `backend/routers/messages.py` - 消息撤回/编辑API
- `backend/websocket/chat.py` - WebSocket禁言检查
- `backend/app.py` - 路由注册

### 前端Web/Electron（待实现）
- `packages/shared/src/types/index.ts` - 类型定义（已完成）
- `packages/shared/src/components/ChatArea.vue` - 主要UI实现

### Android端（待实现）
- `android/app/src/main/java/com/rms/discord/data/model/Message.kt`
- `android/app/src/main/java/com/rms/discord/data/model/MuteRecord.kt`
- `android/app/src/main/java/com/rms/discord/data/api/ApiService.kt`
- `android/app/src/main/java/com/rms/discord/data/websocket/ChatWebSocketManager.kt`
- `android/app/src/main/java/com/rms/discord/ui/chat/ChatScreen.kt`
- `android/app/src/main/java/com/rms/discord/ui/chat/ChatViewModel.kt`
- `android/app/src/main/java/com/rms/discord/ui/chat/EditMessageDialog.kt`
- `android/app/src/main/java/com/rms/discord/ui/chat/MuteUserDialog.kt`
- `android/app/src/main/java/com/rms/discord/ui/chat/MessageItem.kt`
