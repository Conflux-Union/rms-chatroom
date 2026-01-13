"""Moderation API endpoints for mute management."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..models.server import MuteRecord, MuteScope
from .deps import AdminUser, CurrentUser

router = APIRouter(prefix="/api/mute", tags=["moderation"])


class MuteCreate(BaseModel):
    user_id: int
    scope: str  # "global" | "server" | "channel"
    server_id: int | None = None
    channel_id: int | None = None
    duration_minutes: int | None = None  # NULL = permanent mute
    reason: str | None = None


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_mute(
    payload: MuteCreate,
    admin: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Mute a user. Admin only."""
    # Validate scope and corresponding IDs
    if payload.scope == "global":
        if payload.server_id or payload.channel_id:
            raise HTTPException(
                400, "Global mute should not have server_id or channel_id"
            )
    elif payload.scope == "server":
        if not payload.server_id or payload.channel_id:
            raise HTTPException(400, "Server mute requires server_id only")
    elif payload.scope == "channel":
        if not payload.channel_id or payload.server_id:
            raise HTTPException(400, "Channel mute requires channel_id only")
    else:
        raise HTTPException(400, "Invalid scope")

    # Calculate muted_until
    muted_until = None
    if payload.duration_minutes:
        muted_until = datetime.now(timezone.utc) + timedelta(
            minutes=payload.duration_minutes
        )

    # Check if mute record already exists
    existing = await db.execute(
        select(MuteRecord).where(
            MuteRecord.user_id == payload.user_id,
            MuteRecord.scope == payload.scope,
            MuteRecord.server_id == payload.server_id,
            MuteRecord.channel_id == payload.channel_id,
        )
    )
    if existing.scalar_one_or_none():
        raise HTTPException(400, "Mute record already exists")

    # Create mute record
    mute = MuteRecord(
        user_id=payload.user_id,
        scope=payload.scope,
        server_id=payload.server_id,
        channel_id=payload.channel_id,
        muted_until=muted_until,
        muted_by=admin["id"],
        reason=payload.reason,
    )
    db.add(mute)
    await db.commit()

    return {"id": mute.id, "message": "User muted successfully"}


@router.delete("/{mute_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_mute(
    mute_id: int,
    admin: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Remove a mute record. Admin only."""
    result = await db.execute(select(MuteRecord).where(MuteRecord.id == mute_id))
    mute = result.scalar_one_or_none()
    if not mute:
        raise HTTPException(404, "Mute record not found")

    await db.delete(mute)
    await db.commit()
    return None


@router.get("/user/{user_id}")
async def get_user_mutes(
    user_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """Get all active mute records for a user."""
    result = await db.execute(select(MuteRecord).where(MuteRecord.user_id == user_id))
    mutes = result.scalars().all()

    # Filter out expired temporary mutes
    now = datetime.now(timezone.utc)
    active_mutes = [m for m in mutes if m.muted_until is None or m.muted_until > now]

    return [
        {
            "id": m.id,
            "scope": m.scope,
            "server_id": m.server_id,
            "channel_id": m.channel_id,
            "muted_until": m.muted_until.isoformat().replace("+00:00", "Z")
            if m.muted_until
            else None,
            "reason": m.reason,
        }
        for m in active_mutes
    ]
