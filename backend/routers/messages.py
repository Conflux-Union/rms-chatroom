from __future__ import annotations

import logging
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from ..core.database import get_db
from ..models.server import Attachment, Channel, ChannelType, Message
from .deps import CurrentUser

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/channels/{channel_id}/messages", tags=["messages"])


class MessageCreate(BaseModel):
    content: str = ""
    attachment_ids: list[int] = []


class AttachmentResponse(BaseModel):
    id: int
    filename: str
    content_type: str
    size: int
    url: str

    class Config:
        from_attributes = True


class MessageResponse(BaseModel):
    id: int
    channel_id: int
    user_id: int
    username: str
    content: str
    created_at: datetime
    attachments: list[AttachmentResponse] = []
    # New fields for message management
    is_deleted: bool = False
    deleted_by: int | None = None
    edited_at: datetime | None = None

    class Config:
        from_attributes = True


def _attachment_to_response(att: Attachment) -> AttachmentResponse:
    """Convert Attachment model to response with URL."""
    return AttachmentResponse(
        id=att.id,
        filename=att.filename,
        content_type=att.content_type,
        size=att.size,
        url=f"/api/files/{att.id}",
    )


def _message_to_response(msg: Message) -> MessageResponse:
    """Convert Message model to response with attachments."""
    return MessageResponse(
        id=msg.id,
        channel_id=msg.channel_id,
        user_id=msg.user_id,
        username=msg.username,
        content=msg.content,
        created_at=msg.created_at,
        attachments=[_attachment_to_response(att) for att in msg.attachments],
        is_deleted=msg.is_deleted,
        deleted_by=msg.deleted_by,
        edited_at=msg.edited_at,
    )


@router.get("", response_model=list[MessageResponse])
async def get_messages(
    channel_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
    limit: int = Query(50, le=100),
    before: int | None = Query(None),
):
    """Get messages from a text channel."""
    # Verify channel exists and is text type
    channel_result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = channel_result.scalar_one_or_none()
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    if channel.type != ChannelType.TEXT:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a text channel")

    query = (
        select(Message)
        .where(
            Message.channel_id == channel_id,
            Message.is_deleted == False  # Filter out deleted messages
        )
        .options(selectinload(Message.attachments))
    )
    if before:
        query = query.where(Message.id < before)
    query = query.order_by(Message.id.desc()).limit(limit)

    result = await db.execute(query)
    messages = result.scalars().all()

    # Return in chronological order with attachments
    return [_message_to_response(msg) for msg in reversed(messages)]


@router.post("/debug", status_code=status.HTTP_200_OK)
async def debug_message(channel_id: int, request: Request):
    """Debug endpoint to see raw request body."""
    body = await request.body()
    headers = dict(request.headers)
    logger.info(f"DEBUG: channel_id={channel_id}, body={body!r}, headers={headers}")
    return {"body": body.decode('utf-8', errors='replace'), "headers": headers}


@router.post("", response_model=MessageResponse, status_code=status.HTTP_201_CREATED)
async def create_message(
    channel_id: int, payload: MessageCreate, user: CurrentUser, request: Request, db: AsyncSession = Depends(get_db)
):
    """Send a message to a text channel."""
    logger.info(f"create_message: channel_id={channel_id}, content={payload.content!r}, attachments={payload.attachment_ids}, user={user.get('username')}")

    # Must have content or attachments
    if not payload.content.strip() and not payload.attachment_ids:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Message must have content or attachments")

    # Verify channel exists and is text type
    channel_result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = channel_result.scalar_one_or_none()
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    if channel.type != ChannelType.TEXT:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a text channel")

    # Create message
    message = Message(
        channel_id=channel_id,
        user_id=user["id"],
        username=user.get("nickname") or user["username"],
        content=payload.content,
    )
    db.add(message)
    await db.flush()

    # Link attachments to message
    if payload.attachment_ids:
        att_result = await db.execute(
            select(Attachment).where(
                Attachment.id.in_(payload.attachment_ids),
                Attachment.channel_id == channel_id,
                Attachment.user_id == user["id"],
                Attachment.message_id.is_(None),  # Only unlinked attachments
            )
        )
        attachments = att_result.scalars().all()
        for att in attachments:
            att.message_id = message.id
        message.attachments = list(attachments)

    await db.flush()
    await db.refresh(message)

    return _message_to_response(message)


class MessageEdit(BaseModel):
    content: str


@router.patch("/{message_id}", response_model=MessageResponse)
async def edit_message(
    channel_id: int,
    message_id: int,
    payload: MessageEdit,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """Edit a message. Only the author can edit their own messages."""
    if not payload.content.strip():
        raise HTTPException(status_code=400, detail="Content cannot be empty")

    result = await db.execute(
        select(Message).where(Message.id == message_id, Message.channel_id == channel_id)
    )
    message = result.scalar_one_or_none()
    if not message:
        raise HTTPException(status_code=404, detail="Message not found")

    if message.is_deleted:
        raise HTTPException(status_code=400, detail="Cannot edit deleted message")

    # Only the author can edit
    if message.user_id != user["id"]:
        raise HTTPException(status_code=403, detail="Can only edit own messages")

    # Update content
    message.content = payload.content.strip()
    message.edited_at = datetime.utcnow()

    # Extract data before commit (to avoid lazy loading issues)
    content = message.content
    edited_at = message.edited_at

    await db.commit()
    await db.refresh(message)

    # Broadcast edit event via WebSocket
    from ..websocket.manager import chat_manager
    await chat_manager.broadcast_to_channel(channel_id, {
        "type": "message_edited",
        "message_id": message_id,
        "content": content,
        "edited_at": edited_at.isoformat(),
    })

    return _message_to_response(message)


@router.delete("/{message_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_message(
    channel_id: int,
    message_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """Delete (recall) a message. Users can delete own messages within 2 minutes, admins can delete any."""
    # Query message
    result = await db.execute(
        select(Message).where(Message.id == message_id, Message.channel_id == channel_id)
    )
    message = result.scalar_one_or_none()
    if not message:
        raise HTTPException(status_code=404, detail="Message not found")

    if message.is_deleted:
        raise HTTPException(status_code=400, detail="Message already deleted")

    is_admin = user.get("permission_level", 0) >= 3
    is_owner = message.user_id == user["id"]

    # Permission check
    if not is_admin and not is_owner:
        raise HTTPException(status_code=403, detail="Cannot delete others' messages")

    # Non-admins need to check 2-minute limit
    if not is_admin:
        elapsed = (datetime.utcnow() - message.created_at).total_seconds()
        if elapsed > 120:
            raise HTTPException(status_code=403, detail="Can only delete messages within 2 minutes")

    # Soft delete
    message.is_deleted = True
    message.deleted_at = datetime.utcnow()
    message.deleted_by = user["id"]
    await db.commit()

    # Broadcast delete event via WebSocket
    from ..websocket.manager import chat_manager
    await chat_manager.broadcast_to_channel(channel_id, {
        "type": "message_deleted",
        "message_id": message_id,
        "deleted_by": user["id"],
        "deleted_by_username": user.get("nickname") or user["username"],
    })

    return None
