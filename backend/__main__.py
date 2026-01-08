import sys
import threading
import time
import uvicorn
from .core.config import get_settings

settings = get_settings()


def start_voice_service():
    """启动语音识别服务"""
    try:
        # 等待主服务启动
        time.sleep(2)
        print("🎤 启动语音识别服务...")
        
        from .websocket.voice_server import app as voice_app
        voice_app.run(host='0.0.0.0', port=8001, debug=False, threaded=True, use_reloader=False)
    except Exception as e:
        print(f"语音识别服务启动失败: {e}")


if __name__ == "__main__":
    # 检查是否包含 --with-voice 参数
    start_voice = '--with-voice' in sys.argv
    if start_voice:
        sys.argv.remove('--with-voice')
        
        print("🚀 启动 RMS ChatRoom 后端服务 (包含语音识别)")
        print("主服务: http://localhost:8000")
        print("语音服务: http://localhost:8001")
        print("-" * 50)
        
        # 在后台线程启动语音服务
        voice_thread = threading.Thread(target=start_voice_service, daemon=True)
        voice_thread.start()
    else:
        print("🚀 启动 RMS ChatRoom 后端服务")
        print("提示: 使用 --with-voice 参数同时启动语音识别服务")
    
    uvicorn.run(
        "backend.app:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug,
    )
