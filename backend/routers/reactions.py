"""API endpoints for message reactions."""

from __future__ import annotations

import logging
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..models.server import Message, Reaction
from .deps import CurrentUser
from .schemas import ReactionGroupResponse, ReactionUserResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/messages/{message_id}/reactions", tags=["reactions"])


class ReactionCreate(BaseModel):
    emoji: str


class ReactionGroupWithReacted(ReactionGroupResponse):
    """Grouped reactions with current user's reaction status."""

    reacted: bool  # Whether current user has reacted with this emoji


class ReactionResponse(BaseModel):
    id: int
    message_id: int
    user_id: int
    username: str
    emoji: str
    created_at: datetime

    class Config:
        from_attributes = True


def group_reactions(
    reactions: list[Reaction], current_user_id: int
) -> list[ReactionGroupWithReacted]:
    """Group reactions by emoji and include user info."""
    groups: dict[str, ReactionGroupWithReacted] = {}

    for r in reactions:
        if r.emoji not in groups:
            groups[r.emoji] = ReactionGroupWithReacted(
                emoji=r.emoji, count=0, users=[], reacted=False
            )
        groups[r.emoji].count += 1
        groups[r.emoji].users.append(
            ReactionUserResponse(id=r.user_id, username=r.username)
        )
        if r.user_id == current_user_id:
            groups[r.emoji].reacted = True

    return list(groups.values())


@router.post("", response_model=ReactionResponse, status_code=status.HTTP_201_CREATED)
async def add_reaction(
    message_id: int,
    payload: ReactionCreate,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """Add a reaction to a message. If already reacted with same emoji, returns existing."""
    # Validate emoji (basic check - non-empty, reasonable length)
    emoji = payload.emoji.strip()
    if not emoji or len(emoji) > 32:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid emoji",
        )

    # Verify message exists and is not deleted
    msg_result = await db.execute(select(Message).where(Message.id == message_id))
    message = msg_result.scalar_one_or_none()
    if not message:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Message not found"
        )
    if message.is_deleted:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot react to deleted message",
        )

    # Check if user already reacted with this emoji
    existing_result = await db.execute(
        select(Reaction).where(
            Reaction.message_id == message_id,
            Reaction.user_id == user["id"],
            Reaction.emoji == emoji,
        )
    )
    existing = existing_result.scalar_one_or_none()
    if existing:
        # Already reacted, return existing
        return existing

    # Create new reaction
    reaction = Reaction(
        message_id=message_id,
        user_id=user["id"],
        username=user.get("nickname") or user["username"],
        emoji=emoji,
    )
    db.add(reaction)
    await db.commit()
    await db.refresh(reaction)

    # Broadcast reaction added via WebSocket
    from ..websocket.manager import chat_manager

    await chat_manager.broadcast_to_all_users(
        {
            "type": "reaction_added",
            "message_id": message_id,
            "channel_id": message.channel_id,
            "emoji": emoji,
            "user_id": user["id"],
            "username": user.get("nickname") or user["username"],
        },
    )

    return reaction


@router.delete("/{emoji}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_reaction(
    message_id: int,
    emoji: str,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """Remove a reaction from a message."""
    # URL decode emoji (in case it's encoded)
    from urllib.parse import unquote

    emoji = unquote(emoji)

    # Verify message exists
    msg_result = await db.execute(select(Message).where(Message.id == message_id))
    message = msg_result.scalar_one_or_none()
    if not message:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Message not found"
        )

    # Find and delete the reaction
    result = await db.execute(
        select(Reaction).where(
            Reaction.message_id == message_id,
            Reaction.user_id == user["id"],
            Reaction.emoji == emoji,
        )
    )
    reaction = result.scalar_one_or_none()
    if not reaction:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Reaction not found"
        )

    await db.delete(reaction)
    await db.commit()

    # Broadcast reaction removed via WebSocket
    from ..websocket.manager import chat_manager

    await chat_manager.broadcast_to_all_users(
        {
            "type": "reaction_removed",
            "message_id": message_id,
            "channel_id": message.channel_id,
            "emoji": emoji,
            "user_id": user["id"],
        },
    )

    return None


@router.get("", response_model=list[ReactionGroupWithReacted])
async def get_reactions(
    message_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """Get all reactions for a message, grouped by emoji."""
    # Verify message exists
    msg_result = await db.execute(select(Message).where(Message.id == message_id))
    message = msg_result.scalar_one_or_none()
    if not message:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Message not found"
        )

    # Get all reactions for this message
    result = await db.execute(
        select(Reaction)
        .where(Reaction.message_id == message_id)
        .order_by(Reaction.created_at)
    )
    reactions = result.scalars().all()

    return group_reactions(list(reactions), user["id"])
