"""
语音识别路由 - FastAPI 集成
处理语音识别相关的API请求
包含LiveKit SDK集成、Bot Token生成、用户认证等完整功能
集成阿里云语音识别服务和实时转录功能
"""

import asyncio
import uuid
import requests
from fastapi import APIRouter, HTTPException, Depends
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field
from typing import Dict, List, Optional, Any
from datetime import datetime
import logging
import json

from .deps import get_current_user, require_permission, CurrentUser
from ..services.voice_recognition import voice_service
from ..core.config import get_settings

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/voice-recognition", tags=["voice-recognition"])


class VoiceSessionCreate(BaseModel):
    """创建语音识别会话的请求模型"""
    room_config: Dict[str, Any] = Field(..., description="房间配置")
    voice_config: Dict[str, Any] = Field(default_factory=dict, description="语音识别配置")
    
    class Config:
        json_schema_extra = {
            "example": {
                "room_config": {
                    "room_id": "server_123_channel_456",
                    "type": "livekit",
                    "name": "语音频道",
                    "server_id": 123,
                    "channel_id": 456,
                    "livekit_room_name": "voice_456"
                },
                "voice_config": {
                    "language": "zh-CN",
                    "enable_speaker_diarization": True,
                    "sample_rate": 16000
                }
            }
        }


class LiveKitBotTokenRequest(BaseModel):
    """LiveKit Bot Token请求模型"""
    room_name: str = Field(..., description="房间名称")
    bot_identity: Optional[str] = Field(default=None, description="Bot标识符")
    bot_name: str = Field(default="VoiceBot", description="Bot显示名称")


class LiveKitBotTokenResponse(BaseModel):
    """LiveKit Bot Token响应模型"""
    success: bool
    token: Optional[str] = None
    room_name: Optional[str] = None
    bot_identity: Optional[str] = None
    expires_at: Optional[str] = None
    message: Optional[str] = None


class SpeakerAction(BaseModel):
    """说话人状态管理的请求模型"""
    action: str = Field(..., description="操作类型: start 或 stop")
    speaker_id: str = Field(..., description="说话人ID")
    timestamp_ms: Optional[int] = Field(None, description="时间戳（毫秒）")


class VoiceSessionResponse(BaseModel):
    """语音会话响应模型"""
    success: bool
    session_id: Optional[str] = None
    room_id: Optional[str] = None
    status: Optional[str] = None
    message: Optional[str] = None


class AudioStreamPushRequest(BaseModel):
    """音频流推送请求模型"""
    session_id: str = Field(..., description="会话ID")
    stream_index: int = Field(default=0, description="音频流索引")
    audio_data: str = Field(..., description="base64编码的音频数据")
    speaker_id: Optional[str] = Field(None, description="说话人ID")


class RealTimeTranscriptionRequest(BaseModel):
    """实时转录请求模型"""
    callback_url: str = Field(..., description="转录结果回调URL")
    room_id: str = Field(..., description="房间ID")
    livekit_room_name: Optional[str] = Field(None, description="LiveKit房间名称")
    voice_config: Dict[str, Any] = Field(default_factory=dict, description="语音识别配置")
    
    class Config:
        json_schema_extra = {
            "example": {
                "callback_url": "http://localhost:8000/api/voice-recognition/callback",
                "room_id": "voice_123",
                "livekit_room_name": "voice_123",
                "voice_config": {
                    "language": "zh-CN",
                    "sample_rate": 16000,
                    "enable_speaker_diarization": True
                }
            }
        }


class StreamSentencesRequest(BaseModel):
    """流式获取句子请求模型"""
    session_id: str = Field(..., description="会话ID")
    speaker_id: Optional[str] = Field(None, description="指定说话人ID")
    include_unassigned: bool = Field(True, description="是否包含未分配说话人的句子")
    last_timestamp: Optional[int] = Field(None, description="上次获取的时间戳")


@router.post("/sessions", response_model=VoiceSessionResponse)
async def create_voice_session(
    session_data: VoiceSessionCreate,
    user: CurrentUser,
    _: None = Depends(require_permission(2))  # 需要管理员权限
):
    """创建语音识别会话"""
    try:
        # 添加用户信息到房间配置
        room_config = session_data.room_config.copy()
        room_config["created_by"] = user.get("id")
        room_config["created_by_name"] = user.get("nickname") or user.get("username")
        
        # 调用语音服务
        result = await voice_service.create_session(room_config, session_data.voice_config)
        
        if not result.get('success'):
            error_msg = result.get('error', '创建语音识别会话失败')
            
            # 如果是服务繁忙，返回409状态码
            if 'busy' in error_msg or '正在被' in error_msg or '已有活跃' in error_msg:
                raise HTTPException(
                    status_code=409, 
                    detail={
                        'message': error_msg,
                        'busy_room_id': result.get('busy_room_id'),
                        'existing_session_id': result.get('existing_session_id'),
                        'error_type': 'service_busy'
                    }
                )
            else:
                raise HTTPException(status_code=500, detail=error_msg)
        
        logger.info(f"Created voice session {result.get('session_id')} for user {user.get('id')}")
        
        return VoiceSessionResponse(**result)
        
    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"Error creating voice session: {e}")
        raise HTTPException(status_code=500, detail="创建语音识别会话失败")


@router.get("/sessions")
async def list_voice_sessions(
    user: CurrentUser,
_: None = Depends(require_permission(2))):
    """获取所有语音识别会话"""
    try:
        result = await voice_service.get_sessions()
        
        if not result.get('success'):
            raise HTTPException(status_code=500, detail=result.get('error', '获取会话列表失败'))
            
        return result
        
    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"Error listing voice sessions: {e}")
        raise HTTPException(status_code=500, detail="获取会话列表失败")


@router.get("/sessions/{session_id}")
async def get_voice_session(
    session_id: str,
    user: CurrentUser,  _: None = Depends(require_permission(1))# 普通用户也可查看
):
    """获取语音识别会话详情"""
    try:
        result = await voice_service.get_session_detail(session_id)
        
        if not result.get('success'):
            if 'not found' in result.get('error', '').lower():
                raise HTTPException(status_code=404, detail="会话不存在")
            raise HTTPException(status_code=500, detail=result.get('error', '获取会话详情失败'))
            
        return result
        
    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"Error getting voice session: {e}")
        raise HTTPException(status_code=500, detail="获取会话详情失败")


@router.get("/sessions/{session_id}/results")
async def get_voice_session_results(
    session_id: str,
    user: CurrentUser,
    page: int = 1,
    per_page: int = 50,
    _: None = Depends(require_permission(1))
):
    """获取语音识别会话结果"""
    try:
        result = await voice_service.get_session_results(session_id, page, per_page)
        
        if not result.get('success'):
            if 'not found' in result.get('error', '').lower():
                raise HTTPException(status_code=404, detail="会话不存在")
            raise HTTPException(status_code=500, detail=result.get('error', '获取识别结果失败'))
            
        return result
        
    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"Error getting voice session results: {e}")
        raise HTTPException(status_code=500, detail="获取识别结果失败")


@router.post("/sessions/{session_id}/speakers")
async def manage_voice_speaker(
    session_id: str,
    speaker_action: SpeakerAction,
    user: CurrentUser,  _: None = Depends(require_permission(2))# 需要管理员权限
):
    """管理说话人状态"""
    try:
        result = await voice_service.manage_speaker(
            session_id,
            speaker_action.action,
            speaker_action.speaker_id,
            speaker_action.timestamp_ms
        )
        
        if not result.get('success'):
            if 'not found' in result.get('error', '').lower():
                raise HTTPException(status_code=404, detail="会话不存在")
            raise HTTPException(status_code=500, detail=result.get('error', '管理说话人失败'))
            
        return result
        
    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"Error managing speaker: {e}")
        raise HTTPException(status_code=500, detail="管理说话人失败")


@router.delete("/sessions/{session_id}")
async def stop_voice_session(
    session_id: str,
    user: CurrentUser,  _: None = Depends(require_permission(2))# 需要管理员权限
):
    """停止语音识别会话"""
    try:
        result = await voice_service.stop_session(session_id)
        
        if not result.get('success'):
            if 'not found' in result.get('error', '').lower():
                raise HTTPException(status_code=404, detail="会话不存在")
            raise HTTPException(status_code=500, detail=result.get('error', '停止会话失败'))
            
        logger.info(f"Stopped voice session {session_id} by user {user.get('id')}")
        return result
        
    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"Error stopping voice session: {e}")
        raise HTTPException(status_code=500, detail="停止会话失败")


@router.get("/status")
async def get_voice_service_status(
    user: CurrentUser,
_: None = Depends(require_permission(2))):
    """获取语音服务器状态"""
    try:
        result = await voice_service.get_system_status()
        
        if not result.get('success'):
            raise HTTPException(status_code=500, detail=result.get('error', '获取语音服务器状态失败'))
            
        return result
        
    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"Error getting voice service status: {e}")
        raise HTTPException(status_code=500, detail="获取语音服务器状态失败")


@router.get("/health")
async def voice_service_health():
    """语音服务健康检查"""
    try:
        return {
            "status": "healthy",
            "voice_service": "integrated",
            "timestamp": datetime.now().isoformat()
        }
            
    except Exception as e:
        logger.warning(f"Voice service health check failed: {e}")
        return JSONResponse(
            status_code=503,
            content={
                "status": "unhealthy",
                "error": str(e),
                "timestamp": datetime.now().isoformat()
            }
        )


@router.get("/availability")
async def check_voice_service_availability(
    user: CurrentUser,
    room_id: Optional[str] = None,
    _: None = Depends(require_permission(1))
):
    """检查语音服务可用性"""
    try:
        status_result = await voice_service.get_system_status()
        
        if not status_result.get('success'):
            raise HTTPException(status_code=500, detail=status_result.get('error', '获取服务状态失败'))
        
        global_lock = status_result.get('global_lock', {})
        is_locked = global_lock.get('is_locked', False)
        active_room = global_lock.get('active_room_id')
        
        # 如果服务未被占用，或者被查询的房间占用，则可用
        available = not is_locked or (room_id and active_room == room_id)
        
        return {
            "success": True,
            "available": available,
            "is_locked": is_locked,
            "active_room_id": active_room,
            "message": global_lock.get('message', ''),
            "can_create_session": available,
            "stats": status_result.get('stats', {})
        }
        
    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"Error checking voice service availability: {e}")
        raise HTTPException(status_code=500, detail="检查服务可用性失败")


@router.post("/livekit/bot-token", response_model=LiveKitBotTokenResponse)
async def generate_livekit_bot_token(
    request: LiveKitBotTokenRequest,
    user: CurrentUser,  _: None = Depends(require_permission(2))# 需要管理员权限
):
    """生成LiveKit Bot访问令牌"""
    try:
        from ..websocket.transcription import LiveKitBotTokenGenerator, LIVEKIT_AVAILABLE
        
        if not LIVEKIT_AVAILABLE:
            raise HTTPException(status_code=503, detail="LiveKit SDK 不可用")
            
        # 生成Bot身份标识
        bot_identity = request.bot_identity or f"voice-bot-{uuid.uuid4().hex[:8]}"
        
        # 创建token生成器
        token_generator = LiveKitBotTokenGenerator()
        
        # 生成token
        token = token_generator.generate_bot_token(
            room_name=request.room_name,
            bot_identity=bot_identity,
            bot_name=request.bot_name
        )
        
        logger.info(f"Generated LiveKit bot token for room {request.room_name}, bot {bot_identity}")
        
        return LiveKitBotTokenResponse(
            success=True,
            token=token,
            room_name=request.room_name,
            bot_identity=bot_identity,
            message="Bot token generated successfully"
        )
        
    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"Error generating LiveKit bot token: {e}")
        raise HTTPException(status_code=500, detail="生成Bot token失败")


@router.get("/livekit/status")
async def get_livekit_status(
    user: CurrentUser,
_: None = Depends(require_permission(2))):
    """获取LiveKit服务状态"""
    try:
        from ..websocket.transcription import LIVEKIT_AVAILABLE
        from ..core.config import get_settings
        
        settings = get_settings()
        
        return {
            "success": True,
            "livekit_available": LIVEKIT_AVAILABLE,
            "livekit_host": settings.livekit_host if hasattr(settings, 'livekit_host') else None,
            "livekit_configured": bool(
                getattr(settings, 'livekit_api_key', None) and 
                getattr(settings, 'livekit_api_secret', None)
            ),
            "timestamp": datetime.now().isoformat()
        }
        
    except Exception as e:
        logger.exception(f"Error getting LiveKit status: {e}")
        raise HTTPException(status_code=500, detail="获取LiveKit状态失败")


@router.post("/test/callback-server")
async def test_callback_server(
    user: CurrentUser,  _: None = Depends(require_permission(2))# 需要管理员权限
):
    """测试回调服务器功能（用于开发调试）"""
    try:
        from ..websocket.transcription import CallbackServer
        import uuid
        
        # 创建测试回调服务器
        test_port = 9000 + (hash(str(uuid.uuid4())) % 1000)
        callback_server = CallbackServer(host='localhost', port=test_port)
        
        # 添加测试处理器
        test_results = []
        def test_handler(result):
            test_results.append(result)
            logger.info(f"Test callback received: {result}")
        
        callback_server.add_result_handler(test_handler)
        
        # 启动服务器
        callback_server.start()
        
        # 等待启动
        await asyncio.sleep(1)
        
        # 获取状态
        stats = callback_server.get_stats()
        
        # 停止服务器
        callback_server.stop()
        
        return {
            "success": True,
            "message": "回调服务器测试完成",
            "stats": stats,
            "test_results_count": len(test_results)
        }
        
    except Exception as e:
        logger.exception(f"Error testing callback server: {e}")
        raise HTTPException(status_code=500, detail="测试回调服务器失败")


@router.post("/realtime-transcription/start")
async def start_realtime_transcription(
    request: RealTimeTranscriptionRequest,
    user: CurrentUser,
    _: None = Depends(require_permission(2))  # 需要管理员权限
):
    """启动实时语音转录"""
    try:
        # 获取设置
        settings = get_settings()
        
        # 调用独立语音服务启动转录
        voice_service_url = getattr(settings, 'voice_service_url', 'http://localhost:5000')
        
        # 构建请求数据
        transcription_data = {
            "callback_url": request.callback_url,
            "audio_tracks": ["stream://realtime"],  # 实时音频流
            "room_config": {
                "room_id": request.room_id,
                "livekit_room_name": request.livekit_room_name,
                "created_by": user.get("id"),
                "created_by_name": user.get("nickname") or user.get("username")
            },
            "voice_config": request.voice_config
        }
        
        # 发送到语音服务
        response = requests.post(
            f"{voice_service_url}/trainsction",
            json=transcription_data,
            timeout=30
        )
        
        if response.status_code == 202:
            result = response.json()
            
            # 创建本地会话记录
            session_result = await voice_service.create_session(
                transcription_data["room_config"],
                request.voice_config
            )
            
            return {
                "success": True,
                "message": "实时转录已启动",
                "session_id": session_result.get("session_id"),
                "room_id": request.room_id,
                "status": "started"
            }
        else:
            error_msg = response.json().get('error', '启动转录失败')
            raise HTTPException(status_code=500, detail=error_msg)
            
    except requests.RequestException as e:
        logger.exception(f"Error connecting to voice service: {e}")
        raise HTTPException(status_code=503, detail="语音服务不可用")
    except Exception as e:
        logger.exception(f"Error starting realtime transcription: {e}")
        raise HTTPException(status_code=500, detail="启动实时转录失败")


@router.post("/audio/push")
async def push_audio_data(
    request: AudioStreamPushRequest,
    user: CurrentUser,
    _: None = Depends(require_permission(1))
):
    """向指定会话推送音频数据"""
    try:
        settings = get_settings()
        voice_service_url = getattr(settings, 'voice_service_url', 'http://localhost:5000')
        
        # 转发到独立语音服务
        response = requests.post(
            f"{voice_service_url}/streams/push",
            json={
                "session_id": request.session_id,
                "stream_index": request.stream_index,
                "audio_data": request.audio_data
            },
            timeout=10
        )
        
        if response.status_code == 200:
            result = response.json()
            
            # 如果提供了说话人ID，同时注册说话人开始事件
            if request.speaker_id:
                try:
                    speaker_response = requests.post(
                        f"{voice_service_url}/speakerstart",
                        json={
                            "session_id": request.session_id,
                            "speaker_id": request.speaker_id,
                            "timestamp_ms": int(datetime.now().timestamp() * 1000)
                        },
                        timeout=5
                    )
                except Exception as e:
                    logger.warning(f"Failed to register speaker start: {e}")
            
            return result
        else:
            error_msg = response.json().get('error', '推送音频数据失败')
            raise HTTPException(status_code=response.status_code, detail=error_msg)
            
    except requests.RequestException as e:
        logger.exception(f"Error connecting to voice service: {e}")
        raise HTTPException(status_code=503, detail="语音服务不可用")
    except Exception as e:
        logger.exception(f"Error pushing audio data: {e}")
        raise HTTPException(status_code=500, detail="推送音频数据失败")


@router.get("/sentences/stream")
async def stream_sentences(
    session_id: str,
    user: CurrentUser,
    speaker_id: Optional[str] = None,
    include_unassigned: bool = True,
    last_timestamp: Optional[int] = None,
    _: None = Depends(require_permission(1))
):
    """流式获取转录句子"""
    try:
        settings = get_settings()
        voice_service_url = getattr(settings, 'voice_service_url', 'http://localhost:5000')
        
        # 构建查询参数
        params = {
            "session_id": session_id,
            "include_unassigned": str(include_unassigned).lower()
        }
        if speaker_id:
            params["speaker_id"] = speaker_id
        if last_timestamp:
            params["last_timestamp"] = str(last_timestamp)
        
        # 从独立语音服务获取句子
        response = requests.get(
            f"{voice_service_url}/sentences",
            params=params,
            timeout=10
        )
        
        if response.status_code == 200:
            return response.json()
        else:
            error_msg = response.json().get('error', '获取句子失败')
            raise HTTPException(status_code=response.status_code, detail=error_msg)
            
    except requests.RequestException as e:
        logger.exception(f"Error connecting to voice service: {e}")
        raise HTTPException(status_code=503, detail="语音服务不可用")
    except Exception as e:
        logger.exception(f"Error getting sentences: {e}")
        raise HTTPException(status_code=500, detail="获取句子失败")


@router.get("/sentences/sse")
async def sentences_server_sent_events(
    session_id: str,
    user: CurrentUser,
    speaker_id: Optional[str] = None,
    include_unassigned: bool = True,
    _: None = Depends(require_permission(1))
):
    """Server-Sent Events 实时推送转录句子"""
    async def event_generator():
        try:
            settings = get_settings()
            voice_service_url = getattr(settings, 'voice_service_url', 'http://localhost:5000')
            last_timestamp = 0
            
            while True:
                try:
                    # 获取新句子
                    params = {
                        "session_id": session_id,
                        "include_unassigned": str(include_unassigned).lower(),
                        "last_timestamp": str(last_timestamp)
                    }
                    if speaker_id:
                        params["speaker_id"] = speaker_id
                    
                    response = requests.get(
                        f"{voice_service_url}/sentences",
                        params=params,
                        timeout=5
                    )
                    
                    if response.status_code == 200:
                        data = response.json()
                        sentences = data.get("sentences", [])
                        
                        # 发送新句子
                        for sentence in sentences:
                            sentence_timestamp = sentence.get("timestamp_ms", 0)
                            if sentence_timestamp > last_timestamp:
                                yield f"data: {json.dumps(sentence, ensure_ascii=False)}\n\n"
                                last_timestamp = max(last_timestamp, sentence_timestamp)
                        
                        # 发送心跳
                        if not sentences:
                            yield f"data: {json.dumps({'type': 'heartbeat', 'timestamp': int(datetime.now().timestamp() * 1000)}, ensure_ascii=False)}\n\n"
                    
                    # 等待一段时间再次查询
                    await asyncio.sleep(1)
                    
                except Exception as e:
                    logger.exception(f"Error in SSE stream: {e}")
                    yield f"data: {json.dumps({'type': 'error', 'message': str(e)}, ensure_ascii=False)}\n\n"
                    break
                    
        except Exception as e:
            logger.exception(f"SSE generator error: {e}")
            yield f"data: {json.dumps({'type': 'error', 'message': 'Stream ended'}, ensure_ascii=False)}\n\n"
    
    return StreamingResponse(event_generator(), media_type="text/event-stream")


@router.post("/callback")
async def transcription_callback(request: dict):
    """接收语音转录结果的回调接口"""
    try:
        logger.info(f"Received transcription callback: {json.dumps(request, ensure_ascii=False)}")
        
        # 处理转录结果
        session_id = request.get("session_id")
        task_type = request.get("task", "unknown")
        
        if session_id:
            # 更新本地会话状态
            await voice_service.update_session_results(session_id, request)
            
            # 通过WebSocket广播结果给前端
            try:
                from ..websocket.transcription import transcription_manager
                await transcription_manager.broadcast_transcription_result(session_id, request)
            except ImportError:
                logger.warning("Transcription manager not available for broadcasting")
        
        return {"status": "ok", "received": True}
        
    except Exception as e:
        logger.exception(f"Error processing transcription callback: {e}")
        raise HTTPException(status_code=500, detail="处理回调失败")
