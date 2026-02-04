from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..models.server import Channel, ChannelType, Server, ChannelGroup
from .deps import CurrentUser, AdminUser
from ..core.permissions import check_channel_visibility, check_channel_speak_permission


router = APIRouter(prefix="/api/servers/{server_id}/channels", tags=["channels"])


class ChannelCreate(BaseModel):
    name: str
    type: str = "text"
    group_id: int | None = None  # Optional: assign to a channel group
    visibility_min_server_level: int = 1  # 1-4, default accessible to all
    visibility_min_internal_level: int = 1  # 1-2, default accessible to all
    speak_min_server_level: int = 1  # 1-4, default everyone can speak
    speak_min_internal_level: int = 1  # 1-2, default everyone can speak


class ChannelUpdate(BaseModel):
    name: str | None = None
    group_id: int | None = (
        None  # Optional: move to a different group (use -1 to ungroup)
    )
    visibility_min_server_level: int | None = None  # 1-4
    visibility_min_internal_level: int | None = None  # 1-2
    speak_min_server_level: int | None = None  # 1-4
    speak_min_internal_level: int | None = None  # 1-2


class ChannelResponse(BaseModel):
    id: int
    server_id: int
    group_id: int | None
    name: str
    type: str
    position: int
    top_position: int
    visibility_min_server_level: int = 1
    visibility_min_internal_level: int = 1
    speak_min_server_level: int = 1
    speak_min_internal_level: int = 1

    class Config:
        from_attributes = True


@router.get("", response_model=list[ChannelResponse])
async def list_channels(
    server_id: int, user: CurrentUser, db: AsyncSession = Depends(get_db)
):
    """List all channels in a server user has access to."""
    result = await db.execute(
        select(Channel).where(Channel.server_id == server_id).order_by(Channel.position)
    )
    channels = result.scalars().all()
    
    # Filter channels based on user's visibility permissions
    filtered_channels = [
        c for c in channels
        if check_channel_visibility(
            user,
            c.visibility_min_server_level,
            c.visibility_min_internal_level
        )
    ]
    
    return [
        ChannelResponse(
            id=c.id,
            server_id=c.server_id,
            group_id=c.group_id,
            name=c.name,
            type=c.type.value,
            position=c.position,
            top_position=c.top_position,
            visibility_min_server_level=c.visibility_min_server_level,
            visibility_min_internal_level=c.visibility_min_internal_level,
            speak_min_server_level=c.speak_min_server_level,
            speak_min_internal_level=c.speak_min_internal_level,
        )
        for c in filtered_channels
    ]


@router.post("", response_model=ChannelResponse, status_code=status.HTTP_201_CREATED)
async def create_channel(
    server_id: int,
    payload: ChannelCreate,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Create a new channel (admin only)."""
    # Verify server exists
    server_result = await db.execute(select(Server).where(Server.id == server_id))
    if not server_result.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Server not found"
        )

    # Verify group exists if provided
    if payload.group_id is not None:
        group_result = await db.execute(
            select(ChannelGroup).where(
                ChannelGroup.id == payload.group_id, ChannelGroup.server_id == server_id
            )
        )
        if not group_result.scalar_one_or_none():
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Channel group not found"
            )

    channel_type = ChannelType.VOICE if payload.type == "voice" else ChannelType.TEXT
    
    # Validate permission levels
    if not (1 <= payload.visibility_min_server_level <= 4):
        raise HTTPException(status_code=400, detail="visibility_min_server_level must be 1-4")
    if not (1 <= payload.visibility_min_internal_level <= 2):
        raise HTTPException(status_code=400, detail="visibility_min_internal_level must be 1-2")
    if not (1 <= payload.speak_min_server_level <= 4):
        raise HTTPException(status_code=400, detail="speak_min_server_level must be 1-4")
    if not (1 <= payload.speak_min_internal_level <= 2):
        raise HTTPException(status_code=400, detail="speak_min_internal_level must be 1-2")

    if payload.group_id is not None:
        # Grouped channel: get max position within the group
        pos_result = await db.execute(
            select(func.max(Channel.position)).where(
                Channel.server_id == server_id, Channel.group_id == payload.group_id
            )
        )
        max_pos = pos_result.scalar() or -1
        channel = Channel(
            server_id=server_id,
            group_id=payload.group_id,
            name=payload.name,
            type=channel_type,
            position=max_pos + 1,
            top_position=0,  # Not used for grouped channels
            visibility_min_server_level=payload.visibility_min_server_level,
            visibility_min_internal_level=payload.visibility_min_internal_level,
            speak_min_server_level=payload.speak_min_server_level,
            speak_min_internal_level=payload.speak_min_internal_level,
        )
    else:
        # Ungrouped channel: get max top-level position
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
        channel = Channel(
            server_id=server_id,
            group_id=None,
            name=payload.name,
            type=channel_type,
            position=0,  # Not used for ungrouped channels
            top_position=max_pos + 1,
            visibility_min_server_level=payload.visibility_min_server_level,
            visibility_min_internal_level=payload.visibility_min_internal_level,
            speak_min_server_level=payload.speak_min_server_level,
            speak_min_internal_level=payload.speak_min_internal_level,
        )

    db.add(channel)
    await db.flush()

    return ChannelResponse(
        id=channel.id,
        server_id=channel.server_id,
        group_id=channel.group_id,
        name=channel.name,
        type=channel.type.value,
        position=channel.position,
        top_position=channel.top_position,
        visibility_min_server_level=channel.visibility_min_server_level,
        visibility_min_internal_level=channel.visibility_min_internal_level,
        speak_min_server_level=channel.speak_min_server_level,
        speak_min_internal_level=channel.speak_min_internal_level,
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
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found"
        )

    if payload.name is not None:
        channel.name = payload.name
    
    # Update permission levels
    if payload.visibility_min_server_level is not None:
        if not (1 <= payload.visibility_min_server_level <= 4):
            raise HTTPException(status_code=400, detail="visibility_min_server_level must be 1-4")
        channel.visibility_min_server_level = payload.visibility_min_server_level
    if payload.visibility_min_internal_level is not None:
        if not (1 <= payload.visibility_min_internal_level <= 2):
            raise HTTPException(status_code=400, detail="visibility_min_internal_level must be 1-2")
        channel.visibility_min_internal_level = payload.visibility_min_internal_level
    if payload.speak_min_server_level is not None:
        if not (1 <= payload.speak_min_server_level <= 4):
            raise HTTPException(status_code=400, detail="speak_min_server_level must be 1-4")
        channel.speak_min_server_level = payload.speak_min_server_level
    if payload.speak_min_internal_level is not None:
        if not (1 <= payload.speak_min_internal_level <= 2):
            raise HTTPException(status_code=400, detail="speak_min_internal_level must be 1-2")
        channel.speak_min_internal_level = payload.speak_min_internal_level

    # Handle group_id update: -1 means ungroup, None means no change, positive int means move to group
    if payload.group_id is not None:
        if payload.group_id == -1:
            # Ungroup the channel: assign top_position
            if channel.group_id is not None:
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
                channel.group_id = None
                channel.top_position = max_pos + 1
                channel.position = 0
        else:
            # Move to a group: verify group exists
            group_result = await db.execute(
                select(ChannelGroup).where(
                    ChannelGroup.id == payload.group_id,
                    ChannelGroup.server_id == server_id,
                )
            )
            if not group_result.scalar_one_or_none():
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail="Channel group not found",
                )

            # Get max position in target group
            pos_result = await db.execute(
                select(func.max(Channel.position)).where(
                    Channel.server_id == server_id, Channel.group_id == payload.group_id
                )
            )
            max_pos = pos_result.scalar() or -1
            channel.group_id = payload.group_id
            channel.position = max_pos + 1
            channel.top_position = 0  # Not used for grouped channels

    await db.flush()

    return ChannelResponse(
        id=channel.id,
        server_id=channel.server_id,
        group_id=channel.group_id,
        name=channel.name,
        type=channel.type.value,
        position=channel.position,
        top_position=channel.top_position,
        visibility_min_server_level=channel.visibility_min_server_level,
        visibility_min_internal_level=channel.visibility_min_internal_level,
        speak_min_server_level=channel.speak_min_server_level,
        speak_min_internal_level=channel.speak_min_internal_level,
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
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found"
        )

    await db.delete(channel)
