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


class ChannelUpdate(BaseModel):
    name: str | None = None


class ReorderRequest(BaseModel):
    channel_ids: list[int]


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


@router.patch("/{channel_id}", response_model=ChannelResponse)
async def update_channel(
    server_id: int,
    channel_id: int,
    payload: ChannelUpdate,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Update channel properties (admin only)."""
    result = await db.execute(
        select(Channel).where(Channel.id == channel_id, Channel.server_id == server_id)
    )
    channel = result.scalar_one_or_none()
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")

    if payload.name is not None:
        channel.name = payload.name

    await db.flush()

    return ChannelResponse(id=channel.id, server_id=channel.server_id, name=channel.name, type=channel.type.value, position=channel.position)


@router.post("/reorder", response_model=list[ChannelResponse])
async def reorder_channels(
    server_id: int,
    payload: ReorderRequest,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Reorder channels for a server. Accepts a full ordered list of channel IDs for the server.

    If the provided list contains exactly all channel ids it will replace the global order.
    If it contains a subset (commonly channels of a single type), we'll reorder those channels
    among themselves and insert them at the position of the earliest of the moved channels,
    preserving relative order of the other channels.
    """
    # Fetch all channels for server ordered by position
    result = await db.execute(select(Channel).where(Channel.server_id == server_id).order_by(Channel.position))
    channels = result.scalars().all()
    id_map = {c.id: c for c in channels}

    provided_ids = payload.channel_ids
    provided_set = set(provided_ids)
    existing_set = set(id_map.keys())

    if not provided_set.issubset(existing_set):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="channel_ids must refer to channels in the server")

    # If full set provided, simply reorder according to payload
    if provided_set == existing_set:
        for idx, cid in enumerate(provided_ids):
            id_map[cid].position = idx + 1

    else:
        # Partial reorder: remove provided channels from original ordered list
        orig_list = list(channels)
        # find original indices for provided ids
        orig_indices = [i for i, c in enumerate(orig_list) if c.id in provided_set]
        if not orig_indices:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="No valid channel ids provided")
        insert_at = min(orig_indices)

        remaining = [c for c in orig_list if c.id not in provided_set]
        # Build new ordered list by inserting provided channels (in given order) at insert_at
        new_order: list[Channel] = []
        new_order.extend(remaining[:insert_at])
        for cid in provided_ids:
            new_order.append(id_map[cid])
        new_order.extend(remaining[insert_at:])

        # Assign new positions sequentially
        for idx, ch in enumerate(new_order):
            ch.position = idx + 1

    await db.flush()

    # Return updated list ordered by position
    result = await db.execute(select(Channel).where(Channel.server_id == server_id).order_by(Channel.position))
    sorted_channels = result.scalars().all()

    return [
        ChannelResponse(id=c.id, server_id=c.server_id, name=c.name, type=c.type.value, position=c.position)
        for c in sorted_channels
    ]


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
