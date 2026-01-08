#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
语音识别服务管理脚本
用于独立启动、停止语音识别服务
"""

import os
import sys
import argparse
import logging
import signal
import time
from pathlib import Path

# 添加后端路径到Python路径
backend_path = Path(__file__).parent
sys.path.insert(0, str(backend_path.parent))

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s [%(name)s] %(message)s')
logger = logging.getLogger(__name__)


def start_voice_service():
    """启动语音识别服务"""
    try:
        logger.info("正在启动语音识别服务...")
        
        # 导入Flask应用
        from backend.websocket.voice_server import app, ACTIVE_SESSIONS, BOT_INSTANCES
        
        # 优雅关闭处理
        def signal_handler(sig, frame):
            logger.info("收到关闭信号，正在清理资源...")
            
            # 停止所有会话
            for session_id in list(ACTIVE_SESSIONS.keys()):
                try:
                    session = ACTIVE_SESSIONS[session_id]
                    session.status = 'stopping'
                    
                    # 停止Bot
                    if session_id in BOT_INSTANCES:
                        bot = BOT_INSTANCES[session_id]
                        bot.stop()
                        
                    # 停止阿里云语音识别
                    if session.ali_client:
                        try:
                            session.ali_client.stop_transcription(session_id)
                        except Exception as e:
                            logger.warning(f"停止语音识别时出错: {e}")
                            
                    # 停止回调服务器
                    if session.callback_server:
                        try:
                            session.callback_server.stop()
                        except Exception as e:
                            logger.warning(f"停止回调服务器时出错: {e}")
                            
                except Exception as e:
                    logger.exception(f"清理会话 {session_id} 时出错: {e}")
            
            # 清理全局状态
            ACTIVE_SESSIONS.clear()
            BOT_INSTANCES.clear()
            
            logger.info("资源清理完成，服务即将退出")
            sys.exit(0)
        
        # 注册信号处理器
        signal.signal(signal.SIGINT, signal_handler)
        signal.signal(signal.SIGTERM, signal_handler)
        
        # 启动Flask应用
        app.run(host='0.0.0.0', port=8001, debug=False, threaded=True, use_reloader=False)
        
    except Exception as e:
        logger.exception(f"语音识别服务启动失败: {e}")
        return False
    
    return True


def check_service_health():
    """检查语音识别服务健康状态"""
    try:
        import httpx
        with httpx.Client(timeout=5.0) as client:
            response = client.get("http://localhost:8001/api/health")
            response.raise_for_status()
            
            health_data = response.json()
            logger.info(f"语音识别服务状态: {health_data}")
            return True
            
    except Exception as e:
        logger.error(f"健康检查失败: {e}")
        return False


def main():
    """主函数"""
    parser = argparse.ArgumentParser(description="语音识别服务管理")
    parser.add_argument('action', choices=['start', 'health'], help='执行的操作')
    
    args = parser.parse_args()
    
    if args.action == 'start':
        print("🎤 语音识别服务管理器")
        print("=" * 50)
        print("正在启动语音识别服务...")
        print("服务地址: http://0.0.0.0:8001")
        print("按 Ctrl+C 停止服务")
        print("=" * 50)
        
        success = start_voice_service()
        if not success:
            sys.exit(1)
            
    elif args.action == 'health':
        print("检查语音识别服务健康状态...")
        if check_service_health():
            print("✅ 服务运行正常")
            sys.exit(0)
        else:
            print("❌ 服务不可用")
            sys.exit(1)


if __name__ == "__main__":
    main()
