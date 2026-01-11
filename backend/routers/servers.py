from __future__ import annotations

from typing import Literal
from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from ..core.database import get_db
from ..models.server import Server, Channel, ChannelType, ChannelGroup
from .deps import CurrentUser, AdminUser


router = APIRouter(prefix="/api/servers", tags=["servers"])


class ServerCreate(BaseModel):
    name: str
    icon: str | None = None


class ServerUpdate(BaseModel):
    name: str | None = None
    icon: str | None = None


class ServerResponse(BaseModel):
    id: int
    name: str
    icon: str | None
    owner_id: int

    class Config:
        from_attributes = True


class ChannelResponse(BaseModel):
    id: int
    name: str
    type: str
    position: int
    top_position: int
    group_id: int | None = None

    class Config:
        from_attributes = True


class ServerDetailResponse(ServerResponse):
    channels: list[ChannelResponse]


@router.get("", response_model=list[ServerResponse])
async def list_servers(user: CurrentUser, db: AsyncSession = Depends(get_db)):
    """List all servers."""
    result = await db.execute(select(Server).order_by(Server.id))
    servers = result.scalars().all()
    return servers


@router.post("", response_model=ServerResponse, status_code=status.HTTP_201_CREATED)
async def create_server(
    payload: ServerCreate, user: AdminUser, db: AsyncSession = Depends(get_db)
):
    """Create a new server (admin only)."""
    server = Server(name=payload.name, icon=payload.icon, owner_id=user["id"])
    db.add(server)
    await db.flush()

    # Create default channels (ungrouped, so use top_position)
    general_text = Channel(
        server_id=server.id,
        name="general",
        type=ChannelType.TEXT,
        position=0,
        top_position=0,
    )
    general_voice = Channel(
        server_id=server.id,
        name="General",
        type=ChannelType.VOICE,
        position=0,
        top_position=1,
    )
    db.add_all([general_text, general_voice])
    await db.flush()

    return server


@router.get("/{server_id}", response_model=ServerDetailResponse)
async def get_server(
    server_id: int, user: CurrentUser, db: AsyncSession = Depends(get_db)
):
    """Get server details with channels."""
    result = await db.execute(
        select(Server)
        .where(Server.id == server_id)
        .options(selectinload(Server.channels))
    )
    server = result.scalar_one_or_none()
    if not server:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Server not found"
        )

    return ServerDetailResponse(
        id=server.id,
        name=server.name,
        icon=server.icon,
        owner_id=server.owner_id,
        channels=[
            ChannelResponse(
                id=c.id,
                name=c.name,
                type=c.type.value,
                position=c.position,
                top_position=c.top_position,
                group_id=c.group_id,
            )
            for c in sorted(server.channels, key=lambda x: x.position)
        ],
    )


@router.patch("/{server_id}", response_model=ServerResponse)
@router.put(
    "/{server_id}", response_model=ServerResponse
)  # Keep for backward compatibility
async def update_server(
    server_id: int,
    payload: ServerUpdate,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Update a server (admin only)."""
    result = await db.execute(select(Server).where(Server.id == server_id))
    server = result.scalar_one_or_none()
    if not server:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Server not found"
        )

    if payload.name is not None:
        server.name = payload.name
    if payload.icon is not None:
        server.icon = payload.icon

    await db.flush()
    return server


@router.delete("/{server_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_server(
    server_id: int, user: AdminUser, db: AsyncSession = Depends(get_db)
):
    """Delete a server (admin only)."""
    result = await db.execute(select(Server).where(Server.id == server_id))
    server = result.scalar_one_or_none()
    if not server:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Server not found"
        )

    await db.delete(server)


# ============================================
# Unified Top-Level Reorder API
# ============================================


class ReorderItem(BaseModel):
    type: Literal["group", "channel"]
    id: int


class ReorderTopLevelRequest(BaseModel):
    """Request to reorder top-level items (groups and ungrouped channels)."""

    items: list[ReorderItem]


@router.post("/{server_id}/reorder")
async def reorder_top_level(
    server_id: int,
    payload: ReorderTopLevelRequest,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Reorder top-level items: channel groups and ungrouped channels.

    This is the unified API for reordering the sidebar. Groups and ungrouped
    channels share a single position sequence (top_position for channels,
    position for groups).

    Example payload:
    {
        "items": [
            {"type": "group", "id": 1},
            {"type": "channel", "id": 5},
            {"type": "group", "id": 2}
        ]
    }
    """
    # Verify server exists
    server_result = await db.execute(select(Server).where(Server.id == server_id))
    if not server_result.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Server not found"
        )

    # Fetch all groups
    groups_result = await db.execute(
        select(ChannelGroup).where(ChannelGroup.server_id == server_id)
    )
    groups = {g.id: g for g in groups_result.scalars().all()}

    # Fetch all ungrouped channels
    channels_result = await db.execute(
        select(Channel).where(Channel.server_id == server_id, Channel.group_id == None)
    )
    ungrouped_channels = {c.id: c for c in channels_result.scalars().all()}

    # Validate all items
    seen_groups = set()
    seen_channels = set()

    for item in payload.items:
        if item.type == "group":
            if item.id not in groups:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Group {item.id} not found in server",
                )
            if item.id in seen_groups:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Duplicate group {item.id} in items",
                )
            seen_groups.add(item.id)
        elif item.type == "channel":
            if item.id not in ungrouped_channels:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Ungrouped channel {item.id} not found in server",
                )
            if item.id in seen_channels:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Duplicate channel {item.id} in items",
                )
            seen_channels.add(item.id)

    # Validate all items are provided
    if seen_groups != set(groups.keys()):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="All groups must be included in items",
        )
    if seen_channels != set(ungrouped_channels.keys()):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="All ungrouped channels must be included in items",
        )

    # Assign positions
    for idx, item in enumerate(payload.items):
        if item.type == "group":
            groups[item.id].position = idx
        else:
            ungrouped_channels[item.id].top_position = idx

    await db.flush()

    return {"success": True}
