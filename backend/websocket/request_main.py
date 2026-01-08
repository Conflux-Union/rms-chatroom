#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阿里云语音识别服务客户端
支持实时转录、说话人管理、音频流热插拔、时间同步等功能
"""

import json
import time
import base64
import logging
import threading
import requests
from typing import List, Optional, Dict, Any, Callable
from urllib.parse import urljoin

try:
    import sseclient
except ImportError:
    sseclient = None

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
logger = logging.getLogger(__name__)


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
        """
        启动音频转录任务
        
        Args:
            callback_url: 结果回调地址
            audio_tracks: 音频文件路径列表，支持多轨
            ffmpeg_path: FFmpeg可执行文件路径（可选）
            
        Returns:
            服务器响应
        """
        data = {
            'callback_url': callback_url,
            'audio_tracks': audio_tracks
        }
        if ffmpeg_path:
            data['ffmpeg_path'] = ffmpeg_path
            
        logger.info(f"Starting transcription with {len(audio_tracks)} audio tracks")
        response = self._make_request('POST', 'trainsction', json=data)
        return response.json()
    
    def stop_transcription(self, session_id: str) -> Dict[str, Any]:
        """
        停止转录任务
        
        Args:
            session_id: 会话ID
            
        Returns:
            服务器响应
        """
        data = {'session_id': session_id}
        logger.info(f"Stopping transcription for session {session_id}")
        response = self._make_request('POST', 'stoptran', json=data)
        return response.json()
    
    def register_speaker_start(self, session_id: str, speaker_id: str, 
                             timestamp_ms: Optional[int] = None) -> Dict[str, Any]:
        """
        注册说话人开始说话事件
        
        Args:
            session_id: 会话ID
            speaker_id: 说话人标识
            timestamp_ms: 开始时间戳（毫秒），默认为当前时间
            
        Returns:
            服务器响应
        """
        data = {
            'session_id': session_id,
            'speaker_id': speaker_id
        }
        if timestamp_ms is not None:
            data['timestamp_ms'] = timestamp_ms
            
        logger.info(f"Registering speaker start: {speaker_id} in session {session_id}")
        response = self._make_request('POST', 'speakerstart', json=data)
        return response.json()
    
    def register_speaker_stop(self, session_id: str, speaker_id: str, 
                            timestamp_ms: Optional[int] = None) -> Dict[str, Any]:
        """
        注册说话人停止说话事件
        
        Args:
            session_id: 会话ID
            speaker_id: 说话人标识
            timestamp_ms: 停止时间戳（毫秒），默认为当前时间
            
        Returns:
            服务器响应
        """
        data = {
            'session_id': session_id,
            'speaker_id': speaker_id
        }
        if timestamp_ms is not None:
            data['timestamp_ms'] = timestamp_ms
            
        logger.info(f"Registering speaker stop: {speaker_id} in session {session_id}")
        response = self._make_request('POST', 'speakerstop', json=data)
        return response.json()
    
    def get_sentences(self, session_id: str, speaker_id: Optional[str] = None, 
                     include_unassigned: bool = True) -> Dict[str, Any]:
        """
        获取会话的句子
        
        Args:
            session_id: 会话ID
            speaker_id: 指定说话人ID（可选）
            include_unassigned: 是否包含未分配说话人的句子
            
        Returns:
            句子列表和统计信息
        """
        params = {'session_id': session_id, 'include_unassigned': str(include_unassigned).lower()}
        if speaker_id:
            params['speaker_id'] = speaker_id
            
        response = self._make_request('GET', 'sentences', params=params)
        return response.json()
    
    def get_session_stats(self, session_id: str) -> Dict[str, Any]:
        """
        获取会话统计信息
        
        Args:
            session_id: 会话ID
            
        Returns:
            会话统计信息
        """
        params = {'session_id': session_id}
        response = self._make_request('GET', 'session/stats', params=params)
        return response.json()
    
    def add_audio_stream(self, session_id: str, stream_index: int) -> Dict[str, Any]:
        """
        热插拔：添加音频流
        
        Args:
            session_id: 会话ID
            stream_index: 新流的索引
            
        Returns:
            服务器响应
        """
        data = {
            'session_id': session_id,
            'stream_index': stream_index
        }
        logger.info(f"Adding stream {stream_index} to session {session_id}")
        response = self._make_request('POST', 'streams/add', json=data)
        return response.json()
    
    def remove_audio_stream(self, session_id: str, stream_index: int) -> Dict[str, Any]:
        """
        热插拔：移除音频流
        
        Args:
            session_id: 会话ID
            stream_index: 要移除的流索引
            
        Returns:
            服务器响应
        """
        data = {
            'session_id': session_id,
            'stream_index': stream_index
        }
        logger.info(f"Removing stream {stream_index} from session {session_id}")
        response = self._make_request('POST', 'streams/remove', json=data)
        return response.json()
    
    def list_audio_streams(self, session_id: str) -> Dict[str, Any]:
        """
        获取当前活跃的音频流列表
        
        Args:
            session_id: 会话ID
            
        Returns:
            活跃音频流列表
        """
        params = {'session_id': session_id}
        response = self._make_request('GET', 'streams/list', params=params)
        return response.json()
    
    def push_audio_data(self, session_id: str, audio_data: bytes, 
                       stream_index: int = 0) -> Dict[str, Any]:
        """
        向指定音频流推送数据
        
        Args:
            session_id: 会话ID
            audio_data: 音频数据字节
            stream_index: 目标流索引，默认0
            
        Returns:
            服务器响应
        """
        audio_b64 = base64.b64encode(audio_data).decode('utf-8')
        data = {
            'session_id': session_id,
            'stream_index': stream_index,
            'audio_data': audio_b64
        }
        response = self._make_request('POST', 'streams/push', json=data)
        return response.json()
    
    def start_timesync(self, session_id: str) -> Dict[str, Any]:
        """
        启动时间同步会话
        
        Args:
            session_id: 会话ID
            
        Returns:
            服务器时间信息
        """
        data = {'session_id': session_id}
        logger.info(f"Starting timesync for session {session_id}")
        response = self._make_request('POST', 'timesync/start', json=data)
        return response.json()
    
    def stop_timesync(self, session_id: str) -> Dict[str, Any]:
        """
        停止时间同步会话
        
        Args:
            session_id: 会话ID
            
        Returns:
            服务器响应
        """
        data = {'session_id': session_id}
        logger.info(f"Stopping timesync for session {session_id}")
        response = self._make_request('POST', 'timesync/stop', json=data)
        return response.json()
    
    def timesync_heartbeat(self, session_id: str, 
                          callback: Callable[[Dict[str, Any]], None],
                          stop_event: Optional[threading.Event] = None) -> threading.Thread:
        """
        启动时间同步心跳监听（Server-Sent Events）
        
        Args:
            session_id: 会话ID
            callback: 心跳数据回调函数
            stop_event: 停止事件，用于外部控制停止
            
        Returns:
            心跳监听线程
        """
        def heartbeat_worker():
            url = urljoin(self.base_url + '/', f'timesync/heartbeat?session_id={session_id}')
            try:
                response = requests.get(url, stream=True, timeout=None)
                response.raise_for_status()
                
                if not sseclient:
                    logger.error("sseclient module not available")
                    return
                
                client = sseclient.SSEClient(response)
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
    """用于接收转录结果的简单HTTP服务器"""
    
    def __init__(self, host: str = 'localhost', port: int = 8080):
        """
        初始化回调服务器
        
        Args:
            host: 监听主机，默认localhost
            port: 监听端口，默认8080
        """
        self.host = host
        self.port = port
        self.results = []  # 存储接收到的结果
        self.result_handlers = []  # 结果处理器列表
        self._server_thread = None
        self._stop_event = threading.Event()
        
    def add_result_handler(self, handler: Callable[[Dict[str, Any]], None]):
        """添加结果处理器"""
        self.result_handlers.append(handler)
    
    def start(self):
        """启动回调服务器"""
        from flask import Flask, request, jsonify
        
        app = Flask(__name__)
        
        @app.route('/', methods=['POST'])
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
        
        def run_server():
            app.run(host=self.host, port=self.port, debug=False, use_reloader=False)
        
        self._server_thread = threading.Thread(target=run_server, daemon=True)
        self._server_thread.start()
        
        # 等待服务器启动
        time.sleep(1)
        logger.info(f"Callback server started at http://{self.host}:{self.port}")
    
    def get_callback_url(self) -> str:
        """获取回调URL"""
        return f"http://{self.host}:{self.port}/"
    
    def get_results(self) -> List[Dict[str, Any]]:
        """获取所有接收到的结果"""
        return self.results.copy()
    
    def clear_results(self):
        """清空结果"""
        self.results.clear()


# 使用示例和测试代码
def example_usage():
    """使用示例"""
    
    # 1. 创建客户端
    client = AliVoiceClient(base_url="http://localhost:5000")
    
    # 2. 创建回调服务器
    callback_server = CallbackServer(host='localhost', port=8080)
    
    # 添加结果处理器
    def handle_result(result):
        print(f"接收到转录结果: {json.dumps(result, ensure_ascii=False, indent=2)}")
    
    callback_server.add_result_handler(handle_result)
    callback_server.start()
    
    try:
        # 3. 启动转录任务
        audio_files = ["/path/to/audio.wav"]  # 替换为实际音频文件路径
        result = client.start_transcription(
            callback_url=callback_server.get_callback_url(),
            audio_tracks=audio_files
        )
        print(f"转录任务已启动: {result}")
        
        # 获取session_id（需要从回调结果中获取，或者使用阿里云返回的TaskId）
        session_id = "your_session_id"  # 替换为实际的session_id
        
        # 4. 启动时间同步
        timesync_result = client.start_timesync(session_id)
        print(f"时间同步已启动: {timesync_result}")
        
        # 5. 启动心跳监听
        def handle_heartbeat(data):
            print(f"心跳: {data}")
        
        stop_heartbeat = threading.Event()
        heartbeat_thread = client.timesync_heartbeat(session_id, handle_heartbeat, stop_heartbeat)
        
        # 6. 注册说话人事件
        client.register_speaker_start(session_id, "Speaker A")
        time.sleep(5)
        client.register_speaker_stop(session_id, "Speaker A")
        
        # 7. 获取转录句子
        sentences = client.get_sentences(session_id)
        print(f"获取到的句子: {sentences}")
        
        # 8. 获取会话统计
        stats = client.get_session_stats(session_id)
        print(f"会话统计: {stats}")
        
        # 9. 音频流管理示例
        streams = client.list_audio_streams(session_id)
        print(f"当前音频流: {streams}")
        
        # 添加新的音频流
        client.add_audio_stream(session_id, 1)
        
        # 推送音频数据
        audio_data = b"fake_audio_data"  # 替换为实际音频数据
        client.push_audio_data(session_id, audio_data, stream_index=1)
        
        # 等待一段时间让任务完成
        print("等待转录完成...")
        time.sleep(30)
        
        # 10. 停止任务
        stop_heartbeat.set()
        client.stop_timesync(session_id)
        client.stop_transcription(session_id)
        
        print("任务已完成")
        
    except Exception as e:
        logger.exception(f"示例运行出错: {e}")


if __name__ == "__main__":
    # 运行示例
    example_usage()
