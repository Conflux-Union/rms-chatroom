from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..models.server import ChannelGroup, Server, Channel
from .deps import CurrentUser, AdminUser


router = APIRouter(
    prefix="/api/servers/{server_id}/channel-groups", tags=["channel-groups"]
)


class ChannelGroupCreate(BaseModel):
    name: str


class ChannelGroupUpdate(BaseModel):
    name: str | None = None


class ChannelGroupResponse(BaseModel):
    id: int
    server_id: int
    name: str
    position: int

    class Config:
        from_attributes = True


@router.get("", response_model=list[ChannelGroupResponse])
async def list_channel_groups(
    server_id: int, user: CurrentUser, db: AsyncSession = Depends(get_db)
):
    """List all channel groups in a server."""
    result = await db.execute(
        select(ChannelGroup)
        .where(ChannelGroup.server_id == server_id)
        .order_by(ChannelGroup.position)
    )
    groups = result.scalars().all()
    return [
        ChannelGroupResponse(
            id=g.id, server_id=g.server_id, name=g.name, position=g.position
        )
        for g in groups
    ]


@router.post(
    "", response_model=ChannelGroupResponse, status_code=status.HTTP_201_CREATED
)
async def create_channel_group(
    server_id: int,
    payload: ChannelGroupCreate,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Create a new channel group (admin only).

    New groups are placed at the end of the top-level list.
    """
    # Verify server exists
    server_result = await db.execute(select(Server).where(Server.id == server_id))
    if not server_result.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Server not found"
        )

    # Get max top-level position from both groups and ungrouped channels
    group_max = await db.execute(
        select(func.max(ChannelGroup.position)).where(
            ChannelGroup.server_id == server_id
        )
    )
    channel_max = await db.execute(
        select(func.max(Channel.top_position)).where(
            Channel.server_id == server_id, Channel.group_id == None
        )
    )
    max_pos = max(group_max.scalar() or -1, channel_max.scalar() or -1)

    group = ChannelGroup(
        server_id=server_id,
        name=payload.name,
        position=max_pos + 1,
    )
    db.add(group)
    await db.flush()

    return ChannelGroupResponse(
        id=group.id,
        server_id=group.server_id,
        name=group.name,
        position=group.position,
    )


@router.patch("/{group_id}", response_model=ChannelGroupResponse)
async def update_channel_group(
    server_id: int,
    group_id: int,
    payload: ChannelGroupUpdate,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Update channel group properties (admin only)."""
    result = await db.execute(
        select(ChannelGroup).where(
            ChannelGroup.id == group_id, ChannelGroup.server_id == server_id
        )
    )
    group = result.scalar_one_or_none()
    if not group:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Channel group not found"
        )

    if payload.name is not None:
        group.name = payload.name

    await db.flush()

    return ChannelGroupResponse(
        id=group.id, server_id=group.server_id, name=group.name, position=group.position
    )


class ReorderGroupChannelsRequest(BaseModel):
    """Request to reorder channels within a group."""

    channel_ids: list[int]


@router.post("/{group_id}/reorder-channels")
async def reorder_group_channels(
    server_id: int,
    group_id: int,
    payload: ReorderGroupChannelsRequest,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Reorder channels within a specific group.

    Only accepts channel IDs that belong to this group.
    """
    # Verify group exists
    group_result = await db.execute(
        select(ChannelGroup).where(
            ChannelGroup.id == group_id, ChannelGroup.server_id == server_id
        )
    )
    if not group_result.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Channel group not found"
        )

    # Fetch all channels in this group
    result = await db.execute(
        select(Channel)
        .where(Channel.server_id == server_id, Channel.group_id == group_id)
        .order_by(Channel.position)
    )
    channels = result.scalars().all()
    id_map = {c.id: c for c in channels}

    provided_ids = payload.channel_ids
    provided_set = set(provided_ids)
    existing_set = set(id_map.keys())

    # Validate: all provided IDs must be in this group
    if not provided_set.issubset(existing_set):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="channel_ids must refer to channels in this group",
        )

    # Validate: must provide all channels in the group
    if provided_set != existing_set:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="All channel IDs in the group must be provided",
        )

    # Assign new positions
    for idx, cid in enumerate(provided_ids):
        id_map[cid].position = idx

    await db.flush()

    return {"success": True}


@router.delete("/{group_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_channel_group(
    server_id: int, group_id: int, user: AdminUser, db: AsyncSession = Depends(get_db)
):
    """Delete a channel group (admin only). Channels in the group will become ungrouped."""
    result = await db.execute(
        select(ChannelGroup).where(
            ChannelGroup.id == group_id, ChannelGroup.server_id == server_id
        )
    )
    group = result.scalar_one_or_none()
    if not group:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Channel group not found"
        )

    # Get max top-level position for placing ungrouped channels
    group_max = await db.execute(
        select(func.max(ChannelGroup.position)).where(
            ChannelGroup.server_id == server_id
        )
    )
    channel_max = await db.execute(
        select(func.max(Channel.top_position)).where(
            Channel.server_id == server_id, Channel.group_id == None
        )
    )
    max_pos = max(group_max.scalar() or -1, channel_max.scalar() or -1)

    # Set all channels in this group to ungrouped and assign top_position
    channels_result = await db.execute(
        select(Channel).where(Channel.group_id == group_id).order_by(Channel.position)
    )
    channels = channels_result.scalars().all()
    for i, channel in enumerate(channels):
        channel.group_id = None
        channel.top_position = max_pos + 1 + i

    await db.delete(group)
