"""
语音识别路由 - FastAPI 集成
处理语音识别相关的API请求
"""

from fastapi import APIRouter, HTTPException, Depends, BackgroundTasks
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from typing import Dict, List, Optional, Any
from datetime import datetime
import logging
import uuid
import httpx
import asyncio

from ..routers.deps import get_current_user, require_permission
from ..models.server import User
from ..core.config import get_settings

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/voice-recognition", tags=["voice-recognition"])
settings = get_settings()

# 语音服务器配置
VOICE_SERVER_URL = getattr(settings, 'voice_server_url', 'http://localhost:8000')


class VoiceSessionCreate(BaseModel):
    """创建语音识别会话的请求模型"""
    room_config: Dict[str, Any] = Field(..., description="房间配置")
    voice_config: Dict[str, Any] = Field(default_factory=dict, description="语音识别配置")
    
    class Config:
        json_schema_extra = {
            "example": {
                "room_config": {
                    "room_id": "server_123_channel_456",
                    "type": "discord",
                    "name": "语音频道",
                    "server_id": 123,
                    "channel_id": 456
                },
                "voice_config": {
                    "language": "zh-CN",
                    "enable_speaker_diarization": True,
                    "sample_rate": 16000
                }
            }
        }


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


class VoiceHttpClient:
    """语音服务HTTP客户端"""
    
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip('/')
        self.timeout = httpx.Timeout(30.0)
        
    async def create_session(self, room_config: Dict, voice_config: Dict) -> Dict:
        """创建语音识别会话"""
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                f"{self.base_url}/api/sessions",
                json={
                    "room_config": room_config,
                    "voice_config": voice_config
                }
            )
            response.raise_for_status()
            return response.json()
    
    async def get_session(self, session_id: str) -> Dict:
        """获取会话详情"""
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(f"{self.base_url}/api/sessions/{session_id}")
            response.raise_for_status()
            return response.json()
    
    async def get_session_results(self, session_id: str, page: int = 1, per_page: int = 50) -> Dict:
        """获取会话识别结果"""
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(
                f"{self.base_url}/api/sessions/{session_id}/results",
                params={"page": page, "per_page": per_page}
            )
            response.raise_for_status()
            return response.json()
    
    async def manage_speaker(self, session_id: str, action: str, speaker_id: str, timestamp_ms: Optional[int] = None) -> Dict:
        """管理说话人状态"""
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                f"{self.base_url}/api/sessions/{session_id}/speakers",
                json={
                    "action": action,
                    "speaker_id": speaker_id,
                    "timestamp_ms": timestamp_ms
                }
            )
            response.raise_for_status()
            return response.json()
    
    async def stop_session(self, session_id: str) -> Dict:
        """停止语音识别会话"""
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.delete(f"{self.base_url}/api/sessions/{session_id}")
            response.raise_for_status()
            return response.json()
    
    async def list_sessions(self) -> Dict:
        """获取所有会话列表"""
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(f"{self.base_url}/api/sessions")
            response.raise_for_status()
            return response.json()
    
    async def get_status(self) -> Dict:
        """获取语音服务器状态"""
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(f"{self.base_url}/api/status")
            response.raise_for_status()
            return response.json()


# 全局语音客户端实例
voice_client = VoiceHttpClient(VOICE_SERVER_URL)


@router.post("/sessions", response_model=VoiceSessionResponse)
async def create_voice_session(
    session_data: VoiceSessionCreate,
    user: User = Depends(require_permission(2))  # 需要管理员权限
):
    """创建语音识别会话"""
    try:
        # 添加用户信息到房间配置
        room_config = session_data.room_config.copy()
        room_config["created_by"] = user.id
        room_config["created_by_name"] = user.nickname or user.username
        
        # 调用语音服务器
        result = await voice_client.create_session(room_config, session_data.voice_config)
        
        logger.info(f"Created voice session {result.get('session_id')} for user {user.id}")
        
        return VoiceSessionResponse(**result)
        
    except httpx.HTTPStatusError as e:
        logger.error(f"Voice server error: {e.response.text}")
        raise HTTPException(status_code=e.response.status_code, detail="语音服务器错误")
    except Exception as e:
        logger.exception(f"Error creating voice session: {e}")
        raise HTTPException(status_code=500, detail="创建语音识别会话失败")


@router.get("/sessions")
async def list_voice_sessions(
    user: User = Depends(require_permission(2))
):
    """获取所有语音识别会话"""
    try:
        result = await voice_client.list_sessions()
        return result
        
    except httpx.HTTPStatusError as e:
        logger.error(f"Voice server error: {e.response.text}")
        raise HTTPException(status_code=e.response.status_code, detail="语音服务器错误")
    except Exception as e:
        logger.exception(f"Error listing voice sessions: {e}")
        raise HTTPException(status_code=500, detail="获取会话列表失败")


@router.get("/sessions/{session_id}")
async def get_voice_session(
    session_id: str,
    user: User = Depends(require_permission(1))  # 普通用户也可查看
):
    """获取语音识别会话详情"""
    try:
        result = await voice_client.get_session(session_id)
        return result
        
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            raise HTTPException(status_code=404, detail="会话不存在")
        logger.error(f"Voice server error: {e.response.text}")
        raise HTTPException(status_code=e.response.status_code, detail="语音服务器错误")
    except Exception as e:
        logger.exception(f"Error getting voice session: {e}")
        raise HTTPException(status_code=500, detail="获取会话详情失败")


@router.get("/sessions/{session_id}/results")
async def get_voice_session_results(
    session_id: str,
    page: int = 1,
    per_page: int = 50,
    user: User = Depends(require_permission(1))
):
    """获取语音识别会话结果"""
    try:
        result = await voice_client.get_session_results(session_id, page, per_page)
        return result
        
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            raise HTTPException(status_code=404, detail="会话不存在")
        logger.error(f"Voice server error: {e.response.text}")
        raise HTTPException(status_code=e.response.status_code, detail="语音服务器错误")
    except Exception as e:
        logger.exception(f"Error getting voice session results: {e}")
        raise HTTPException(status_code=500, detail="获取识别结果失败")


@router.post("/sessions/{session_id}/speakers")
async def manage_voice_speaker(
    session_id: str,
    speaker_action: SpeakerAction,
    user: User = Depends(require_permission(2))  # 需要管理员权限
):
    """管理说话人状态"""
    try:
        result = await voice_client.manage_speaker(
            session_id,
            speaker_action.action,
            speaker_action.speaker_id,
            speaker_action.timestamp_ms
        )
        return result
        
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            raise HTTPException(status_code=404, detail="会话不存在")
        logger.error(f"Voice server error: {e.response.text}")
        raise HTTPException(status_code=e.response.status_code, detail="语音服务器错误")
    except Exception as e:
        logger.exception(f"Error managing speaker: {e}")
        raise HTTPException(status_code=500, detail="管理说话人失败")


@router.delete("/sessions/{session_id}")
async def stop_voice_session(
    session_id: str,
    user: User = Depends(require_permission(2))  # 需要管理员权限
):
    """停止语音识别会话"""
    try:
        result = await voice_client.stop_session(session_id)
        logger.info(f"Stopped voice session {session_id} by user {user.id}")
        return result
        
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            raise HTTPException(status_code=404, detail="会话不存在")
        logger.error(f"Voice server error: {e.response.text}")
        raise HTTPException(status_code=e.response.status_code, detail="语音服务器错误")
    except Exception as e:
        logger.exception(f"Error stopping voice session: {e}")
        raise HTTPException(status_code=500, detail="停止会话失败")


@router.get("/status")
async def get_voice_service_status(
    user: User = Depends(require_permission(2))
):
    """获取语音服务器状态"""
    try:
        result = await voice_client.get_status()
        return result
        
    except httpx.HTTPStatusError as e:
        logger.error(f"Voice server error: {e.response.text}")
        raise HTTPException(status_code=e.response.status_code, detail="语音服务器错误")
    except Exception as e:
        logger.exception(f"Error getting voice service status: {e}")
        raise HTTPException(status_code=500, detail="获取语音服务器状态失败")


@router.get("/health")
async def voice_service_health():
    """语音服务健康检查"""
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(5.0)) as client:
            response = await client.get(f"{VOICE_SERVER_URL}/api/health")
            response.raise_for_status()
            return {
                "status": "healthy",
                "voice_server": response.json(),
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
