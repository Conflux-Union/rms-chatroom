from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..models.server import Channel, ChannelType, Message
from .deps import CurrentUser


router = APIRouter(prefix="/api/channels/{channel_id}/messages", tags=["messages"])


class MessageCreate(BaseModel):
    content: str


class MessageResponse(BaseModel):
    id: int
    channel_id: int
    user_id: int
    username: str
    content: str
    created_at: datetime

    class Config:
        from_attributes = True


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

    query = select(Message).where(Message.channel_id == channel_id)
    if before:
        query = query.where(Message.id < before)
    query = query.order_by(Message.id.desc()).limit(limit)

    result = await db.execute(query)
    messages = result.scalars().all()

    # Return in chronological order
    return list(reversed(messages))


@router.post("", response_model=MessageResponse, status_code=status.HTTP_201_CREATED)
async def create_message(
    channel_id: int, payload: MessageCreate, user: CurrentUser, db: AsyncSession = Depends(get_db)
):
    """Send a message to a text channel."""
    # Verify channel exists and is text type
    channel_result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = channel_result.scalar_one_or_none()
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    if channel.type != ChannelType.TEXT:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a text channel")

    message = Message(
        channel_id=channel_id,
        user_id=user["id"],
        username=user.get("nickname") or user["username"],
        content=payload.content,
    )
    db.add(message)
    await db.flush()

    return message
