#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
服务启动脚本
同时启动主后端服务和语音识别服务
"""

import os
import sys
import time
import logging
import subprocess
import threading
from pathlib import Path

# 添加后端路径到Python路径
backend_path = Path(__file__).parent
sys.path.insert(0, str(backend_path.parent))

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s [%(name)s] %(message)s')
logger = logging.getLogger(__name__)


def start_main_backend():
    """启动主后端服务（FastAPI）"""
    logger.info("启动主后端服务...")
    try:
        # 使用uvicorn启动FastAPI应用
        import uvicorn
        from backend.core.config import get_settings
        
        settings = get_settings()
        uvicorn.run(
            "backend.app:app",
            host=settings.host,
            port=settings.port,
            reload=settings.debug,
            log_level="info"
        )
    except Exception as e:
        logger.exception(f"主后端服务启动失败: {e}")


def start_voice_service():
    """启动语音识别服务"""
    logger.info("启动语音识别服务...")
    try:
        # 等待一下，让主服务先启动
        time.sleep(2)
        
        # 导入并运行语音服务
        from backend.websocket.voice_server import app
        app.run(host='0.0.0.0', port=8001, debug=True, threaded=True, use_reloader=False)
        
    except Exception as e:
        logger.exception(f"语音识别服务启动失败: {e}")


def main():
    """主函数 - 启动所有服务"""
    print("🚀 RMS ChatRoom 后端服务启动中...")
    print("=" * 60)
    print("📋 服务列表:")
    print("  - 主后端服务 (FastAPI)    : http://localhost:8000")
    print("  - 语音识别服务 (Flask)    : http://localhost:8001") 
    print("=" * 60)
    
    # 创建线程启动语音服务
    voice_thread = threading.Thread(target=start_voice_service, daemon=True)
    voice_thread.start()
    
    # 在主线程启动FastAPI服务
    try:
        start_main_backend()
    except KeyboardInterrupt:
        logger.info("收到中断信号，正在关闭服务...")
    except Exception as e:
        logger.exception(f"服务运行出错: {e}")
    
    logger.info("所有服务已关闭")


if __name__ == "__main__":
    main()
