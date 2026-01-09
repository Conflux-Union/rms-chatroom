#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实时语音转文字统一服务
集成 WebSocket 连接管理、Bot主动语音监听、识别结果广播、全局锁机制和阿里云语音识别客户端
包含完整的LiveKit SDK集成、Bot Token生成、用户认证和优雅关闭功能
"""

import asyncio
import json
import logging
import uuid
import threading
import time
import base64
import requests
import random
import struct
from datetime import datetime
from typing import Dict, List, Optional, Any, Callable, TYPE_CHECKING
from dataclasses import dataclass, field
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Depends, HTTPException, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from ..routers.deps import get_current_user, require_permission, CurrentUser
from ..core.config import get_settings

# LiveKit SDK 集成
try:
    from livekit.api import AccessToken, VideoGrants, LiveKitAPI
    from livekit.rtc import Room, RoomOptions, DataPacket, AudioFrame
    from livekit.rtc.participant import RemoteParticipant as ParticipantInfo
    LIVEKIT_AVAILABLE = True
except ImportError:
    LIVEKIT_AVAILABLE = False
    # 创建占位符以避免运行时NameError
    class AccessToken:  # type: ignore
        def __init__(self, *args, **kwargs):
            pass
        def with_identity(self, *args):
            return self
        def with_name(self, *args):
            return self
        def with_grants(self, *args):
            return self
        def to_jwt(self):
            return "mock_token"
    
    class VideoGrants:  # type: ignore
        def __init__(self, *args, **kwargs):
            pass
    
    class LiveKitAPI:  # type: ignore
        pass
    
    class Room:  # type: ignore
        def on(self, event_name):
            def decorator(func):
                return func
            return decorator
        
        async def disconnect(self):
            pass
    
    class RoomOptions:  # type: ignore
        def __init__(self, *args, **kwargs):
            pass
    
    class DataPacket:  # type: ignore
        pass
    
    class AudioFrame:  # type: ignore
        def __init__(self):
            self.data = b""
    
    class ParticipantInfo:  # type: ignore
        def __init__(self):
            self.name = "unknown"
            self.identity = "unknown"
    
    async def connect(*args, **kwargs):  # type: ignore
        # 模拟连接成功
        pass

# 创建模拟类以避免导入错误 (仅在导入失败时使用)
if not LIVEKIT_AVAILABLE:
    class MockRoom:
        def __init__(self):
            self.participants = {}
        
        def on(self, event_name):
            def decorator(func):
                return func
            return decorator
        
        async def disconnect(self):
            pass

    # 将模拟Room赋值给真实Room类型
    Room = MockRoom  # type: ignore

try:
    import sseclient
except ImportError:
    sseclient = None

logger = logging.getLogger(__name__)
settings = get_settings()

# ==================== LiveKit SDK 集成 ====================

class LiveKitBotTokenGenerator:
    """LiveKit Bot Token生成器"""
    
    def __init__(self):
        if not LIVEKIT_AVAILABLE:
            logger.warning("LiveKit SDK not available. Please install livekit-python-sdk")
            
    def generate_bot_token(self, room_name: str, bot_identity: str, bot_name: str = "VoiceBot") -> str:
        """
        生成Bot访问令牌
        
        Args:
            room_name: 房间名称
            bot_identity: Bot标识符
            bot_name: Bot显示名称
            
        Returns:
            JWT访问令牌
        """
        if not LIVEKIT_AVAILABLE:
            raise RuntimeError("LiveKit SDK not available")
            
        token = (
            AccessToken(
                api_key=settings.livekit_api_key,
                api_secret=settings.livekit_api_secret,
            )
            .with_identity(bot_identity)
            .with_name(bot_name)
            .with_grants(VideoGrants(
                room_join=True,
                room=room_name,
                can_publish=True,
                can_publish_sources=["microphone"],  # Bot只发布音频
                can_subscribe=True,
                can_publish_data=True,
                # 移除不存在的参数 can_subscribe_data
            ))
        )
        
        return token.to_jwt()


class LiveKitVoiceBot:
    """LiveKit语音识别Bot"""
    
    def __init__(self, session_id: str, room_name: str, bot_identity: str, audio_handler):
        self.session_id = session_id
        self.room_name = room_name
        self.bot_identity = bot_identity
        self.audio_handler = audio_handler
        self.room: Optional[Room] = None
        self.connected = False
        self._stop_event = threading.Event()
        self._audio_buffer = []
        
        if not LIVEKIT_AVAILABLE:
            raise RuntimeError("LiveKit SDK not available")
    
    async def connect_and_join(self):
        """连接到LiveKit房间"""
        try:
            # 生成Bot令牌
            token_generator = LiveKitBotTokenGenerator()
            bot_token = token_generator.generate_bot_token(
                self.room_name, 
                self.bot_identity, 
                f"VoiceBot-{self.session_id[:8]}"
            )
            
            # 连接到房间
            self.room = Room()
            await self.room.connect(
                url=settings.livekit_host,
                token=bot_token,
                options=RoomOptions(
                    auto_subscribe=True,
                    # 移除不存在的参数 adaptive_stream
                )
            )
            
            # 设置事件处理器
            self._setup_event_handlers()
            
            self.connected = True
            logger.info(f"Bot {self.bot_identity} successfully joined room {self.room_name}")
            
        except Exception as e:
            logger.exception(f"Failed to connect bot to room: {e}")
            self.connected = False
            raise
    
    def _setup_event_handlers(self):
        """设置房间事件处理器"""
        if not self.room:
            return
            
        @self.room.on("participant_connected")
        def on_participant_connected(participant: ParticipantInfo):
            logger.info(f"Participant {participant.name} joined room {self.room_name}")
            
        @self.room.on("participant_disconnected")
        def on_participant_disconnected(participant: ParticipantInfo):
            logger.info(f"Participant {participant.name} left room {self.room_name}")
        
        @self.room.on("track_subscribed")
        def on_track_subscribed(track, publication, participant):
            if track.kind == "audio":
                logger.info(f"Subscribed to audio track from {participant.name}")
                # 开始处理音频数据
                asyncio.create_task(self._process_audio_track(track, participant))
    
    async def _process_audio_track(self, track, participant):
        """处理音频轨道数据"""
        try:
            while not self._stop_event.is_set() and self.connected:
                try:
                    # 获取音频帧
                    frame = await track.recv()
                    if frame:
                        # 转换为字节数据并发送给语音识别服务
                        audio_data = frame.data
                        if self.audio_handler:
                            self.audio_handler.process_audio(audio_data, participant.identity)
                        
                except Exception as e:
                    logger.warning(f"Error processing audio frame: {e}")
                    break
                    
        except Exception as e:
            logger.exception(f"Error in audio track processing: {e}")
    
    async def disconnect(self):
        """断开连接"""
        self._stop_event.set()
        self.connected = False
        
        if self.room:
            try:
                await self.room.disconnect()
                logger.info(f"Bot {self.bot_identity} disconnected from room {self.room_name}")
            except Exception as e:
                logger.warning(f"Error disconnecting from room: {e}")
            finally:
                self.room = None

# ==================== 阿里云语音识别客户端 ====================

class AliVoiceClient:
    """阿里云语音识别服务客户端"""
    
    def __init__(self, base_url: str = "http://localhost:5000", timeout: int = 30):
        """
        初始化客户端
        
        Args:
            base_url: 服务器地址，默认 http://localhost:5000
            timeout: 请求超时时间，默认30秒
        """
        self.base_url = base_url.rstrip('/')
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update({'Content-Type': 'application/json'})
    
    def _make_request(self, method: str, endpoint: str, **kwargs) -> requests.Response:
        """发送HTTP请求"""
        url = urljoin(self.base_url + '/', endpoint)
        try:
            response = self.session.request(method, url, timeout=self.timeout, **kwargs)
            response.raise_for_status()
            return response
        except requests.exceptions.RequestException as e:
            logger.error(f"Request failed: {method} {url} - {e}")
            raise
    
    def start_transcription(self, callback_url: str, audio_tracks: List[str], 
                          ffmpeg_path: Optional[str] = None) -> Dict[str, Any]:
        """启动音频转录任务"""
        data = {
            'callback_url': callback_url,
            'audio_tracks': audio_tracks
        }
        if ffmpeg_path:
            data['ffmpeg_path'] = ffmpeg_path
            
        logger.info(f"Starting transcription with {len(audio_tracks)} audio tracks")
        response = self._make_request('POST', 'transcription', json=data)
        return response.json()
    
    def stop_transcription(self, session_id: str) -> Dict[str, Any]:
        """停止转录任务"""
        data = {'session_id': session_id}
        logger.info(f"Stopping transcription for session {session_id}")
        response = self._make_request('POST', 'stoptran', json=data)
        return response.json()
    
    def register_speaker_start(self, session_id: str, speaker_id: str, 
                             timestamp_ms: Optional[int] = None) -> Dict[str, Any]:
        """注册说话人开始说话事件"""
        data = {
            'session_id': session_id,
            'speaker_id': speaker_id
        }
        if timestamp_ms is not None:
            data['timestamp_ms'] = str(timestamp_ms)
            
        logger.info(f"Registering speaker start: {speaker_id} in session {session_id}")
        response = self._make_request('POST', 'speakerstart', json=data)
        return response.json()
    
    def register_speaker_stop(self, session_id: str, speaker_id: str, 
                            timestamp_ms: Optional[int] = None) -> Dict[str, Any]:
        """注册说话人停止说话事件"""
        data = {
            'session_id': session_id,
            'speaker_id': speaker_id
        }
        if timestamp_ms is not None:
            data['timestamp_ms'] = str(timestamp_ms)
            
        logger.info(f"Registering speaker stop: {speaker_id} in session {session_id}")
        response = self._make_request('POST', 'speakerstop', json=data)
        return response.json()
    
    def push_audio_data(self, session_id: str, audio_data: bytes, 
                       stream_index: int = 0) -> Dict[str, Any]:
        """向指定音频流推送数据"""
        audio_b64 = base64.b64encode(audio_data).decode('utf-8')
        data = {
            'session_id': session_id,
            'stream_index': stream_index,
            'audio_data': audio_b64
        }
        response = self._make_request('POST', 'streams/push', json=data)
        return response.json()
    
    def get_sentences(self, session_id: str, speaker_id: Optional[str] = None, 
                     include_unassigned: bool = True) -> Dict[str, Any]:
        """获取会话的句子"""
        params = {'session_id': session_id, 'include_unassigned': str(include_unassigned).lower()}
        if speaker_id:
            params['speaker_id'] = speaker_id
            
        response = self._make_request('GET', 'sentences', params=params)
        return response.json()
    
    def get_session_stats(self, session_id: str) -> Dict[str, Any]:
        """获取会话统计信息"""
        params = {'session_id': session_id}
        response = self._make_request('GET', 'session/stats', params=params)
        return response.json()
    
    def add_audio_stream(self, session_id: str, stream_index: int) -> Dict[str, Any]:
        """热插拔：添加音频流"""
        data = {
            'session_id': session_id,
            'stream_index': stream_index
        }
        logger.info(f"Adding stream {stream_index} to session {session_id}")
        response = self._make_request('POST', 'streams/add', json=data)
        return response.json()
    
    def remove_audio_stream(self, session_id: str, stream_index: int) -> Dict[str, Any]:
        """热插拔：移除音频流"""
        data = {
            'session_id': session_id,
            'stream_index': stream_index
        }
        logger.info(f"Removing stream {stream_index} from session {session_id}")
        response = self._make_request('POST', 'streams/remove', json=data)
        return response.json()
    
    def list_audio_streams(self, session_id: str) -> Dict[str, Any]:
        """获取当前活跃的音频流列表"""
        params = {'session_id': session_id}
        response = self._make_request('GET', 'streams/list', params=params)
        return response.json()
    
    def start_timesync(self, session_id: str) -> Dict[str, Any]:
        """启动时间同步会话"""
        data = {'session_id': session_id}
        logger.info(f"Starting timesync for session {session_id}")
        response = self._make_request('POST', 'timesync/start', json=data)
        return response.json()
    
    def stop_timesync(self, session_id: str) -> Dict[str, Any]:
        """停止时间同步会话"""
        data = {'session_id': session_id}
        logger.info(f"Stopping timesync for session {session_id}")
        response = self._make_request('POST', 'timesync/stop', json=data)
        return response.json()
    
    def timesync_heartbeat(self, session_id: str, 
                          callback: Callable[[Dict[str, Any]], None],
                          stop_event: Optional[threading.Event] = None) -> threading.Thread:
        """启动时间同步心跳监听（Server-Sent Events）"""
        def heartbeat_worker():
            url = urljoin(self.base_url + '/', f'timesync/heartbeat?session_id={session_id}')
            try:
                response = requests.get(url, stream=True, timeout=None)
                response.raise_for_status()
                
                if not sseclient:
                    logger.error("sseclient module not available")
                    return
                
                client = sseclient.SSEClient(response.iter_lines())
                logger.info(f"Started heartbeat stream for session {session_id}")
                
                for event in client.events():
                    if stop_event and stop_event.is_set():
                        break
                    
                    try:
                        data = json.loads(event.data)
                        callback(data)
                    except json.JSONDecodeError as e:
                        logger.error(f"Failed to parse heartbeat data: {e}")
                    except Exception as e:
                        logger.exception(f"Heartbeat callback error: {e}")
                        
            except Exception as e:
                logger.exception(f"Heartbeat stream error: {e}")
        
        thread = threading.Thread(target=heartbeat_worker, daemon=True)
        thread.start()
        return thread


class CallbackServer:
    """用于接收转录结果的简单HTTP服务器 - 支持优雅关闭"""
    
    def __init__(self, host: str = 'localhost', port: int = 8080):
        """初始化回调服务器"""
        self.host = host
        self.port = port
        self.results = []  # 存储接收到的结果
        self.result_handlers = []  # 结果处理器列表
        self._server_thread = None
        self._stop_event = threading.Event()
        self._flask_app = None
        self._server_process = None
        
    def add_result_handler(self, handler: Callable[[Dict[str, Any]], None]):
        """添加结果处理器"""
        self.result_handlers.append(handler)
    
    def start(self):
        """启动回调服务器"""
        from flask import Flask, request, jsonify
        
        # 确保Flask应用被正确初始化
        if self._flask_app is None:
            self._flask_app = Flask(__name__)
        
        @self._flask_app.route('/', methods=['POST'])
        def receive_result():
            try:
                result = request.get_json()
                self.results.append(result)
                
                # 调用所有处理器
                for handler in self.result_handlers:
                    try:
                        handler(result)
                    except Exception as e:
                        logger.exception(f"Result handler error: {e}")
                
                logger.info(f"Received result: {json.dumps(result, ensure_ascii=False)}")
                return jsonify({'status': 'ok'}), 200
            except Exception as e:
                logger.exception(f"Callback processing error: {e}")
                return jsonify({'error': str(e)}), 500
        
        @self._flask_app.route('/health', methods=['GET'])
        def health_check():
            """健康检查端点"""
            return jsonify({
                'status': 'healthy',
                'results_count': len(self.results),
                'timestamp': datetime.now().isoformat()
            })
        
        @self._flask_app.route('/shutdown', methods=['POST'])
        def shutdown():
            """优雅关闭端点"""
            try:
                self._stop_event.set()
                # 获取当前请求的environ，用于关闭服务器
                func = request.environ.get('werkzeug.server.shutdown')
                if func is None:
                    return jsonify({'error': 'Not running with the Werkzeug Server'}), 500
                func()
                return jsonify({'message': 'Server shutting down...'}), 200
            except Exception as e:
                logger.exception(f"Error during shutdown: {e}")
                return jsonify({'error': str(e)}), 500
        
        def run_server():
            try:
                # 禁用Flask的日志输出
                import logging as flask_logging
                flask_logging.getLogger('werkzeug').setLevel(flask_logging.WARNING)
                
                # 确保Flask应用存在并运行
                if self._flask_app is not None:
                    self._flask_app.run(
                        host=self.host, 
                        port=self.port, 
                        debug=False, 
                        use_reloader=False,
                        threaded=True
                    )
            except Exception as e:
                logger.exception(f"Flask server error: {e}")
        
        self._server_thread = threading.Thread(target=run_server, daemon=True)
        self._server_thread.start()
        
        # 等待服务器启动
        time.sleep(1)
        logger.info(f"Callback server started at http://{self.host}:{self.port}")
    
    def stop(self):
        """优雅关闭回调服务器"""
        if self._stop_event.is_set():
            logger.info("Callback server already stopping")
            return
            
        logger.info(f"Stopping callback server at http://{self.host}:{self.port}")
        
        try:
            # 尝试通过HTTP请求触发关闭
            import requests
            requests.post(f"http://{self.host}:{self.port}/shutdown", timeout=5)
        except Exception as e:
            logger.warning(f"Failed to gracefully shutdown via HTTP: {e}")
        
        # 设置停止事件
        self._stop_event.set()
        
        # 等待服务器线程结束（最多等待5秒）
        if self._server_thread and self._server_thread.is_alive():
            self._server_thread.join(timeout=5)
            if self._server_thread.is_alive():
                logger.warning("Callback server thread did not shut down cleanly")
            else:
                logger.info("Callback server stopped successfully")
        
        # 清理资源
        self._flask_app = None
        self._server_thread = None
    
    def is_running(self) -> bool:
        """检查服务器是否正在运行"""
        return (self._server_thread is not None and 
                self._server_thread.is_alive() and 
                not self._stop_event.is_set())
    
    def get_callback_url(self) -> str:
        """获取回调URL"""
        return f"http://{self.host}:{self.port}/"
    
    def get_results(self) -> List[Dict[str, Any]]:
        """获取所有接收到的结果"""
        return self.results.copy()
    
    def clear_results(self):
        """清空结果"""
        self.results.clear()
        
    def get_stats(self) -> Dict[str, Any]:
        """获取服务器统计信息"""
        return {
            'running': self.is_running(),
            'results_count': len(self.results),
            'handlers_count': len(self.result_handlers),
            'host': self.host,
            'port': self.port,
            'callback_url': self.get_callback_url()
        }

# ==================== 全局配置和状态 ====================

# 全局异步锁 - 确保一次只有一个房间使用语音识别服务
_voice_service_lock = asyncio.Lock()
_current_active_room = None  # 当前占用服务的房间ID

# 会话管理
ACTIVE_SESSIONS = {}
BOT_INSTANCES = {}
EXECUTOR = ThreadPoolExecutor(max_workers=10)

# WebSocket 客户端管理
room_clients: Dict[str, List["VoiceTranscriptionClient"]] = defaultdict(list)
session_to_room: Dict[str, str] = {}
room_sessions: Dict[str, str] = {}
client_connections: Dict[str, "VoiceTranscriptionClient"] = {}

# ==================== 数据模型 ====================

@dataclass
class VoiceTranscriptionClient:
    """语音转文字客户端信息"""
    websocket: WebSocket
    user_id: str
    username: str
    room_id: str
    session_id: Optional[str] = None
    connected_at: datetime = field(default_factory=datetime.now)
    last_activity: datetime = field(default_factory=datetime.now)


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
        self.bot_instance: Optional["WebRTCBot"] = None
        
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
    """语音转文字 Bot - 主动加入语音房间监听音频"""
    
    def __init__(self, session_id: str, room_config: Dict[str, Any]):
        self.session_id = session_id
        self.room_config = room_config
        self.room_id = room_config.get('room_id', 'unknown')
        self.channel_id = room_config.get('channel_id')  # 语音频道ID
        self.bot_type = room_config.get('type', 'livekit')
        self.connected = False
        self.audio_handler = None
        self._stop_event = threading.Event()
        
        # LiveKit 相关
        self.livekit_room = None
        self.bot_token = None
        
    def set_audio_handler(self, handler):
        self.audio_handler = handler
        
    async def start_audio_capture(self):
        """启动音频捕获 - 主动加入语音房间"""
        logger.info(f"Bot {self.session_id} joining voice room for channel {self.channel_id}")
        
        if self.bot_type == 'livekit':
            await self._join_livekit_room()
        else:
            # 回退到模拟音频（用于测试）
            self._start_mock_audio()
            
    async def _join_livekit_room(self):
        """加入 LiveKit 语音房间"""
        try:
            # 1. 生成 Bot 的访问令牌
            bot_identity = f"transcription-bot-{self.session_id[:8]}"
            room_name = f"voice_{self.channel_id}"
            
            # TODO: 需要实现 Bot Token 生成
            # token = self._generate_bot_token(bot_identity, room_name)
            
            # 2. 连接到 LiveKit 房间
            # TODO: 需要 livekit-python-sdk
            logger.info(f"Bot attempting to join LiveKit room: {room_name}")
            
            # 暂时使用模拟实现
            await self._simulate_livekit_connection(room_name, bot_identity)
            
        except Exception as e:
            logger.exception(f"Error joining LiveKit room: {e}")
            # 回退到模拟音频
            self._start_mock_audio()
    
    async def _simulate_livekit_connection(self, room_name: str, bot_identity: str):
        """模拟 LiveKit 连接和音频监听"""
        logger.info(f"Bot {bot_identity} simulating connection to room {room_name}")
        
        self.connected = True
        
        # 启动模拟音频监听循环
        def audio_listener():
            import random
            import struct
            
            logger.info(f"Bot {self.session_id} started audio listening simulation")
            
            while not self._stop_event.is_set() and self.connected:
                try:
                    # 模拟从房间接收到的音频数据
                    # 在实际实现中，这里会是真实的 LiveKit 音频流
                    chunk_size = 1024
                    audio_data = b''
                    
                    # 生成16kHz 16位单声道PCM数据（模拟房间内用户的语音）
                    for _ in range(chunk_size):
                        # 模拟不同的说话人
                        speaker_variance = random.choice(['user1', 'user2', 'user3'])
                        sample = int(random.uniform(-2000, 2000))
                        audio_data += struct.pack('<h', sample)
                    
                    # 处理接收到的音频
                    if self.audio_handler:
                        asyncio.create_task(
                            self.audio_handler.process_audio(audio_data, f'room_speaker_{speaker_variance}')
                        )
                    
                    # 模拟实时音频流间隔
                    time.sleep(0.064)  # ~16ms chunks (62.5 FPS)
                    
                except Exception as e:
                    logger.exception(f"Error in audio listener: {e}")
                    break
        
        # 在后台线程中运行音频监听
        audio_thread = threading.Thread(target=audio_listener, daemon=True)
        audio_thread.start()
        
        logger.info(f"Bot {bot_identity} audio monitoring started")
        
    def _generate_bot_token(self, bot_identity: str, room_name: str) -> str:
        """生成 Bot 的 LiveKit 访问令牌"""
        # TODO: 实现真正的 LiveKit Token 生成
        # 需要使用 settings 中的 LiveKit credentials
        
        # 示例实现（需要 livekit-api）:
        # token = AccessToken(settings.livekit_api_key, settings.livekit_api_secret)
        # token.with_identity(bot_identity).with_name(f"语音转文字Bot-{self.session_id[:8]}")
        # token.with_grants(VideoGrants(room_join=True, room=room_name))
        # return token.to_jwt()
        
        return f"mock_token_for_{bot_identity}"
        
    def _start_mock_audio(self):
        """模拟音频流（用于测试和回退）"""
        def audio_worker():
            import random
            import struct
            
            logger.info(f"Bot {self.session_id} started mock audio generation")
            
            while not self._stop_event.is_set():
                # 生成16kHz 16位单声道PCM数据
                chunk_size = 1024
                audio_data = b''
                
                for _ in range(chunk_size):
                    sample = int(random.uniform(-1000, 1000))
                    audio_data += struct.pack('<h', sample)
                
                if self.audio_handler:
                    asyncio.create_task(
                        self.audio_handler.process_audio(audio_data, 'mock_speaker')
                    )
                
                time.sleep(0.064)  # ~16ms chunks
                        
        thread = threading.Thread(target=audio_worker, daemon=True)
        thread.start()
        logger.info(f"Mock audio started for session {self.session_id}")
        
    def stop(self):
        """停止 Bot"""
        self._stop_event.set()
        self.connected = False
        
        # 断开 LiveKit 连接
        if self.livekit_room:
            try:
                # TODO: 实现真正的 LiveKit 断开
                # await self.livekit_room.disconnect()
                logger.info(f"Bot {self.session_id} disconnected from LiveKit room")
            except Exception as e:
                logger.warning(f"Error disconnecting from LiveKit: {e}")
                
        logger.info(f"Bot stopped for session {self.session_id}")
        
    async def cleanup(self):
        """异步清理资源"""
        self.stop()


class AudioHandler:
    """音频处理器"""
    
    def __init__(self, session_id: str):
        self.session_id = session_id
        self.last_audio_time = None
        
    async def process_audio(self, audio_data: bytes, speaker_id: str = 'unknown'):
        """处理音频数据"""
        try:
            self.last_audio_time = datetime.now()
            
            # 获取会话
            session = ACTIVE_SESSIONS.get(self.session_id)
            if not session or not session.ali_client:
                return
                
            # 推送到阿里云语音识别 (异步包装)
            await asyncio.to_thread(
                session.ali_client.push_audio_data,
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


# ==================== API 模型 ====================

class VoiceSessionCreate(BaseModel):
    """创建语音识别会话的请求模型"""
    room_config: Dict[str, Any] = Field(..., description="房间配置")
    voice_config: Dict[str, Any] = Field(default_factory=dict, description="语音识别配置")


class VoiceSessionResponse(BaseModel):
    """语音会话响应模型"""
    success: bool
    session_id: Optional[str] = None
    room_id: Optional[str] = None
    status: Optional[str] = None
    message: Optional[str] = None


class SpeakerAction(BaseModel):
    """说话人状态管理的请求模型"""
    action: str = Field(..., description="操作类型: start 或 stop")
    speaker_id: str = Field(..., description="说话人ID")
    timestamp_ms: Optional[int] = Field(None, description="时间戳（毫秒）")


# ==================== 核心业务逻辑 ====================

def create_bot_for_room(session_id: str, room_config: Dict[str, Any]) -> Optional[WebRTCBot]:
    """根据房间配置创建对应的Bot"""
    try:
        room_type = room_config.get('type', 'generic')
        logger.info(f"Creating {room_type} bot for session {session_id}")
        
        bot = WebRTCBot(session_id, room_config)
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
        callback_port = 9000 + (hash(session_id) % 1000)
        session.callback_server = CallbackServer(port=callback_port)
        
        def handle_voice_result(result):
            session.results.append({
                'timestamp': datetime.now().isoformat(),
                'data': result
            })
            session.last_activity = datetime.now()
            logger.info(f"Received voice result for {session_id}: {result.get('text', '')[:50]}...")
            
            # 广播结果到 WebSocket 客户端
            asyncio.create_task(broadcast_transcription_result(session_id, result))
        
        session.callback_server.add_result_handler(handle_voice_result)
        await asyncio.to_thread(session.callback_server.start)
        
        # 3. 启动阿里云语音识别
        try:
            transcription_result = await asyncio.to_thread(
                session.ali_client.start_transcription,
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
            await bot.start_audio_capture()  # 异步启动音频捕获
            
            session.status = 'active'
            logger.info(f"Session {session_id} initialized successfully")
        else:
            session.status = 'error'
            logger.error(f"Failed to create bot for session {session_id}")
            
    except Exception as e:
        logger.exception(f"Error initializing session {session_id}: {e}")
        if session_id in ACTIVE_SESSIONS:
            ACTIVE_SESSIONS[session_id].status = 'error'


async def create_voice_session(room_config: Dict[str, Any], voice_config: Dict[str, Any]) -> Dict[str, Any]:
    """创建语音识别会话"""
    global _current_active_room
    
    try:
        # 检查全局锁 - 一次只能有一个房间使用语音识别服务
        async with _voice_service_lock:
            room_id = room_config.get('room_id', f'room_{uuid.uuid4().hex[:8]}')
            
            # 如果已有其他房间在使用服务，拒绝新请求
            if _current_active_room is not None and _current_active_room != room_id:
                logger.warning(f"Voice service busy: room {_current_active_room} is already using the service")
                return {
                    'success': False,
                    'error': f'语音识别服务正在被房间 {_current_active_room} 使用，请等待其结束后再试',
                    'busy_room_id': _current_active_room
                }
            
            # 检查当前房间是否已有活跃会话
            active_room_sessions = [
                s for s in ACTIVE_SESSIONS.values() 
                if s.room_id == room_id and s.status == 'active'
            ]
            
            if active_room_sessions:
                logger.warning(f"Room {room_id} already has active voice session")
                return {
                    'success': False,
                    'error': f'房间 {room_id} 已有活跃的语音识别会话',
                    'existing_session_id': active_room_sessions[0].session_id
                }
            
            # 生成会话ID
            session_id = str(uuid.uuid4())
            
            # 设置当前活跃房间
            _current_active_room = room_id
            
            # 创建会话
            session = VoiceSession(session_id, room_id, {
                'room_config': room_config,
                'voice_config': voice_config
            })
            
            ACTIVE_SESSIONS[session_id] = session
            
            # 异步初始化
            asyncio.create_task(initialize_session_async(session_id))
            
            # 更新 WebSocket 映射
            room_sessions[room_id] = session_id
            session_to_room[session_id] = room_id
            
            # 为房间内所有客户端设置会话ID
            for client in room_clients[room_id]:
                client.session_id = session_id
            
            logger.info(f"Created session {session_id} for room {room_id} - service locked to this room")
            
            return {
                'success': True,
                'session_id': session_id,
                'room_id': room_id,
                'status': 'initializing',
                'message': 'Session created successfully'
            }
            
    except Exception as e:
        logger.exception(f"Error creating session: {e}")
        return {'success': False, 'error': str(e)}


async def stop_voice_session(session_id: str) -> Dict[str, Any]:
    """停止并删除会话"""
    global _current_active_room
    
    try:
        if session_id not in ACTIVE_SESSIONS:
            return {'success': False, 'error': 'Session not found'}
            
        session = ACTIVE_SESSIONS[session_id]
        room_id = session.room_id
        session.status = 'stopping'
        
        # 停止Bot
        if session_id in BOT_INSTANCES:
            bot = BOT_INSTANCES[session_id]
            await bot.cleanup()
            del BOT_INSTANCES[session_id]
            
        # 停止阿里云语音识别
        if session.ali_client:
            try:
                await asyncio.to_thread(session.ali_client.stop_transcription, session_id)
            except Exception as e:
                logger.warning(f"Error stopping transcription: {e}")
                
        # 清理会话
        session.status = 'stopped'
        del ACTIVE_SESSIONS[session_id]
        
        # 清理 WebSocket 映射
        room_sessions.pop(room_id, None)
        session_to_room.pop(session_id, None)
        
        # 清理客户端会话ID
        for client in room_clients[room_id]:
            client.session_id = None
        
        # 释放全局锁
        async with _voice_service_lock:
            remaining_room_sessions = [
                s for s in ACTIVE_SESSIONS.values() 
                if s.room_id == room_id and s.status == 'active'
            ]
            
            if not remaining_room_sessions and _current_active_room == room_id:
                _current_active_room = None
                logger.info(f"Released global voice service lock from room {room_id}")
        
        logger.info(f"Session {session_id} stopped and cleaned up")
        
        # 广播会话结束消息
        await broadcast_to_room(room_id, {
            'type': 'transcription_stopped',
            'session_id': session_id,
            'room_id': room_id,
            'timestamp': datetime.now().isoformat()
        })
        
        return {
            'success': True,
            'message': 'Session stopped successfully'
        }
        
    except Exception as e:
        logger.exception(f"Error stopping session: {e}")
        return {'success': False, 'error': str(e)}


# ==================== WebSocket 管理 ====================

async def connect_client(websocket: WebSocket, user_id: str, username: str, room_id: str) -> str:
    """连接客户端到房间"""
    client_id = f"{room_id}_{user_id}_{uuid.uuid4().hex[:8]}"
    
    client = VoiceTranscriptionClient(
        websocket=websocket,
        user_id=user_id,
        username=username,
        room_id=room_id
    )
    
    # 如果房间已有活跃会话，设置会话ID
    if room_id in room_sessions:
        client.session_id = room_sessions[room_id]
    
    room_clients[room_id].append(client)
    client_connections[client_id] = client
    
    logger.info(f"Client {client_id} ({username}) connected to room {room_id}")
    
    # 通知房间内其他用户
    await broadcast_to_room(room_id, {
        'type': 'user_joined',
        'user_id': user_id,
        'username': username,
        'timestamp': datetime.now().isoformat()
    }, exclude_client=client_id)
    
    return client_id


async def disconnect_client(client_id: str):
    """断开客户端连接"""
    if client_id not in client_connections:
        return
        
    client = client_connections[client_id]
    room_id = client.room_id
    
    # 从房间移除
    room_clients[room_id] = [c for c in room_clients[room_id] if c != client]
    
    # 如果房间没有客户端了，停止语音识别会话
    if not room_clients[room_id] and room_id in room_sessions:
        session_id = room_sessions[room_id]
        await stop_voice_session(session_id)
        logger.info(f"Auto-stopped transcription session {session_id} for empty room {room_id}")
    
    del client_connections[client_id]
    
    # 通知房间内其他用户
    await broadcast_to_room(room_id, {
        'type': 'user_left',
        'user_id': client.user_id,
        'username': client.username,
        'timestamp': datetime.now().isoformat()
    })
    
    logger.info(f"Client {client_id} ({client.username}) disconnected from room {room_id}")


# 移除了 handle_audio_data 函数 - Bot 现在主动监听语音房间，无需前端发送音频


async def broadcast_transcription_result(session_id: str, result: Dict[str, Any]):
    """处理语音识别结果并广播给房间"""
    if session_id not in session_to_room:
        logger.warning(f"Received result for unknown session {session_id}")
        return
    
    room_id = session_to_room[session_id]
    
    broadcast_msg = {
        'type': 'transcription_result',
        'session_id': session_id,
        'room_id': room_id,
        'result': result,
        'timestamp': datetime.now().isoformat()
    }
    
    await broadcast_to_room(room_id, broadcast_msg)
    logger.info(f"Broadcasted transcription result to room {room_id}: {result.get('text', '')[:50]}...")


async def broadcast_to_room(room_id: str, message: Dict[str, Any], exclude_client: Optional[str] = None):
    """向房间内所有客户端广播消息"""
    if room_id not in room_clients:
        return
    
    disconnected_clients = []
    message_json = json.dumps(message, ensure_ascii=False)
    
    for client in room_clients[room_id]:
        client_id = next((cid for cid, c in client_connections.items() if c == client), None)
        
        if client_id == exclude_client:
            continue
            
        try:
            await client.websocket.send_text(message_json)
        except Exception as e:
            logger.warning(f"Failed to send message to client {client_id}: {e}")
            if client_id:
                disconnected_clients.append(client_id)
    
    # 清理断开的连接
    for client_id in disconnected_clients:
        await disconnect_client(client_id)


async def send_to_client(client_id: str, message: Dict[str, Any]):
    """向特定客户端发送消息"""
    if client_id not in client_connections:
        return False
    
    client = client_connections[client_id]
    
    try:
        message_json = json.dumps(message, ensure_ascii=False)
        await client.websocket.send_text(message_json)
        return True
    except Exception as e:
        logger.warning(f"Failed to send message to client {client_id}: {e}")
        await disconnect_client(client_id)
        return False


# ==================== API 路由 ====================

router = APIRouter(prefix="/api/voice-transcription", tags=["voice-transcription"])


# WebSocket 端点
@router.websocket("/ws/room/{room_id}")
async def websocket_voice_transcription(
    websocket: WebSocket,
    room_id: str,
    token: str = Query(..., description="用户认证令牌")
):
    """WebSocket 端点：实时语音转文字 - 仅处理控制消息，不接收音频数据"""
    await websocket.accept()
    client_id = None
    
    try:
        # 实现真实的token验证
        user = await authenticate_websocket_token(token)
        if not user:
            await websocket.send_text(json.dumps({
                'type': 'error',
                'message': '认证失败：无效的token'
            }))
            await websocket.close(code=4001, reason="Authentication failed")
            return
        
        user_id = str(user["id"])
        username = user.get("nickname") or user.get("username", f"User_{user_id}")
        
        client_id = await connect_client(websocket, user_id, username, room_id)
        
        # 发送欢迎消息和房间状态
        room_status = get_room_status(room_id)
        await websocket.send_text(json.dumps({
            'type': 'welcome',
            'client_id': client_id,
            'user_id': user_id,
            'username': username,
            'room_status': room_status,
            'timestamp': client_connections[client_id].connected_at.isoformat()
        }, ensure_ascii=False))
        
        logger.info(f"User {username} ({user_id}) connected to voice transcription room {room_id}")
        
        while True:
            try:
                # 只接收文本消息（控制指令），不再接收音频数据
                message = await websocket.receive_text()
                await handle_text_message(client_id, message)
            except Exception as e:
                logger.warning(f"Error handling message from {client_id}: {e}")
                break
            
    except WebSocketDisconnect:
        logger.info(f"Client {client_id} disconnected from room {room_id}")
    except Exception as e:
        logger.exception(f"WebSocket error for client {client_id}: {e}")
    finally:
        if client_id:
            await disconnect_client(client_id)


async def authenticate_websocket_token(token: str) -> Optional[Dict[str, Any]]:
    """
    验证WebSocket连接的token
    
    Args:
        token: JWT token
        
    Returns:
        用户信息字典，验证失败返回None
    """
    try:
        from ..services.sso_client import SSOClient
        
        # 使用SSO客户端验证token
        user = await SSOClient.verify_token(token)
        return user
        
    except Exception as e:
        logger.warning(f"WebSocket token authentication failed: {e}")
        return None


async def handle_text_message(client_id: str, message: str):
    """处理客户端文本消息"""
    try:
        data = json.loads(message)
        msg_type = data.get('type')
        
        if msg_type == 'start_transcription':
            await handle_start_transcription(client_id, data)
        elif msg_type == 'stop_transcription':
            await handle_stop_transcription(client_id, data)
        elif msg_type == 'ping':
            await send_to_client(client_id, {
                'type': 'pong',
                'timestamp': datetime.now().isoformat()
            })
        elif msg_type == 'get_room_status':
            client = client_connections.get(client_id)
            if client:
                room_status = get_room_status(client.room_id)
                await send_to_client(client_id, {
                    'type': 'room_status',
                    'data': room_status,
                    'timestamp': datetime.now().isoformat()
                })
        else:
            await send_to_client(client_id, {
                'type': 'error',
                'message': f'未知的消息类型: {msg_type}'
            })
            
    except json.JSONDecodeError:
        await send_to_client(client_id, {
            'type': 'error',
            'message': '无效的JSON格式'
        })
    except Exception as e:
        logger.exception(f"Error handling text message from {client_id}: {e}")
        await send_to_client(client_id, {
            'type': 'error',
            'message': f'处理消息时出错: {str(e)}'
        })


async def handle_start_transcription(client_id: str, data: Dict[str, Any]):
    """处理启动转文字请求"""
    client = client_connections.get(client_id)
    if not client:
        logger.warning(f"Unknown client {client_id} requesting transcription start")
        return
    
    requester_user = {
        'id': client.user_id,
        'username': client.username,
        'nickname': client.username
    }
    
    voice_config = data.get('voice_config', {})
    
    # 获取语音频道信息
    channel_id = data.get('channel_id')  # 前端需要提供语音频道ID
    if not channel_id:
        await send_to_client(client_id, {
            'type': 'transcription_start_result',
            'success': False,
            'error': '缺少语音频道ID，Bot需要知道要加入哪个语音频道进行监听',
            'timestamp': datetime.now().isoformat()
        })
        return
    
    room_config = {
        'room_id': client.room_id,
        'channel_id': channel_id,  # 语音频道ID，Bot将加入这个频道
        'type': 'livekit',  # 使用 LiveKit
        'name': f'实时转文字-{client.room_id}',
        'created_by': client.user_id,
        'created_by_name': client.username
    }
    
    result = await create_voice_session(room_config, voice_config)
    
    if result.get('success'):
        client.session_id = result.get('session_id')
        logger.info(f"Transcription started for room {client.room_id} with Bot monitoring channel {channel_id}")
        
        # 广播会话开始消息
        await broadcast_to_room(client.room_id, {
            'type': 'transcription_started',
            'session_id': result.get('session_id'),
            'room_id': client.room_id,
            'channel_id': channel_id,
            'started_by': client.username,
            'message': f'语音转文字已启动，Bot正在监听频道 {channel_id}',
            'timestamp': datetime.now().isoformat()
        })
    
    await send_to_client(client_id, {
        'type': 'transcription_start_result',
        'success': result.get('success'),
        'data': result,
        'timestamp': datetime.now().isoformat()
    })


async def handle_stop_transcription(client_id: str, data: Dict[str, Any]):
    """处理停止转文字请求"""
    client = client_connections.get(client_id)
    if not client:
        return
    
    if client.room_id not in room_sessions:
        await send_to_client(client_id, {
            'type': 'transcription_stop_result',
            'success': False,
            'data': {'error': '房间没有活跃的转文字会话'},
            'timestamp': datetime.now().isoformat()
        })
        return
    
    session_id = room_sessions[client.room_id]
    result = await stop_voice_session(session_id)
    
    await send_to_client(client_id, {
        'type': 'transcription_stop_result',
        'success': result.get('success'),
        'data': result,
        'timestamp': datetime.now().isoformat()
    })


# REST API 端点
@router.post("/sessions", response_model=VoiceSessionResponse)
async def create_session_api(
    session_data: VoiceSessionCreate,
    user: CurrentUser,
    _: None = Depends(require_permission(2))
):
    """创建语音识别会话 (REST API)"""
    try:
        room_config = session_data.room_config.copy()
        room_config["created_by"] = user.get("id")
        room_config["created_by_name"] = user.get("nickname") or user.get("username")
        
        result = await create_voice_session(room_config, session_data.voice_config)
        
        if not result.get('success'):
            error_msg = result.get('error', '创建语音识别会话失败')
            
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


@router.delete("/sessions/{session_id}")
async def stop_session_api(
    session_id: str,
    user: CurrentUser,
    _: None = Depends(require_permission(2))
):
    """停止语音识别会话 (REST API)"""
    try:
        result = await stop_voice_session(session_id)
        
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


@router.get("/sessions")
async def list_sessions_api(
    user: CurrentUser,
    _: None = Depends(require_permission(2))
):
    """获取所有语音识别会话"""
    try:
        sessions = [session.to_dict() for session in ACTIVE_SESSIONS.values()]
        return {
            'success': True,
            'total': len(sessions),
            'sessions': sessions
        }
    except Exception as e:
        logger.exception(f"Error listing sessions: {e}")
        raise HTTPException(status_code=500, detail="获取会话列表失败")


@router.get("/sessions/{session_id}")
async def get_session_detail_api(
    session_id: str,
    user: CurrentUser,
    _: None = Depends(require_permission(1))
):
    """获取会话详情"""
    try:
        if session_id not in ACTIVE_SESSIONS:
            raise HTTPException(status_code=404, detail="会话不存在")
            
        session = ACTIVE_SESSIONS[session_id]
        bot = BOT_INSTANCES.get(session_id)
        
        return {
            'success': True,
            'session': session.to_dict(),
            'speakers': session.speakers,
            'results_preview': session.results[-5:] if session.results else [],
            'bot_status': {
                'connected': bot.connected if bot else False,
                'type': bot.bot_type if bot else None,
                'room_id': bot.room_id if bot else None
            }
        }
        
    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"Error getting session detail: {e}")
        raise HTTPException(status_code=500, detail="获取会话详情失败")


@router.get("/status")
async def get_system_status(
    user: CurrentUser,
    _: None = Depends(require_permission(2))
):
    """获取系统状态"""
    global _current_active_room
    
    try:
        return {
            'success': True,
            'status': 'running',
            'timestamp': datetime.now().isoformat(),
            'stats': {
                'active_sessions': len(ACTIVE_SESSIONS),
                'active_bots': len(BOT_INSTANCES),
                'websocket_rooms': len(room_clients),
                'websocket_clients': sum(len(clients) for clients in room_clients.values()),
                'executor_threads': getattr(EXECUTOR, '_threads', 'unknown')
            },
            'global_lock': {
                'is_locked': _current_active_room is not None,
                'active_room_id': _current_active_room,
                'message': f'服务被房间 {_current_active_room} 占用' if _current_active_room else '服务可用'
            }
        }
    except Exception as e:
        logger.exception(f"Error getting system status: {e}")
        raise HTTPException(status_code=500, detail="获取系统状态失败")


@router.get("/rooms/{room_id}/status")
async def get_room_status_api(
    room_id: str,
    user: CurrentUser,
    _: None = Depends(require_permission(1))
):
    """获取房间状态"""
    try:
        status = get_room_status(room_id)
        return {'success': True, 'data': status}
    except Exception as e:
        logger.exception(f"Error getting room status: {e}")
        raise HTTPException(status_code=500, detail="获取房间状态失败")


@router.get("/health")
async def health_check():
    """健康检查"""
    return {
        "status": "healthy",
        "voice_service": "integrated",
        "timestamp": datetime.now().isoformat()
    }


def get_room_status(room_id: str) -> Dict[str, Any]:
    """获取房间状态"""
    clients = room_clients.get(room_id, [])
    session_id = room_sessions.get(room_id)
    
    return {
        'room_id': room_id,
        'client_count': len(clients),
        'clients': [
            {
                'user_id': client.user_id,
                'username': client.username,
                'connected_at': client.connected_at.isoformat(),
                'last_activity': client.last_activity.isoformat()
            }
            for client in clients
        ],
        'has_active_session': session_id is not None,
        'session_id': session_id
    }


class TranscriptionManager:
    """转录结果管理器 - 处理WebSocket广播"""
    
    def __init__(self):
        self.connections: Dict[str, List[WebSocket]] = {}  # session_id -> [websockets]
        self._lock = asyncio.Lock()
    
    async def add_connection(self, session_id: str, websocket: WebSocket):
        """添加WebSocket连接"""
        async with self._lock:
            if session_id not in self.connections:
                self.connections[session_id] = []
            self.connections[session_id].append(websocket)
            logger.info(f"Added WebSocket connection for session {session_id}")
    
    async def remove_connection(self, session_id: str, websocket: WebSocket):
        """移除WebSocket连接"""
        async with self._lock:
            if session_id in self.connections:
                try:
                    self.connections[session_id].remove(websocket)
                    if not self.connections[session_id]:
                        del self.connections[session_id]
                    logger.info(f"Removed WebSocket connection for session {session_id}")
                except ValueError:
                    pass
    
    async def broadcast_transcription_result(self, session_id: str, result_data: Dict[str, Any]):
        """广播转录结果到所有连接的客户端"""
        if session_id not in self.connections:
            return
        
        message = {
            "type": "transcription_result",
            "session_id": session_id,
            "data": result_data,
            "timestamp": datetime.now().isoformat()
        }
        
        # 获取连接列表的副本以避免并发修改
        async with self._lock:
            connections = self.connections.get(session_id, []).copy()
        
        # 广播给所有连接
        for websocket in connections:
            try:
                await websocket.send_text(json.dumps(message, ensure_ascii=False))
            except Exception as e:
                logger.warning(f"Failed to send message to WebSocket: {e}")
                # 移除失效的连接
                await self.remove_connection(session_id, websocket)


# 全局转录管理器实例
transcription_manager = TranscriptionManager()


@router.websocket("/ws/{session_id}")
async def transcription_websocket(
    websocket: WebSocket,
    session_id: str,
    token: str = Query(..., description="认证token")
):
    """转录结果WebSocket连接"""
    try:
        # 验证token（可以根据实际需求调整验证逻辑）
        # user = await get_current_user_from_token(token)
        # if not user:
        #     await websocket.close(code=1008, reason="Invalid token")
        #     return
        
        await websocket.accept()
        await transcription_manager.add_connection(session_id, websocket)
        
        try:
            while True:
                # 接收客户端消息（心跳等）
                message = await websocket.receive_text()
                
                try:
                    data = json.loads(message)
                    msg_type = data.get("type")
                    
                    if msg_type == "ping":
                        # 回复心跳
                        await websocket.send_text(json.dumps({
                            "type": "pong",
                            "timestamp": datetime.now().isoformat()
                        }))
                    elif msg_type == "get_status":
                        # 获取会话状态
                        from ..services.voice_recognition import voice_service
                        status = await voice_service.get_session_detail(session_id)
                        await websocket.send_text(json.dumps({
                            "type": "status",
                            "data": status
                        }))
                    
                except json.JSONDecodeError:
                    await websocket.send_text(json.dumps({
                        "type": "error",
                        "message": "Invalid JSON format"
                    }))
                
        except WebSocketDisconnect:
            pass
        
    except Exception as e:
        logger.exception(f"WebSocket error: {e}")
    finally:
        await transcription_manager.remove_connection(session_id, websocket)
