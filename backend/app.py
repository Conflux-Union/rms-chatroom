from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse

from .core.config import get_settings

logger = logging.getLogger(__name__)
from .core.database import init_db
from .routers import auth, servers, channels, messages, files, system, music, bug_report, app_update, voice_recognition
from .websocket import chat, voice, music as music_ws, transcription


settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    # Set up music broadcast function
    music.set_ws_broadcast(music_ws.broadcast_music_state)
    # Set up callback for late joiners to get current playback state
    music_ws.set_get_room_playback_state(music.get_room_playback_state)
    yield


app = FastAPI(title="RMS ChatRoom", lifespan=lifespan)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    """Log validation errors with request body for debugging."""
    body = await request.body()
    logger.error(f"Validation error: {exc.errors()}")
    logger.error(f"Request body: {body!r}")
    logger.error(f"Request path: {request.url.path}")
    return JSONResponse(
        status_code=422,
        content={"detail": exc.errors()},
    )


# CORS for development
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# API routes
app.include_router(auth.router)
app.include_router(servers.router)
app.include_router(channels.router)
app.include_router(messages.router)
app.include_router(files.router)
app.include_router(system.router)
app.include_router(music.router)
app.include_router(bug_report.router)
app.include_router(app_update.router)
app.include_router(voice_recognition.router)

# WebSocket routes
app.include_router(chat.router)
app.include_router(voice.router)
app.include_router(music_ws.router)
app.include_router(transcription.router)


# Health check
@app.get("/health")
async def health():
    """健康检查接口，返回服务状态"""
    health_status = {
        "status": "ok",
        "timestamp": datetime.now().isoformat(),
        "services": {
            "main_backend": "ok",
            "database": "unknown",
            "voice_recognition": "unknown"
        }
    }
    
    # 检查数据库连接
    try:
        from .core.database import get_db
        # 这里可以添加具体的数据库健康检查
        health_status["services"]["database"] = "ok"
    except Exception:
        health_status["services"]["database"] = "error"
        health_status["status"] = "degraded"
    
    # 检查语音识别服务
    try:
        from .services.voice_recognition import voice_service
        # 检查语音服务是否可用
        health_status["services"]["voice_recognition"] = "ok"
    except Exception:
        health_status["services"]["voice_recognition"] = "error"
    
    return health_status


@app.get("/health/detailed")
async def health_detailed():
    """详细健康检查，包含各个组件状态"""
    try:
        import sys
        
        # 基本系统信息
        system_info = {
            "python_version": sys.version,
            "platform": sys.platform
        }
        
        # 尝试获取更多系统信息（如果可用）
        try:
            import psutil
            system_info.update({
                "memory_usage": psutil.virtual_memory()._asdict(),
                "disk_usage": psutil.disk_usage('/')._asdict(),
                "cpu_percent": psutil.cpu_percent(interval=1),
                "process_count": len(psutil.pids())
            })
        except ImportError:
            system_info["note"] = "psutil not available for detailed system metrics"
        
        # 服务状态
        services_status = {
            "main_backend": {"status": "ok", "port": settings.port},
            "voice_recognition_api": {"status": "available", "endpoint": "/api/voice-recognition"},
        }
        
        return {
            "status": "ok",
            "timestamp": datetime.now().isoformat(),
            "version": "1.0.0",
            "system": system_info,
            "services": services_status,
            "config": {
                "debug": settings.debug,
                "host": settings.host,
                "port": settings.port
            }
        }
    except Exception as e:
        return {
            "status": "error",
            "error": str(e),
            "timestamp": datetime.now().isoformat()
        }


# Serve frontend in production
frontend_dist = Path(settings.frontend_dist_path)
if frontend_dist.exists() and frontend_dist.is_dir():
    # Serve static assets
    app.mount("/assets", StaticFiles(directory=frontend_dist / "assets"), name="assets")

    @app.get("/{full_path:path}")
    async def serve_spa(full_path: str):
        # API routes are handled above, this catches everything else
        file_path = frontend_dist / full_path
        if file_path.exists() and file_path.is_file():
            return FileResponse(file_path)
        return FileResponse(frontend_dist / "index.html")


if __name__ == "__main__":
    import uvicorn
    import logging
    
    # 设置日志
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s [%(name)s] %(message)s')
    logger = logging.getLogger(__name__)

    try:
        uvicorn.run(
            "backend.app:app",
            host=settings.host,
            port=settings.port,
            reload=settings.debug,
            log_level="info"
        )
    except KeyboardInterrupt:
        logger.info("收到中断信号，正在关闭服务...")
    except Exception as e:
        logger.exception(f"服务运行出错: {e}")
    
    logger.info("服务已关闭")
