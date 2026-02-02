#!/usr/bin/env python
"""
RMS Discord Standalone Entry Point

This script serves as the entry point for the standalone executable.
It handles:
- First-run configuration generation
- Embedded frontend resources extraction
- Database initialization
- Server startup
"""

from __future__ import annotations

import json
import logging
import os
import secrets
import shutil
import sys
from pathlib import Path

# Import backend modules at top level for PyInstaller analysis
# These imports ensure all dependencies are detected during bundling
import uvicorn
import sqlalchemy
import sqlalchemy.ext.asyncio
import sqlalchemy.dialects.sqlite
import aiosqlite
import fastapi
import starlette
import pydantic
import alembic
import websockets

# Determine if running as PyInstaller bundle
if getattr(sys, 'frozen', False) and hasattr(sys, '_MEIPASS'):
    # Running as PyInstaller bundle
    BUNDLE_DIR = Path(sys._MEIPASS)
    RUNTIME_DIR = Path(sys.executable).parent
    IS_BUNDLED = True
else:
    # Running as normal Python script
    BUNDLE_DIR = Path(__file__).parent
    RUNTIME_DIR = Path(__file__).parent
    IS_BUNDLED = False

# Configuration paths
CONFIG_FILE = RUNTIME_DIR / "config.json"
DATABASE_FILE = RUNTIME_DIR / "discord.db"
FRONTEND_DIST = RUNTIME_DIR / "frontend_dist"
UPLOADS_DIR = RUNTIME_DIR / "uploads"

# Logging setup
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s [%(name)s] %(message)s'
)
logger = logging.getLogger(__name__)


def generate_secret_key() -> str:
    """Generate a secure random secret key."""
    return secrets.token_urlsafe(32)


def create_default_config() -> dict:
    """Create default configuration with secure random secrets."""
    return {
        "database_url": f"sqlite+aiosqlite:///{DATABASE_FILE}",
        # OAuth 2.0 configuration (user must fill these)
        "oauth_base_url": "https://sso.rms.net.cn",
        "oauth_authorize_endpoint": "/oauth/authorize",
        "oauth_token_endpoint": "/oauth/token",
        "oauth_userinfo_endpoint": "/oauth/userinfo",
        "oauth_client_id": "YOUR_CLIENT_ID_HERE",
        "oauth_client_secret": "YOUR_CLIENT_SECRET_HERE",
        "oauth_redirect_uri": "http://localhost:8000/api/auth/callback",
        "oauth_scope": "openid profile",
        # JWT configuration (auto-generated)
        "jwt_secret": generate_secret_key(),
        "jwt_algorithm": "HS256",
        "access_token_expire_minutes": 60,
        "refresh_token_expire_days": 30,
        # Server configuration
        "host": "0.0.0.0",
        "port": 8000,
        "debug": False,
        "frontend_dist_path": str(FRONTEND_DIST),
        "cors_origins": ["http://localhost:8000"],
        "deploy_token": generate_secret_key(),
        # LiveKit configuration (optional)
        "livekit_host": "ws://localhost:7880",
        "livekit_internal_host": "ws://127.0.0.1:7880",
        "livekit_api_key": "rms_discord",
        "livekit_api_secret": "rmsdiscordsecretkey123456",
        # Voice recognition (optional)
        "voice_server_url": "",
        "voice_service_url": "",
        "voice_callback_base_url": ""
    }


def init_config() -> bool:
    """Initialize configuration file if not exists."""
    if CONFIG_FILE.exists():
        logger.info(f"Configuration file found: {CONFIG_FILE}")
        return True

    logger.info("First run detected. Generating default configuration...")

    try:
        config = create_default_config()
        CONFIG_FILE.write_text(json.dumps(config, indent=2, ensure_ascii=False))
        logger.info(f"Configuration file created: {CONFIG_FILE}")
        logger.warning("=" * 60)
        logger.warning("IMPORTANT: Please edit config.json and set:")
        logger.warning("  - oauth_client_id")
        logger.warning("  - oauth_client_secret")
        logger.warning("  - oauth_redirect_uri (if not using localhost:8000)")
        logger.warning("=" * 60)
        return True
    except Exception as e:
        logger.error(f"Failed to create configuration file: {e}")
        return False


def extract_frontend() -> bool:
    """Extract embedded frontend resources if needed."""
    if FRONTEND_DIST.exists():
        logger.info(f"Frontend resources found: {FRONTEND_DIST}")
        return True

    logger.info("Extracting frontend resources...")

    try:
        # In bundled mode, frontend is in _MEIPASS/frontend_dist
        bundled_frontend = BUNDLE_DIR / "frontend_dist"

        if not bundled_frontend.exists():
            logger.warning("Frontend resources not found in bundle. Frontend will not be available.")
            return False

        # Copy frontend to runtime directory
        shutil.copytree(bundled_frontend, FRONTEND_DIST)
        logger.info(f"Frontend resources extracted to: {FRONTEND_DIST}")
        return True
    except Exception as e:
        logger.error(f"Failed to extract frontend resources: {e}")
        return False


def init_directories() -> bool:
    """Initialize required directories."""
    try:
        UPLOADS_DIR.mkdir(exist_ok=True)
        logger.info(f"Uploads directory ready: {UPLOADS_DIR}")
        return True
    except Exception as e:
        logger.error(f"Failed to create directories: {e}")
        return False


def check_config_validity() -> bool:
    """Check if configuration is valid (OAuth credentials set)."""
    try:
        config = json.loads(CONFIG_FILE.read_text())

        if config.get("oauth_client_id") == "YOUR_CLIENT_ID_HERE":
            logger.error("OAuth client_id not configured. Please edit config.json")
            return False

        if config.get("oauth_client_secret") == "YOUR_CLIENT_SECRET_HERE":
            logger.error("OAuth client_secret not configured. Please edit config.json")
            return False

        return True
    except Exception as e:
        logger.error(f"Failed to read configuration: {e}")
        return False


def start_server():
    """Start the FastAPI server."""
    # Override config path for backend
    os.environ['CONFIG_PATH'] = str(CONFIG_FILE)

    # Import app after setting environment
    from backend.app import app, settings

    logger.info("=" * 60)
    logger.info(f"RMS Discord Server Starting")
    logger.info(f"Version: {getattr(sys, '_version', 'unknown')}")
    logger.info(f"Runtime Directory: {RUNTIME_DIR}")
    logger.info(f"Configuration: {CONFIG_FILE}")
    logger.info(f"Database: {DATABASE_FILE}")
    logger.info(f"Frontend: {FRONTEND_DIST}")
    logger.info(f"Server: http://{settings.host}:{settings.port}")
    logger.info("=" * 60)

    try:
        uvicorn.run(
            app,
            host=settings.host,
            port=settings.port,
            log_level="info"
        )
    except KeyboardInterrupt:
        logger.info("Received interrupt signal, shutting down...")
    except Exception as e:
        logger.exception(f"Server error: {e}")
        sys.exit(1)

    logger.info("Server stopped")


def main():
    """Main entry point."""
    logger.info("RMS Discord Standalone")
    logger.info(f"Running mode: {'Bundled' if IS_BUNDLED else 'Development'}")

    # Step 1: Initialize configuration
    if not init_config():
        logger.error("Failed to initialize configuration")
        sys.exit(1)

    # Step 2: Check configuration validity
    if not check_config_validity():
        logger.error("Configuration is invalid. Please edit config.json and restart.")
        input("Press Enter to exit...")
        sys.exit(1)

    # Step 3: Extract frontend resources
    if IS_BUNDLED:
        extract_frontend()

    # Step 4: Initialize directories
    if not init_directories():
        logger.error("Failed to initialize directories")
        sys.exit(1)

    # Step 5: Start server
    start_server()


if __name__ == "__main__":
    main()
