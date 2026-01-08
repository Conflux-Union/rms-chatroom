#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
语音识别服务核心模块
集成到FastAPI主后端中
包含阿里云语音识别集成、实时转录、LiveKit Bot等完整功能
"""

import logging
import threading
import uuid
import time
import asyncio
import requests
from typing import Dict, List, Optional, Any
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor

# 导入现有的客户端
from ..websocket.transcription import AliVoiceClient, CallbackServer

from ..core.config import get_settings

logger = logging.getLogger(__name__)

# 全局锁 - 确保一次只有一个房间可以使用语音识别服务
_voice_service_lock = asyncio.Lock()
_current_active_room = None  # 当前使用服务的房间ID

# 全局存储
ACTIVE_SESSIONS: Dict[str, 'VoiceSession'] = {}
BOT_INSTANCES: Dict[str, 'WebRTCBot'] = {}
EXECUTOR = ThreadPoolExecutor(max_workers=10)


def get_voice_service_config():
    """获取语音服务配置"""
    settings = get_settings()
    return {
        "base_url": settings.voice_service_url,
        "callback_base_url": settings.voice_callback_base_url,
        "timeout": 30,
        "max_retries": 3
    }


class VoiceSession:
    """语音会话管理类"""
    
    def __init__(self, session_id: str, room_id: str, config: Dict[str, Any]):
        self.session_id = session_id
        self.room_id = room_id
        self.config = config
        self.created_at = datetime.now()
        self.status = "initializing"
        self.results = []
        self.speakers = {}
        self.last_activity = datetime.now()
        
        # 核心组件
        self.ali_client: Optional[AliVoiceClient] = None
        self.callback_server: Optional[CallbackServer] = None
        self.bot_instance: Optional['WebRTCBot'] = None
        
    def to_dict(self) -> Dict[str, Any]:
        return {
            'session_id': self.session_id,
            'room_id': self.room_id,
            'status': self.status,
            'created_at': self.created_at.isoformat(),
            'last_activity': self.last_activity.isoformat(),
            'speakers_count': len(self.speakers),
            'results_count': len(self.results)
        }


class WebRTCBot:
    """WebRTC Bot基类"""
    
    def __init__(self, session_id: str, room_config: Dict[str, Any]):
        self.session_id = session_id
        self.room_config = room_config
        self.room_id = room_config.get('room_id', 'unknown')
        self.bot_type = room_config.get('type', 'generic')
        self.connected = False
        self.audio_handler = None
        self._stop_event = threading.Event()
        
    def set_audio_handler(self, handler):
        self.audio_handler = handler
        
    def start_audio_capture(self):
        """启动音频捕获（子类实现具体逻辑）"""
        logger.info(f"Starting audio capture for bot {self.session_id}")
        self._start_mock_audio()
        
    def _start_mock_audio(self):
        """模拟音频流（用于测试）"""
        def audio_worker():
            import random
            import struct
            
            while not self._stop_event.is_set():
                # 生成16kHz 16位单声道PCM数据
                chunk_size = 1024
                audio_data = b''
                
                for _ in range(chunk_size):
                    sample = int(random.uniform(-1000, 1000))
                    audio_data += struct.pack('<h', sample)
                
                if self.audio_handler:
                    self.audio_handler.process_audio(audio_data, 'mock_speaker')
                
                time.sleep(0.064)  # ~16ms chunks
                
        thread = threading.Thread(target=audio_worker, daemon=True)
        thread.start()
        logger.info(f"Mock audio started for session {self.session_id}")
        
    def stop(self):
        self._stop_event.set()
        self.connected = False
        logger.info(f"Bot stopped for session {self.session_id}")


class AudioHandler:
    """音频处理器"""
    
    def __init__(self, session_id: str):
        self.session_id = session_id
        self.last_audio_time = None
        
    def process_audio(self, audio_data: bytes, speaker_id: str = 'unknown'):
        """处理音频数据"""
        try:
            self.last_audio_time = datetime.now()
            
            # 获取会话
            session = ACTIVE_SESSIONS.get(self.session_id)
            if not session or not session.ali_client:
                return
                
            # 推送到阿里云语音识别
            session.ali_client.push_audio_data(
                self.session_id,
                audio_data,
                stream_index=0
            )
            
            # 更新说话人信息
            if speaker_id not in session.speakers:
                session.speakers[speaker_id] = {
                    'first_seen': datetime.now().isoformat(),
                    'last_activity': datetime.now().isoformat()
                }
            else:
                session.speakers[speaker_id]['last_activity'] = datetime.now().isoformat()
                
        except Exception as e:
            logger.exception(f"Error processing audio: {e}")


def create_bot_for_room(session_id: str, room_config: Dict[str, Any]) -> Optional[WebRTCBot]:
    """根据房间配置创建对应的Bot"""
    try:
        room_type = room_config.get('type', 'generic')
        room_name = room_config.get('livekit_room_name')  # 从配置中获取LiveKit房间名称
        
        logger.info(f"Creating {room_type} bot for session {session_id}, LiveKit room: {room_name}")
        
        # 如果提供了LiveKit房间名称，创建LiveKit Bot
        if room_name:
            try:
                from ..websocket.transcription import LiveKitVoiceBot
                
                bot_identity = f"transcription-bot-{session_id}"
                audio_handler = AudioHandler(session_id)
                
                # 创建LiveKit Bot
                livekit_bot = LiveKitVoiceBot(session_id, room_name, bot_identity, audio_handler)
                
                # 包装成WebRTCBot接口
                class LiveKitBotWrapper(WebRTCBot):
                    def __init__(self, livekit_bot):
                        super().__init__(session_id, room_config)
                        self.livekit_bot = livekit_bot
                        self.bot_type = 'livekit'
                    
                    def start_audio_capture(self):
                        """启动LiveKit连接和音频捕获"""
                        logger.info(f"Starting LiveKit bot connection for session {self.session_id}")
                        
                        # 在后台连接到LiveKit
                        async def connect_bot():
                            try:
                                await self.livekit_bot.connect_and_join()
                                self.connected = True
                                logger.info(f"LiveKit bot {self.livekit_bot.bot_identity} connected to room {self.livekit_bot.room_name}")
                            except Exception as e:
                                logger.exception(f"Failed to connect LiveKit bot: {e}")
                                self.connected = False
                        
                        # 使用asyncio.create_task在事件循环中运行
                        asyncio.create_task(connect_bot())
                    
                    def stop(self):
                        """停止LiveKit连接"""
                        super().stop()
                        
                        async def disconnect_bot():
                            try:
                                await self.livekit_bot.disconnect()
                                logger.info(f"LiveKit bot {self.livekit_bot.bot_identity} disconnected")
                            except Exception as e:
                                logger.warning(f"Error disconnecting LiveKit bot: {e}")
                        
                        # 在后台断开连接
                        asyncio.create_task(disconnect_bot())
                
                return LiveKitBotWrapper(livekit_bot)
                
            except ImportError:
                logger.warning("LiveKit SDK not available, falling back to mock bot")
        
        # 创建普通的模拟Bot
        bot = WebRTCBot(session_id, room_config)
        
        # 设置音频处理器
        audio_handler = AudioHandler(session_id)
        bot.set_audio_handler(audio_handler)
        
        return bot
        
    except Exception as e:
        logger.exception(f"Failed to create bot: {e}")
        return None


async def initialize_session_async(session_id: str):
    """异步初始化会话"""
    try:
        session = ACTIVE_SESSIONS[session_id]
        room_config = session.config['room_config']
        voice_config = session.config['voice_config']
        
        logger.info(f"Initializing session {session_id}")
        
        # 1. 创建阿里云客户端
        session.ali_client = AliVoiceClient()
        
        # 2. 创建回调服务器
        callback_port = 9000 + (hash(session_id) % 1000)  # 使用9000-9999端口范围
        session.callback_server = CallbackServer(port=callback_port)
        
        def handle_voice_result(result):
            session.results.append({
                'timestamp': datetime.now().isoformat(),
                'data': result
            })
            session.last_activity = datetime.now()
            logger.info(f"Received voice result for {session_id}: {result.get('text', '')[:50]}...")
            
            # 通知 WebSocket 管理器广播结果
            try:
                # 动态导入避免循环依赖
                from ..websocket.transcription import broadcast_transcription_result
                asyncio.create_task(
                    broadcast_transcription_result(session_id, result)
                )
            except ImportError:
                logger.warning("WebSocket transcription manager not available")
            except Exception as e:
                logger.exception(f"Error notifying transcription manager: {e}")
        
        session.callback_server.add_result_handler(handle_voice_result)
        session.callback_server.start()
        
        # 3. 启动阿里云语音识别
        try:
            transcription_result = session.ali_client.start_transcription(
                callback_url=session.callback_server.get_callback_url(),
                audio_tracks=['stream://realtime']
            )
            logger.info(f"Transcription started: {transcription_result}")
        except Exception as e:
            logger.error(f"Failed to start transcription: {e}")
            session.status = 'error'
            return
        
        # 4. 创建并启动Bot
        bot = create_bot_for_room(session_id, room_config)
        if bot:
            session.bot_instance = bot
            BOT_INSTANCES[session_id] = bot
            bot.connected = True
            bot.start_audio_capture()
            
            session.status = 'active'
            logger.info(f"Session {session_id} initialized successfully")
        else:
            session.status = 'error'
            logger.error(f"Failed to create bot for session {session_id}")
            
    except Exception as e:
        logger.exception(f"Error initializing session {session_id}: {e}")
        if session_id in ACTIVE_SESSIONS:
            ACTIVE_SESSIONS[session_id].status = 'error'


class VoiceServiceClient:
    """独立语音服务客户端封装"""
    
    def __init__(self, base_url: str = "http://localhost:5000", timeout: int = 30):
        self.base_url = base_url.rstrip('/')
        self.timeout = timeout
        self.session = requests.Session()
        
    async def start_transcription(self, callback_url: str, room_config: Dict[str, Any], voice_config: Dict[str, Any]) -> Dict[str, Any]:
        """启动转录任务"""
        try:
            data = {
                "callback_url": callback_url,
                "audio_tracks": ["stream://realtime"],
                "room_config": room_config,
                "voice_config": voice_config
            }
            
            response = self.session.post(
                f"{self.base_url}/trainsction",
                json=data,
                timeout=self.timeout
            )
            response.raise_for_status()
            return response.json()
            
        except requests.RequestException as e:
            logger.exception(f"Error starting transcription: {e}")
            return {"success": False, "error": str(e)}
    
    async def stop_transcription(self, session_id: str) -> Dict[str, Any]:
        """停止转录任务"""
        try:
            data = {"session_id": session_id}
            response = self.session.post(
                f"{self.base_url}/stoptran",
                json=data,
                timeout=self.timeout
            )
            response.raise_for_status()
            return response.json()
            
        except requests.RequestException as e:
            logger.exception(f"Error stopping transcription: {e}")
            return {"success": False, "error": str(e)}
    
    async def get_sentences(self, session_id: str, speaker_id: Optional[str] = None, include_unassigned: bool = True) -> Dict[str, Any]:
        """获取转录句子"""
        try:
            params = {
                "session_id": session_id,
                "include_unassigned": str(include_unassigned).lower()
            }
            if speaker_id:
                params["speaker_id"] = speaker_id
                
            response = self.session.get(
                f"{self.base_url}/sentences",
                params=params,
                timeout=self.timeout
            )
            response.raise_for_status()
            return response.json()
            
        except requests.RequestException as e:
            logger.exception(f"Error getting sentences: {e}")
            return {"success": False, "error": str(e)}
    
    async def manage_speaker(self, session_id: str, action: str, speaker_id: str, timestamp_ms: Optional[int] = None) -> Dict[str, Any]:
        """管理说话人状态"""
        try:
            endpoint = f"/speaker{action}"  # speakerstart 或 speakerstop
            data = {
                "session_id": session_id,
                "speaker_id": speaker_id
            }
            if timestamp_ms is not None:
                data["timestamp_ms"] = timestamp_ms
            else:
                data["timestamp_ms"] = int(datetime.now().timestamp() * 1000)
                
            response = self.session.post(
                f"{self.base_url}{endpoint}",
                json=data,
                timeout=self.timeout
            )
            response.raise_for_status()
            return response.json()
            
        except requests.RequestException as e:
            logger.exception(f"Error managing speaker: {e}")
            return {"success": False, "error": str(e)}


# 全局语音服务客户端
def get_voice_client():
    """获取语音服务客户端实例"""
    config = get_voice_service_config()
    return VoiceServiceClient(config["base_url"], config["timeout"])


voice_client = get_voice_client()


class VoiceRecognitionService:
    """语音识别服务管理类"""
    
    def __init__(self):
        self.active_sessions = ACTIVE_SESSIONS
        self.voice_client = get_voice_client()
        config = get_voice_service_config()
        self.callback_base_url = config["callback_base_url"]
    
    async def create_session(self, room_config: Dict[str, Any], voice_config: Dict[str, Any]) -> Dict[str, Any]:
        """创建语音识别会话"""
        async with _voice_service_lock:
            try:
                # 检查是否已有活跃会话
                global _current_active_room
                room_id = room_config.get("room_id", "unknown")
                
                if _current_active_room and _current_active_room != room_id:
                    return {
                        "success": False,
                        "error": f"语音识别服务正在被房间 {_current_active_room} 使用",
                        "busy": True
                    }
                
                # 生成会话ID
                session_id = str(uuid.uuid4())
                
                # 创建会话对象
                session = VoiceSession(session_id, room_id, {
                    'room_config': room_config,
                    'voice_config': voice_config
                })
                
                # 启动独立语音服务的转录任务
                callback_url = f"{self.callback_base_url}?session_id={session_id}"
                transcription_result = await self.voice_client.start_transcription(
                    callback_url, room_config, voice_config
                )
                
                if not transcription_result.get("success", True):  # 202状态码表示accepted
                    return {
                        "success": False,
                        "error": transcription_result.get("error", "启动转录服务失败")
                    }
                
                # 保存会话
                self.active_sessions[session_id] = session
                session.status = "active"
                _current_active_room = room_id
                
                # 如果配置了LiveKit，启动Bot
                if room_config.get("livekit_room_name"):
                    bot = await self._create_livekit_bot(session_id, room_config)
                    if bot:
                        BOT_INSTANCES[session_id] = bot
                        session.bot_instance = bot
                
                logger.info(f"Created voice recognition session {session_id} for room {room_id}")
                
                return {
                    "success": True,
                    "session_id": session_id,
                    "room_id": room_id,
                    "status": session.status,
                    "message": "语音识别会话创建成功"
                }
                
            except Exception as e:
                logger.exception(f"Error creating voice session: {e}")
                return {
                    "success": False,
                    "error": str(e)
                }
    
    async def get_sessions(self) -> Dict[str, Any]:
        """获取所有会话列表"""
        try:
            sessions = [session.to_dict() for session in self.active_sessions.values()]
            return {
                "success": True,
                "total": len(sessions),
                "sessions": sessions
            }
        except Exception as e:
            logger.exception(f"Error getting sessions: {e}")
            return {
                "success": False,
                "error": str(e)
            }
    
    async def get_session_detail(self, session_id: str) -> Dict[str, Any]:
        """获取会话详情"""
        try:
            if session_id not in self.active_sessions:
                return {
                    "success": False,
                    "error": "Session not found"
                }
            
            session = self.active_sessions[session_id]
            bot = BOT_INSTANCES.get(session_id)
            
            # 从独立语音服务获取最新句子
            sentences_result = await self.voice_client.get_sentences(session_id)
            
            response_data = {
                'success': True,
                'session': session.to_dict(),
                'speakers': session.speakers,
                'sentences': sentences_result.get('sentences', []) if sentences_result.get('success') else [],
                'bot_status': {
                    'connected': bot.connected if bot else False,
                    'type': bot.bot_type if bot else None,
                    'room_id': bot.room_id if bot else None
                }
            }
            
            return response_data
            
        except Exception as e:
            logger.exception(f"Error getting session detail: {e}")
            return {
                "success": False,
                "error": str(e)
            }
    
    async def get_session_results(self, session_id: str, page: int = 1, per_page: int = 50) -> Dict[str, Any]:
        """获取会话识别结果（分页）"""
        try:
            if session_id not in self.active_sessions:
                return {
                    "success": False,
                    "error": "Session not found"
                }
            
            # 从独立语音服务获取句子
            sentences_result = await self.voice_client.get_sentences(session_id)
            
            if not sentences_result.get('success', True):
                return {
                    "success": False,
                    "error": sentences_result.get('error', '获取结果失败')
                }
            
            sentences = sentences_result.get('sentences', [])
            
            # 分页处理
            total = len(sentences)
            start_idx = (page - 1) * per_page
            end_idx = min(start_idx + per_page, total)
            
            paginated_sentences = sentences[start_idx:end_idx]
            
            return {
                'success': True,
                'session_id': session_id,
                'pagination': {
                    'page': page,
                    'per_page': per_page,
                    'total': total,
                    'pages': (total + per_page - 1) // per_page
                },
                'results': paginated_sentences
            }
            
        except Exception as e:
            logger.exception(f"Error getting session results: {e}")
            return {
                "success": False,
                "error": str(e)
            }
    
    async def manage_speaker(self, session_id: str, action: str, speaker_id: str, timestamp_ms: Optional[int] = None) -> Dict[str, Any]:
        """管理说话人状态"""
        try:
            if session_id not in self.active_sessions:
                return {
                    "success": False,
                    "error": "Session not found"
                }
            
            if action not in ['start', 'stop']:
                return {
                    "success": False,
                    "error": "Invalid action, must be 'start' or 'stop'"
                }
            
            # 调用独立语音服务
            result = await self.voice_client.manage_speaker(session_id, action, speaker_id, timestamp_ms)
            
            if result.get('status') == 'ok':
                # 更新本地会话记录
                session = self.active_sessions[session_id]
                if speaker_id not in session.speakers:
                    session.speakers[speaker_id] = {
                        'first_seen': datetime.now().isoformat(),
                        'last_activity': datetime.now().isoformat(),
                        'status': action
                    }
                else:
                    session.speakers[speaker_id]['last_activity'] = datetime.now().isoformat()
                    session.speakers[speaker_id]['status'] = action
                
                session.last_activity = datetime.now()
                
                return {
                    "success": True,
                    "action": action,
                    "speaker_id": speaker_id,
                    "result": result
                }
            else:
                return {
                    "success": False,
                    "error": result.get('error', f'管理说话人{action}失败')
                }
            
        except Exception as e:
            logger.exception(f"Error managing speaker: {e}")
            return {
                "success": False,
                "error": str(e)
            }
    
    async def stop_session(self, session_id: str) -> Dict[str, Any]:
        """停止语音识别会话"""
        async with _voice_service_lock:
            try:
                global _current_active_room
                
                if session_id not in self.active_sessions:
                    return {
                        "success": False,
                        "error": "Session not found"
                    }
                
                session = self.active_sessions[session_id]
                
                # 停止独立语音服务的转录任务
                stop_result = await self.voice_client.stop_transcription(session_id)
                
                # 停止Bot（如果存在）
                bot = BOT_INSTANCES.get(session_id)
                if bot:
                    bot.stop()
                    del BOT_INSTANCES[session_id]
                
                # 清理会话
                del self.active_sessions[session_id]
                
                # 释放全局锁
                if _current_active_room == session.room_id:
                    _current_active_room = None
                
                logger.info(f"Stopped voice recognition session {session_id}")
                
                return {
                    "success": True,
                    "session_id": session_id,
                    "message": "语音识别会话已停止"
                }
                
            except Exception as e:
                logger.exception(f"Error stopping session: {e}")
                return {
                    "success": False,
                    "error": str(e)
                }
    
    async def get_system_status(self) -> Dict[str, Any]:
        """获取系统状态"""
        try:
            global _current_active_room
            
            # 统计信息
            active_count = len(self.active_sessions)
            bot_count = len(BOT_INSTANCES)
            
            # 健康检查 - ping独立语音服务
            health_status = "unknown"
            try:
                response = requests.get(f"{voice_client.base_url}/health", timeout=5)
                health_status = "healthy" if response.status_code == 200 else "unhealthy"
            except:
                health_status = "unreachable"
            
            return {
                "success": True,
                "global_lock": {
                    "is_locked": _current_active_room is not None,
                    "active_room_id": _current_active_room,
                    "message": f"服务正在被房间 {_current_active_room} 使用" if _current_active_room else "服务可用"
                },
                "stats": {
                    "active_sessions": active_count,
                    "active_bots": bot_count,
                    "voice_service_health": health_status
                },
                "timestamp": datetime.now().isoformat()
            }
            
        except Exception as e:
            logger.exception(f"Error getting system status: {e}")
            return {
                "success": False,
                "error": str(e)
            }
    
    async def update_session_results(self, session_id: str, result_data: Dict[str, Any]) -> None:
        """更新会话结果（来自回调）"""
        try:
            if session_id in self.active_sessions:
                session = self.active_sessions[session_id]
                session.results.append({
                    'timestamp': datetime.now().isoformat(),
                    'data': result_data
                })
                session.last_activity = datetime.now()
                logger.info(f"Updated session {session_id} with callback result")
            
        except Exception as e:
            logger.exception(f"Error updating session results: {e}")
    
    async def _create_livekit_bot(self, session_id: str, room_config: Dict[str, Any]) -> Optional['WebRTCBot']:
        """创建LiveKit Bot"""
        try:
            livekit_room_name = room_config.get("livekit_room_name")
            if not livekit_room_name:
                return None
            
            # 尝试创建LiveKit Bot
            bot = create_bot_for_room(session_id, room_config)
            if bot:
                bot.start_audio_capture()
                logger.info(f"Created LiveKit bot for session {session_id}")
            
            return bot
            
        except Exception as e:
            logger.exception(f"Error creating LiveKit bot: {e}")
            return None


# 全局服务实例
voice_service = VoiceRecognitionService()
