from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path
from typing import Any

from pydantic_settings import BaseSettings, SettingsConfigDict


RUNTIME_ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = RUNTIME_ROOT / "config.json"

DEFAULT_CONFIG: dict[str, Any] = {
    "database_url": "sqlite+aiosqlite:///./discord.db",
    "sso_base_url": "https://sso.rms.net.cn",
    "sso_verify_endpoint": "/api/user",
    "host": "0.0.0.0",
    "port": 8000,
    "debug": True,
    "frontend_dist_path": "../packages/web/dist",
    "cors_origins": ["http://localhost:5173", "http://127.0.0.1:5173"],
    "deploy_token": "",
    "livekit_host": "ws://localhost:7880",
    "livekit_internal_host": "ws://127.0.0.1:7880",
    "livekit_api_key": "rms_discord",
    "livekit_api_secret": "rmsdiscordsecretkey123456",
    "voice_server_url": "http://localhost:8001",  # 语音识别服务器端口
    "voice_service_url": "http://localhost:5000",  # 独立语音转文字服务地址
    "voice_callback_base_url": "http://localhost:8000/api/voice-recognition/callback",  # 回调地址
}


def _load_config() -> dict[str, Any]:
    if not CONFIG_PATH.exists():
        try:
            CONFIG_PATH.write_text(json.dumps(DEFAULT_CONFIG, indent=2), encoding="utf-8")
        except OSError:
            pass
        return DEFAULT_CONFIG.copy()

    try:
        payload = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return DEFAULT_CONFIG.copy()

    merged = DEFAULT_CONFIG.copy()
    if isinstance(payload, dict):
        merged.update(payload)
    return merged


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore")

    database_url: str = "sqlite+aiosqlite:///./discord.db"
    sso_base_url: str = "https://sso.rms.net.cn"
    sso_verify_endpoint: str = "/api/user"
    host: str = "0.0.0.0"
    port: int = 8000
    debug: bool = True
    frontend_dist_path: str = "../packages/web/dist"
    cors_origins: list[str] = ["http://localhost:5173"]
    deploy_token: str = ""
    livekit_host: str = "ws://localhost:7880"
    livekit_internal_host: str = "ws://127.0.0.1:7880"
    livekit_api_key: str = "rms_discord"
    livekit_api_secret: str = "rmsdiscordsecretkey123456"
    voice_server_url: str = "http://api.hurrybili1016hjh.cc:5000"  # 语音识别服务器端口
    voice_service_url: str = "http://api.hurrybili1016hjh.cc:5000"  # 独立语音转文字服务地址
    voice_callback_base_url: str = "http://preview-chatroom.rms.net.cn/api/voice-recognition/callback"  # 回调地址


def _env_overrides() -> dict[str, Any]:
    overrides: dict[str, Any] = {}
    for field in Settings.model_fields:
        env_key = field.upper()
        if env_key in os.environ:
            val = os.environ[env_key]
            if field == "cors_origins":
                overrides[field] = [x.strip() for x in val.split(",")]
            else:
                overrides[field] = val
    return overrides


@lru_cache
def get_settings() -> Settings:
    base = _load_config()
    base.update(_env_overrides())
    return Settings(**base)
