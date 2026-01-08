# 语音识别服务集成说明

## 概述

已将语音识别服务集成到 RMS ChatRoom 后端系统中，提供实时语音转文字功能。

## 架构说明

### 服务组件
- **主后端服务** (FastAPI): `http://localhost:8000`
  - 处理用户认证、频道管理、消息等核心功能
  - 提供语音识别管理API接口
  
- **语音识别服务** (Flask): `http://localhost:8001`
  - 集成阿里云语音识别API
  - 管理WebRTC Bot实例
  - 处理音频流和回调

### 端口分配
- `8000`: 主后端服务 (FastAPI)
- `8001`: 语音识别服务 (Flask)  
- `9000-9999`: 语音识别回调服务器端口池

## 启动方式

### 方式1: 同时启动两个服务
```bash
cd backend
python -m backend --with-voice
```

### 方式2: 分别启动
```bash
# 终端1: 启动主后端
cd backend
python -m backend

# 终端2: 启动语音识别服务
cd backend  
python manage_voice.py start
```

### 方式3: 使用启动脚本
```bash
cd backend
python start_services.py
```

## API 接口

### 语音识别管理接口
基于主后端服务，需要用户认证和权限：

- `POST /api/voice-recognition/sessions` - 创建语音识别会话 (需要管理员权限)
- `GET /api/voice-recognition/sessions` - 获取所有会话列表 (需要管理员权限)
- `GET /api/voice-recognition/sessions/{id}` - 获取会话详情
- `GET /api/voice-recognition/sessions/{id}/results` - 获取识别结果
- `POST /api/voice-recognition/sessions/{id}/speakers` - 管理说话人状态 (需要管理员权限)
- `DELETE /api/voice-recognition/sessions/{id}` - 停止会话 (需要管理员权限)
- `GET /api/voice-recognition/status` - 获取语音服务状态 (需要管理员权限)
- `GET /api/voice-recognition/health` - 健康检查

### 直接语音服务接口 
基于语音识别服务，无认证：

- `POST /api/sessions` - 创建会话
- `GET /api/sessions` - 获取会话列表
- `GET /api/sessions/{id}` - 获取会话详情
- `GET /api/sessions/{id}/results` - 获取结果
- `POST /api/sessions/{id}/speakers` - 管理说话人
- `DELETE /api/sessions/{id}` - 停止会话
- `GET /api/status` - 系统状态
- `GET /api/health` - 健康检查

## 配置说明

### 主配置文件 (backend/config.json)
```json
{
  "voice_server_url": "http://localhost:8001",
  // ... 其他配置
}
```

### 环境变量
- `VOICE_SERVER_URL`: 语音识别服务URL
- 其他配置可通过环境变量覆盖

## 使用示例

### 创建语音识别会话
```bash
curl -X POST "http://localhost:8000/api/voice-recognition/sessions" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "room_config": {
      "room_id": "server_123_channel_456",
      "type": "discord", 
      "name": "语音频道",
      "server_id": 123,
      "channel_id": 456
    },
    "voice_config": {
      "language": "zh-CN",
      "enable_speaker_diarization": true,
      "sample_rate": 16000
    }
  }'
```

### 获取识别结果
```bash
curl "http://localhost:8000/api/voice-recognition/sessions/{session_id}/results" \
  -H "Authorization: Bearer <token>"
```

## 健康检查

```bash
# 检查主后端
curl http://localhost:8000/health

# 检查语音识别服务
curl http://localhost:8001/api/health

# 或使用管理脚本
python manage_voice.py health
```

## 权限要求

- **管理员权限 (level >= 2)**: 创建、停止会话，管理说话人，查看系统状态
- **普通用户权限 (level >= 1)**: 查看会话详情和结果

## 注意事项

1. 语音识别服务需要阿里云账号和API密钥配置
2. WebRTC Bot功能目前为模拟实现，实际使用需要根据具体平台实现
3. 回调服务器使用动态端口分配，确保9000-9999端口段可用
4. 生产环境建议使用进程管理器 (如supervisor) 管理服务进程
5. 建议配置反向代理 (如Nginx) 进行负载均衡和SSL终端

## 故障排除

1. **语音服务启动失败**: 检查端口8001是否被占用
2. **回调失败**: 检查9000-9999端口段是否可用
3. **API调用失败**: 确认用户权限和认证token
4. **连接语音服务失败**: 确认 `voice_server_url` 配置正确
