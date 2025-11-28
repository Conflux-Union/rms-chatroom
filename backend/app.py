from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse

from .core.config import get_settings

logger = logging.getLogger(__name__)
from .core.database import init_db
from .routers import auth, servers, channels, messages, system, music
from .websocket import chat, voice, music as music_ws


settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    # Set up music broadcast function
    music.set_ws_broadcast(music_ws.broadcast_music_state)
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
app.include_router(system.router)
app.include_router(music.router)

# WebSocket routes
app.include_router(chat.router)
app.include_router(voice.router)
app.include_router(music_ws.router)


# Health check
@app.get("/health")
async def health():
    return {"status": "ok"}


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
    uvicorn.run(
        "backend.app:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug,
    )
