"""Moderation service for checking user mute status."""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models.server import Channel, MuteRecord, MuteScope


async def check_user_muted(
    user_id: int,
    channel_id: int,
    db: AsyncSession,
) -> tuple[bool, str | None]:
    """
    Check if user is muted in the given channel.

    Returns (is_muted, reason).
    Checks in order: global -> server -> channel.
    Automatically filters expired temporary mutes.
    """
    # Query all mute records for this user
    result = await db.execute(
        select(MuteRecord).where(MuteRecord.user_id == user_id)
    )
    mutes = result.scalars().all()

    now = datetime.utcnow()

    # Check global mute (highest priority)
    for mute in mutes:
        if mute.scope == MuteScope.GLOBAL:
            if mute.muted_until is None or mute.muted_until > now:
                return True, mute.reason or "You are globally muted"

    # Get channel's server_id
    channel_result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = channel_result.scalar_one_or_none()
    if not channel:
        return False, None

    # Check server-level mute
    for mute in mutes:
        if mute.scope == MuteScope.SERVER and mute.server_id == channel.server_id:
            if mute.muted_until is None or mute.muted_until > now:
                return True, mute.reason or "You are muted in this server"

    # Check channel-level mute
    for mute in mutes:
        if mute.scope == MuteScope.CHANNEL and mute.channel_id == channel_id:
            if mute.muted_until is None or mute.muted_until > now:
                return True, mute.reason or "You are muted in this channel"

    return False, None
