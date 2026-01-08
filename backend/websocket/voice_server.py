#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
语音识别服务器后端 - 简化版本
处理前端请求，管理语音室Bot，整合阿里云语音识别服务
"""

import os
import json
import time
import logging
import threading
import uuid
from typing import Dict, List, Optional, Any
from datetime import datetime
from flask import Flask, request, jsonify
import asyncio
from concurrent.futures import ThreadPoolExecutor

# 导入现有的客户端
from request_main import AliVoiceClient, CallbackServer

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__)

# 全局存储
ACTIVE_SESSIONS = {}
BOT_INSTANCES = {}
EXECUTOR = ThreadPoolExecutor(max_workers=10)


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
        self.ali_client = None
        self.callback_server = None
        self.bot_instance = None
        
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
        logger.info(f"Creating {room_type} bot for session {session_id}")
        
        # 根据不同平台创建不同的Bot
        # 这里可以扩展支持Discord、Zoom、腾讯会议等
        bot = WebRTCBot(session_id, room_config)
        
        # 设置音频处理器
        audio_handler = AudioHandler(session_id)
        bot.set_audio_handler(audio_handler)
        
        return bot
        
    except Exception as e:
        logger.exception(f"Failed to create bot: {e}")
        return None


def initialize_session_async(session_id: str):
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


@app.route('/api/sessions', methods=['POST'])
def create_session():
    """创建新的语音识别会话"""
    try:
        data = request.get_json()
        
        # 解析配置
        room_config = data.get('room_config', {})
        voice_config = data.get('voice_config', {})
        
        # 生成会话ID
        session_id = str(uuid.uuid4())
        room_id = room_config.get('room_id', f'room_{session_id[:8]}')
        
        # 创建会话
        session = VoiceSession(session_id, room_id, {
            'room_config': room_config,
            'voice_config': voice_config
        })
        
        ACTIVE_SESSIONS[session_id] = session
        
        # 异步初始化
        EXECUTOR.submit(initialize_session_async, session_id)
        
        logger.info(f"Created session {session_id} for room {room_id}")
        
        return jsonify({
            'success': True,
            'session_id': session_id,
            'room_id': room_id,
            'status': 'initializing',
            'message': 'Session created successfully'
        }), 201
        
    except Exception as e:
        logger.exception(f"Error creating session: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/api/sessions', methods=['GET'])
def list_sessions():
    """获取所有会话列表"""
    try:
        sessions = [session.to_dict() for session in ACTIVE_SESSIONS.values()]
        return jsonify({
            'success': True,
            'total': len(sessions),
            'sessions': sessions
        })
    except Exception as e:
        logger.exception(f"Error listing sessions: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/api/sessions/<session_id>', methods=['GET'])
def get_session_detail(session_id: str):
    """获取会话详情"""
    try:
        if session_id not in ACTIVE_SESSIONS:
            return jsonify({'error': 'Session not found'}), 404
            
        session = ACTIVE_SESSIONS[session_id]
        bot = BOT_INSTANCES.get(session_id)
        
        response_data = {
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
        
        return jsonify(response_data)
        
    except Exception as e:
        logger.exception(f"Error getting session detail: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/api/sessions/<session_id>/results', methods=['GET'])
def get_session_results(session_id: str):
    """获取会话识别结果"""
    try:
        if session_id not in ACTIVE_SESSIONS:
            return jsonify({'error': 'Session not found'}), 404
            
        session = ACTIVE_SESSIONS[session_id]
        
        # 分页参数
        page = int(request.args.get('page', 1))
        per_page = min(int(request.args.get('per_page', 50)), 200)
        
        # 计算分页
        total = len(session.results)
        start_idx = (page - 1) * per_page
        end_idx = min(start_idx + per_page, total)
        
        results = session.results[start_idx:end_idx]
        
        return jsonify({
            'success': True,
            'session_id': session_id,
            'pagination': {
                'page': page,
                'per_page': per_page,
                'total': total,
                'pages': (total + per_page - 1) // per_page
            },
            'results': results
        })
        
    except Exception as e:
        logger.exception(f"Error getting session results: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/api/sessions/<session_id>/speakers', methods=['POST'])
def manage_speaker(session_id: str):
    """管理说话人状态"""
    try:
        if session_id not in ACTIVE_SESSIONS:
            return jsonify({'error': 'Session not found'}), 404
            
        data = request.get_json()
        action = data.get('action')  # 'start' or 'stop'
        speaker_id = data.get('speaker_id')
        timestamp_ms = data.get('timestamp_ms')
        
        if not action or not speaker_id:
            return jsonify({'error': 'Missing action or speaker_id'}), 400
            
        session = ACTIVE_SESSIONS[session_id]
        if not session.ali_client:
            return jsonify({'error': 'Voice service not ready'}), 400
            
        if action == 'start':
            result = session.ali_client.register_speaker_start(session_id, speaker_id, timestamp_ms)
        elif action == 'stop':
            result = session.ali_client.register_speaker_stop(session_id, speaker_id, timestamp_ms)
        else:
            return jsonify({'error': 'Invalid action'}), 400
            
        return jsonify({
            'success': True,
            'action': action,
            'speaker_id': speaker_id,
            'result': result
        })
        
    except Exception as e:
        logger.exception(f"Error managing speaker: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/api/sessions/<session_id>', methods=['DELETE'])
def stop_session(session_id: str):
    """停止并删除会话"""
    try:
        if session_id not in ACTIVE_SESSIONS:
            return jsonify({'error': 'Session not found'}), 404
            
        session = ACTIVE_SESSIONS[session_id]
        session.status = 'stopping'
        
        # 停止Bot
        if session_id in BOT_INSTANCES:
            bot = BOT_INSTANCES[session_id]
            bot.stop()
            del BOT_INSTANCES[session_id]
            
        # 停止阿里云语音识别
        if session.ali_client:
            try:
                session.ali_client.stop_transcription(session_id)
            except Exception as e:
                logger.warning(f"Error stopping transcription: {e}")
                
        # 清理会话
        session.status = 'stopped'
        del ACTIVE_SESSIONS[session_id]
        
        logger.info(f"Session {session_id} stopped and cleaned up")
        
        return jsonify({
            'success': True,
            'message': 'Session stopped successfully'
        })
        
    except Exception as e:
        logger.exception(f"Error stopping session: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/api/status', methods=['GET'])
def system_status():
    """获取系统状态"""
    try:
        return jsonify({
            'success': True,
            'status': 'running',
            'timestamp': datetime.now().isoformat(),
            'stats': {
                'active_sessions': len(ACTIVE_SESSIONS),
                'active_bots': len(BOT_INSTANCES),
                'executor_threads': EXECUTOR._threads if hasattr(EXECUTOR, '_threads') else 'unknown'
            }
        })
    except Exception as e:
        logger.exception(f"Error getting system status: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/api/health', methods=['GET'])
def health_check():
    """健康检查端点"""
    return jsonify({
        'status': 'healthy',
        'timestamp': datetime.now().isoformat()
    })


# 错误处理
@app.errorhandler(404)
def not_found(error):
    return jsonify({'error': 'Endpoint not found'}), 404


@app.errorhandler(500)
def internal_error(error):
    return jsonify({'error': 'Internal server error'}), 500


if __name__ == '__main__':
    print("🎤 语音识别服务器后端启动中...")
    print("=" * 60)
    print("📋 API接口列表:")
    print("  POST   /api/sessions              - 创建语音识别会话")
    print("  GET    /api/sessions              - 获取会话列表")
    print("  GET    /api/sessions/<id>         - 获取会话详情")
    print("  GET    /api/sessions/<id>/results - 获取识别结果")
    print("  POST   /api/sessions/<id>/speakers- 管理说话人状态")
    print("  DELETE /api/sessions/<id>         - 停止并删除会话")
    print("  GET    /api/status               - 系统状态")
    print("  GET    /api/health               - 健康检查")
    print("=" * 60)
    print("🚀 服务器启动在: http://0.0.0.0:8001")
    print("📖 查看日志以获取更多信息...")
    print()
    
    app.run(host='0.0.0.0', port=8001, debug=True, threaded=True)
