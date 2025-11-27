from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..models.server import Channel, ChannelType, Server
from .deps import CurrentUser, AdminUser


router = APIRouter(prefix="/api/servers/{server_id}/channels", tags=["channels"])


class ChannelCreate(BaseModel):
    name: str
    type: str = "text"


class ChannelResponse(BaseModel):
    id: int
    server_id: int
    name: str
    type: str
    position: int

    class Config:
        from_attributes = True


@router.get("", response_model=list[ChannelResponse])
async def list_channels(server_id: int, user: CurrentUser, db: AsyncSession = Depends(get_db)):
    """List all channels in a server."""
    result = await db.execute(
        select(Channel).where(Channel.server_id == server_id).order_by(Channel.position)
    )
    channels = result.scalars().all()
    return [
        ChannelResponse(
            id=c.id, server_id=c.server_id, name=c.name, type=c.type.value, position=c.position
        )
        for c in channels
    ]


@router.post("", response_model=ChannelResponse, status_code=status.HTTP_201_CREATED)
async def create_channel(
    server_id: int, payload: ChannelCreate, user: AdminUser, db: AsyncSession = Depends(get_db)
):
    """Create a new channel (admin only)."""
    # Verify server exists
    server_result = await db.execute(select(Server).where(Server.id == server_id))
    if not server_result.scalar_one_or_none():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Server not found")

    # Get max position
    pos_result = await db.execute(
        select(Channel.position).where(Channel.server_id == server_id).order_by(Channel.position.desc())
    )
    max_pos = pos_result.scalar() or 0

    channel_type = ChannelType.VOICE if payload.type == "voice" else ChannelType.TEXT
    channel = Channel(
        server_id=server_id,
        name=payload.name,
        type=channel_type,
        position=max_pos + 1,
    )
    db.add(channel)
    await db.flush()

    return ChannelResponse(
        id=channel.id,
        server_id=channel.server_id,
        name=channel.name,
        type=channel.type.value,
        position=channel.position,
    )


@router.delete("/{channel_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_channel(
    server_id: int, channel_id: int, user: AdminUser, db: AsyncSession = Depends(get_db)
):
    """Delete a channel (admin only)."""
    result = await db.execute(
        select(Channel).where(Channel.id == channel_id, Channel.server_id == server_id)
    )
    channel = result.scalar_one_or_none()
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")

    await db.delete(channel)
