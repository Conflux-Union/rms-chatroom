from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..models.server import ChannelGroup, Server, Channel
from .deps import CurrentUser, AdminUser


router = APIRouter(prefix="/api/servers/{server_id}/channel-groups", tags=["channel-groups"])


class ChannelGroupCreate(BaseModel):
    name: str


class ChannelGroupUpdate(BaseModel):
    name: str | None = None


class ReorderGroupsRequest(BaseModel):
    group_ids: list[int]


class ChannelGroupResponse(BaseModel):
    id: int
    server_id: int
    name: str
    position: int

    class Config:
        from_attributes = True


@router.get("", response_model=list[ChannelGroupResponse])
async def list_channel_groups(server_id: int, user: CurrentUser, db: AsyncSession = Depends(get_db)):
    """List all channel groups in a server."""
    result = await db.execute(
        select(ChannelGroup).where(ChannelGroup.server_id == server_id).order_by(ChannelGroup.position)
    )
    groups = result.scalars().all()
    return [
        ChannelGroupResponse(
            id=g.id, server_id=g.server_id, name=g.name, position=g.position
        )
        for g in groups
    ]


@router.post("", response_model=ChannelGroupResponse, status_code=status.HTTP_201_CREATED)
async def create_channel_group(
    server_id: int, payload: ChannelGroupCreate, user: AdminUser, db: AsyncSession = Depends(get_db)
):
    """Create a new channel group (admin only)."""
    # Verify server exists
    server_result = await db.execute(select(Server).where(Server.id == server_id))
    if not server_result.scalar_one_or_none():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Server not found")

    # Get max position
    pos_result = await db.execute(
        select(ChannelGroup.position).where(ChannelGroup.server_id == server_id).order_by(ChannelGroup.position.desc())
    )
    max_pos = pos_result.scalar() or 0

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
        select(ChannelGroup).where(ChannelGroup.id == group_id, ChannelGroup.server_id == server_id)
    )
    group = result.scalar_one_or_none()
    if not group:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel group not found")

    if payload.name is not None:
        group.name = payload.name

    await db.flush()

    return ChannelGroupResponse(
        id=group.id, server_id=group.server_id, name=group.name, position=group.position
    )


@router.post("/reorder", response_model=list[ChannelGroupResponse])
async def reorder_channel_groups(
    server_id: int,
    payload: ReorderGroupsRequest,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Reorder channel groups for a server."""
    # Fetch all groups for server ordered by position
    result = await db.execute(
        select(ChannelGroup).where(ChannelGroup.server_id == server_id).order_by(ChannelGroup.position)
    )
    groups = result.scalars().all()
    id_map = {g.id: g for g in groups}

    provided_ids = payload.group_ids
    provided_set = set(provided_ids)
    existing_set = set(id_map.keys())

    if not provided_set.issubset(existing_set):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="group_ids must refer to groups in the server"
        )

    # Reorder according to payload
    if provided_set == existing_set:
        for idx, gid in enumerate(provided_ids):
            id_map[gid].position = idx + 1

    await db.flush()

    # Return updated list ordered by position
    result = await db.execute(
        select(ChannelGroup).where(ChannelGroup.server_id == server_id).order_by(ChannelGroup.position)
    )
    sorted_groups = result.scalars().all()

    return [
        ChannelGroupResponse(id=g.id, server_id=g.server_id, name=g.name, position=g.position)
        for g in sorted_groups
    ]


@router.delete("/{group_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_channel_group(
    server_id: int, group_id: int, user: AdminUser, db: AsyncSession = Depends(get_db)
):
    """Delete a channel group (admin only). Channels in the group will become ungrouped."""
    result = await db.execute(
        select(ChannelGroup).where(ChannelGroup.id == group_id, ChannelGroup.server_id == server_id)
    )
    group = result.scalar_one_or_none()
    if not group:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel group not found")

    # Set all channels in this group to ungrouped (group_id = NULL)
    channels_result = await db.execute(
        select(Channel).where(Channel.group_id == group_id)
    )
    channels = channels_result.scalars().all()
    for channel in channels:
        channel.group_id = None

    await db.delete(group)
