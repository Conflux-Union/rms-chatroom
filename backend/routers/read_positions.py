"""Read position API for cross-device sync."""

from __future__ import annotations

from collections.abc import AsyncGenerator
from typing import Annotated, Any

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import async_session_maker
from ..models.server import ReadPosition
from .deps import CurrentUser


router = APIRouter(prefix="/read-positions", tags=["read-positions"])


async def get_db_session() -> AsyncGenerator[AsyncSession, None]:
    """Get database session for dependency injection."""
    async with async_session_maker() as session:
        yield session


DbSession = Annotated[AsyncSession, Depends(get_db_session)]


class ReadPositionResponse(BaseModel):
    channel_id: int
    last_read_message_id: int
    has_mention: bool
    last_mention_message_id: int | None


class ReadPositionsResponse(BaseModel):
    positions: list[ReadPositionResponse]


@router.get("", response_model=ReadPositionsResponse)
async def get_all_read_positions(user: CurrentUser, db: DbSession) -> dict[str, Any]:
    """Get all read positions for the current user.

    Called on login to sync read positions from server.
    """
    result = await db.execute(
        select(ReadPosition).where(ReadPosition.user_id == user["id"])
    )
    positions = result.scalars().all()

    return {
        "positions": [
            {
                "channel_id": pos.channel_id,
                "last_read_message_id": pos.last_read_message_id,
                "has_mention": pos.has_mention,
                "last_mention_message_id": pos.last_mention_message_id,
            }
            for pos in positions
        ]
    }
