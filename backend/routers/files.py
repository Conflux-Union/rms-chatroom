"""File upload and download API for chat attachments."""
from __future__ import annotations

import logging
import mimetypes
import os
import re
import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile, status
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..models.server import Attachment, Channel, ChannelType
from .deps import CurrentUser

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["files"])

# File storage configuration
UPLOAD_DIR = Path(__file__).parent.parent / "uploads"
MAX_FILE_SIZE = 10 * 1024 * 1024  # 10MB for all files

# Allowed file extensions by category
ALLOWED_IMAGES = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"}
ALLOWED_VIDEOS = {".mp4", ".webm", ".mov", ".avi", ".mkv"}
ALLOWED_AUDIO = {".mp3", ".wav", ".ogg", ".m4a", ".flac"}
ALLOWED_DOCS = {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".md"}
ALLOWED_ARCHIVES = {".zip", ".rar", ".7z", ".tar", ".gz"}

# Blocked extensions (security)
BLOCKED_EXTENSIONS = {".exe", ".bat", ".cmd", ".sh", ".ps1", ".vbs", ".js", ".msi", ".dll", ".so"}

ALL_ALLOWED = ALLOWED_IMAGES | ALLOWED_VIDEOS | ALLOWED_AUDIO | ALLOWED_DOCS | ALLOWED_ARCHIVES


class AttachmentResponse(BaseModel):
    id: int
    filename: str
    content_type: str
    size: int
    url: str

    class Config:
        from_attributes = True


def sanitize_filename(filename: str) -> str:
    """Remove potentially dangerous characters from filename."""
    # Remove path separators and null bytes
    filename = os.path.basename(filename)
    filename = filename.replace("\x00", "")
    # Keep only safe characters
    filename = re.sub(r'[<>:"/\\|?*]', "_", filename)
    return filename[:200] if filename else "unnamed"


@router.post("/channels/{channel_id}/upload", response_model=AttachmentResponse)
async def upload_file(
    channel_id: int,
    user: CurrentUser,
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
):
    """Upload a file attachment to a channel."""
    # Verify channel exists and is text type
    channel_result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = channel_result.scalar_one_or_none()
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    if channel.type != ChannelType.TEXT:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a text channel")

    # Validate filename
    if not file.filename:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="No filename provided")

    safe_filename = sanitize_filename(file.filename)
    ext = os.path.splitext(safe_filename)[1].lower()

    # Security check: block dangerous extensions
    if ext in BLOCKED_EXTENSIONS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"File type {ext} is not allowed for security reasons"
        )

    # Determine content type
    content_type = file.content_type or mimetypes.guess_type(safe_filename)[0] or "application/octet-stream"

    # Read file content
    content = await file.read()
    size = len(content)

    if size == 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Empty file")
    if size > MAX_FILE_SIZE:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"File too large. Max size is {MAX_FILE_SIZE // 1024 // 1024}MB"
        )

    # Generate unique stored name
    stored_name = f"{uuid.uuid4().hex}{ext}"

    # Create channel directory if not exists
    channel_dir = UPLOAD_DIR / str(channel_id)
    channel_dir.mkdir(parents=True, exist_ok=True)

    # Save file
    file_path = channel_dir / stored_name
    try:
        file_path.write_bytes(content)
    except OSError as e:
        logger.error(f"Failed to save file: {e}")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Failed to save file")

    # Create database record (message_id is null until attached to a message)
    attachment = Attachment(
        message_id=None,
        channel_id=channel_id,
        user_id=user["id"],
        filename=safe_filename,
        stored_name=stored_name,
        content_type=content_type,
        size=size,
    )
    db.add(attachment)
    await db.flush()
    await db.refresh(attachment)

    logger.info(f"User {user['username']} uploaded file {safe_filename} ({size} bytes) to channel {channel_id}")

    return AttachmentResponse(
        id=attachment.id,
        filename=attachment.filename,
        content_type=attachment.content_type,
        size=attachment.size,
        url=f"/api/files/{attachment.id}",
    )


@router.get("/files/{attachment_id}")
async def download_file(
    attachment_id: int,
    user: CurrentUser,
    inline: bool = Query(False, description="Display inline instead of download"),
    db: AsyncSession = Depends(get_db),
):
    """Download or preview a file attachment."""
    result = await db.execute(select(Attachment).where(Attachment.id == attachment_id))
    attachment = result.scalar_one_or_none()

    if not attachment:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found")

    # Build file path
    file_path = UPLOAD_DIR / str(attachment.channel_id) / attachment.stored_name

    if not file_path.exists():
        logger.error(f"File not found on disk: {file_path}")
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found on disk")

    # Determine disposition
    if inline:
        # For inline preview, use appropriate content types
        media_type = attachment.content_type
        disposition = "inline"
    else:
        media_type = "application/octet-stream"
        disposition = "attachment"

    return FileResponse(
        path=file_path,
        filename=attachment.filename,
        media_type=media_type,
        content_disposition_type=disposition,
    )


@router.delete("/files/{attachment_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_file(
    attachment_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """Delete a file attachment (only owner or admin)."""
    result = await db.execute(select(Attachment).where(Attachment.id == attachment_id))
    attachment = result.scalar_one_or_none()

    if not attachment:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found")

    # Check permission: owner or admin (permission_level >= 3)
    is_owner = attachment.user_id == user["id"]
    is_admin = user.get("permission_level", 0) >= 3
    if not is_owner and not is_admin:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Permission denied")

    # Delete file from disk
    file_path = UPLOAD_DIR / str(attachment.channel_id) / attachment.stored_name
    try:
        if file_path.exists():
            file_path.unlink()
    except OSError as e:
        logger.error(f"Failed to delete file from disk: {e}")

    # Delete database record
    await db.delete(attachment)

    logger.info(f"User {user['username']} deleted file {attachment.filename}")
