"""Android app update routes."""
from __future__ import annotations

import json
from pathlib import Path

from fastapi import APIRouter, HTTPException, status
from fastapi.responses import FileResponse

from ..core.config import RUNTIME_ROOT


router = APIRouter(prefix="/api/app/android", tags=["app"])

ANDROID_APP_DIR = RUNTIME_ROOT / "android_app"
VERSION_FILE = ANDROID_APP_DIR / "version.json"
APK_FILE = ANDROID_APP_DIR / "rms-chatroom.apk"


@router.get("/checkupdate")
async def check_update() -> dict:
    """
    Check for Android app updates.
    
    Returns version info from version.json.
    """
    if not VERSION_FILE.exists():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Version info not found",
        )
    
    try:
        version_info = json.loads(VERSION_FILE.read_text())
    except json.JSONDecodeError:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Invalid version info",
        )
    
    return {
        "version_code": version_info.get("version_code", 0),
        "version_name": version_info.get("version_name", "unknown"),
        "changelog": version_info.get("changelog", ""),
        "force_update": version_info.get("force_update", False),
        "download_url": "/api/app/android/download",
    }


@router.get("/download")
async def download_apk() -> FileResponse:
    """Download the latest APK."""
    if not APK_FILE.exists():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="APK file not found",
        )
    
    return FileResponse(
        path=APK_FILE,
        filename="rms-chatroom.apk",
        media_type="application/vnd.android.package-archive",
    )
