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
    # OAuth 2.0 configuration
    "oauth_base_url": "https://sso.rms.net.cn",
    "oauth_authorize_endpoint": "/oauth/authorize",
    "oauth_token_endpoint": "/oauth/token",
    "oauth_userinfo_endpoint": "/oauth/userinfo",
    "oauth_client_id": "",
    "oauth_client_secret": "",
    "oauth_redirect_uri": "http://localhost:8000/api/auth/callback",
    "oauth_scope": "openid profile",
    # JWT configuration
    "jwt_secret": "",
    "jwt_algorithm": "HS256",
    "access_token_expire_minutes": 60,
    "refresh_token_expire_days": 30,
    # Server configuration
    "host": "0.0.0.0",
    "port": 8000,
    "debug": True,
    "frontend_dist_path": "../packages/web/dist",
    "cors_origins": ["http://localhost:5173", "http://127.0.0.1:5173"],
    "deploy_token": "",
    "livekit_host": "ws://localhost:7880",
    "livekit_internal_host": "ws://127.0.0.1:7880",
    "livekit_api_key": "rms_discord",  # intentionally empty in public repo
    "livekit_api_secret": "rmsdiscordsecretkey123456",  # do NOT store secrets in repo
    "voice_server_url": "",  # set via ENV or config.json in deployment
    "voice_service_url": "",  # set via ENV or config.json in deployment
    "voice_callback_base_url": ""  # set via ENV or config.json in deployment
}


def _load_config() -> dict[str, Any]:
    # If config.json does not exist, do NOT create one automatically with defaults
    # to avoid leaking secrets in public repositories or creating unintended files.
    if not CONFIG_PATH.exists():
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
    # OAuth 2.0 configuration
    oauth_base_url: str = "https://sso.rms.net.cn"
    oauth_authorize_endpoint: str = "/oauth/authorize"
    oauth_token_endpoint: str = "/oauth/token"
    oauth_userinfo_endpoint: str = "/oauth/userinfo"
    oauth_client_id: str = ""
    oauth_client_secret: str = ""
    oauth_redirect_uri: str = "http://localhost:8000/api/auth/callback"
    oauth_scope: str = "openid profile"
    # JWT configuration
    jwt_secret: str = ""
    jwt_algorithm: str = "HS256"
    access_token_expire_minutes: int = 60
    refresh_token_expire_days: int = 30
    # Server configuration
    host: str = "0.0.0.0"
    port: int = 8000
    debug: bool = True
    frontend_dist_path: str = "../packages/web/dist"
    cors_origins: list[str] = ["http://localhost:5173"]
    deploy_token: str = ""
    livekit_host: str = "ws://localhost:7880"
    livekit_internal_host: str = "ws://127.0.0.1:7880"
    livekit_api_key: str = ""
    livekit_api_secret: str = ""
    voice_server_url: str = ""  # set via config.json or VOICE_SERVER_URL env
    voice_service_url: str = ""  # set via config.json or VOICE_SERVICE_URL env
    voice_callback_base_url: str = ""  # set via config.json or VOICE_CALLBACK_BASE_URL env


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
