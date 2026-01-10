from __future__ import annotations

import logging
import re
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from ..core.database import get_db
from ..models.server import Attachment, Channel, ChannelType, Message
from .deps import CurrentUser
from .schemas import ReactionGroupResponse, ReactionUserResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/channels/{channel_id}/messages", tags=["messages"])


class MessageCreate(BaseModel):
    content: str = ""
    attachment_ids: list[int] = []
    reply_to_id: int | None = None


class AttachmentResponse(BaseModel):
    id: int
    filename: str
    content_type: str
    size: int
    url: str

    class Config:
        from_attributes = True


class ReplyToResponse(BaseModel):
    """Minimal info about the replied-to message."""

    id: int
    user_id: int
    username: str
    content: str  # Truncated preview


class MentionResponse(BaseModel):
    """User mentioned in a message."""

    id: int
    username: str


class MessageResponse(BaseModel):
    id: int
    channel_id: int
    user_id: int
    username: str
    content: str
    created_at: datetime
    attachments: list[AttachmentResponse] = []
    # New fields for message management
    is_deleted: bool = False
    deleted_by: int | None = None
    edited_at: datetime | None = None
    # Reply feature
    reply_to_id: int | None = None
    reply_to: ReplyToResponse | None = None
    # Mentions feature
    mentions: list[MentionResponse] = []
    # Reactions feature
    reactions: list[ReactionGroupResponse] = []

    class Config:
        from_attributes = True


def _attachment_to_response(att: Attachment) -> AttachmentResponse:
    """Convert Attachment model to response with URL."""
    return AttachmentResponse(
        id=att.id,
        filename=att.filename,
        content_type=att.content_type,
        size=att.size,
        url=f"/api/files/{att.id}",
    )


def _truncate_content(content: str, max_len: int = 100) -> str:
    """Truncate content for reply preview."""
    if len(content) <= max_len:
        return content
    return content[: max_len - 3] + "..."


def _message_to_response(msg: Message) -> MessageResponse:
    """Convert Message model to response with attachments and reply info."""
    import re

    reply_to_data = None
    if msg.reply_to_id and msg.reply_to:
        reply_to_data = ReplyToResponse(
            id=msg.reply_to.id,
            user_id=msg.reply_to.user_id,
            username=msg.reply_to.username,
            content=_truncate_content(msg.reply_to.content)
            if not msg.reply_to.is_deleted
            else "[Message deleted]",
        )

    # Parse mentions from content (same logic as WebSocket)
    # We parse from content rather than stored IDs since we don't have username lookup here
    mentions_data = []
    if msg.content:
        mention_pattern = re.compile(r"@(\w+)")
        mentioned_usernames = mention_pattern.findall(msg.content)
        # Deduplicate while preserving order
        seen = set()
        for username in mentioned_usernames:
            if username not in seen:
                seen.add(username)
                # Use placeholder ID since we don't have user lookup in this context
                # Frontend primarily uses username for display anyway
                mentions_data.append(MentionResponse(id=0, username=username))

    # Group reactions by emoji
    reactions_data = []
    if hasattr(msg, "reactions") and msg.reactions:
        groups: dict[str, ReactionGroupResponse] = {}
        for r in msg.reactions:
            if r.emoji not in groups:
                groups[r.emoji] = ReactionGroupResponse(
                    emoji=r.emoji, count=0, users=[]
                )
            groups[r.emoji].count += 1
            groups[r.emoji].users.append(
                ReactionUserResponse(id=r.user_id, username=r.username)
            )
        reactions_data = list(groups.values())

    return MessageResponse(
        id=msg.id,
        channel_id=msg.channel_id,
        user_id=msg.user_id,
        username=msg.username,
        content=msg.content,
        created_at=msg.created_at,
        attachments=[_attachment_to_response(att) for att in msg.attachments],
        is_deleted=msg.is_deleted,
        deleted_by=msg.deleted_by,
        edited_at=msg.edited_at,
        reply_to_id=msg.reply_to_id,
        reply_to=reply_to_data,
        mentions=mentions_data,
        reactions=reactions_data,
    )


@router.get("", response_model=list[MessageResponse])
async def get_messages(
    channel_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
    limit: int = Query(50, le=100),
    before: int | None = Query(None),
):
    """Get messages from a text channel."""
    # Verify channel exists and is text type
    channel_result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = channel_result.scalar_one_or_none()
    if not channel:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found"
        )
    if channel.type != ChannelType.TEXT:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Not a text channel"
        )

    query = (
        select(Message)
        .where(
            Message.channel_id == channel_id,
            Message.is_deleted == False,  # Filter out deleted messages
        )
        .options(
            selectinload(Message.attachments),
            selectinload(Message.reply_to),
            selectinload(Message.reactions),
        )
    )
    if before:
        query = query.where(Message.id < before)
    query = query.order_by(Message.id.desc()).limit(limit)

    result = await db.execute(query)
    messages = result.scalars().all()

    # Return in chronological order with attachments
    return [_message_to_response(msg) for msg in reversed(messages)]


@router.post("/debug", status_code=status.HTTP_200_OK)
async def debug_message(channel_id: int, request: Request):
    """Debug endpoint to see raw request body."""
    body = await request.body()
    headers = dict(request.headers)
    logger.info(f"DEBUG: channel_id={channel_id}, body={body!r}, headers={headers}")
    return {"body": body.decode("utf-8", errors="replace"), "headers": headers}


@router.post("", response_model=MessageResponse, status_code=status.HTTP_201_CREATED)
async def create_message(
    channel_id: int,
    payload: MessageCreate,
    user: CurrentUser,
    request: Request,
    db: AsyncSession = Depends(get_db),
):
    """Send a message to a text channel."""
    logger.info(
        f"create_message: channel_id={channel_id}, content={payload.content!r}, attachments={payload.attachment_ids}, user={user.get('username')}"
    )

    # Must have content or attachments
    if not payload.content.strip() and not payload.attachment_ids:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Message must have content or attachments",
        )

    # Verify channel exists and is text type
    channel_result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = channel_result.scalar_one_or_none()
    if not channel:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found"
        )
    if channel.type != ChannelType.TEXT:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Not a text channel"
        )

    # Validate reply_to_id if provided
    reply_to_msg: Message | None = None
    if payload.reply_to_id:
        reply_result = await db.execute(
            select(Message).where(
                Message.id == payload.reply_to_id,
                Message.channel_id == channel_id,
            )
        )
        reply_to_msg = reply_result.scalar_one_or_none()
        if not reply_to_msg:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Reply target message not found in this channel",
            )

    # Create message
    message = Message(
        channel_id=channel_id,
        user_id=user["id"],
        username=user.get("nickname") or user["username"],
        content=payload.content,
        reply_to_id=payload.reply_to_id,
    )
    db.add(message)
    await db.flush()

    # Link attachments to message
    attachments: list[Attachment] = []
    if payload.attachment_ids:
        att_result = await db.execute(
            select(Attachment).where(
                Attachment.id.in_(payload.attachment_ids),
                Attachment.channel_id == channel_id,
                Attachment.user_id == user["id"],
                Attachment.message_id.is_(None),  # Only unlinked attachments
            )
        )
        attachments = list(att_result.scalars().all())
        for att in attachments:
            att.message_id = message.id

    await db.flush()

    # Build reply_to response data
    reply_to_data = None
    if reply_to_msg:
        reply_to_data = ReplyToResponse(
            id=reply_to_msg.id,
            user_id=reply_to_msg.user_id,
            username=reply_to_msg.username,
            content=_truncate_content(reply_to_msg.content)
            if not reply_to_msg.is_deleted
            else "[Message deleted]",
        )

    # Build response directly without accessing ORM relationship
    return MessageResponse(
        id=message.id,
        channel_id=message.channel_id,
        user_id=message.user_id,
        username=message.username,
        content=message.content,
        created_at=message.created_at,
        attachments=[_attachment_to_response(att) for att in attachments],
        is_deleted=message.is_deleted,
        deleted_by=message.deleted_by,
        edited_at=message.edited_at,
        reply_to_id=message.reply_to_id,
        reply_to=reply_to_data,
    )


class MessageEdit(BaseModel):
    content: str


@router.patch("/{message_id}", response_model=MessageResponse)
async def edit_message(
    channel_id: int,
    message_id: int,
    payload: MessageEdit,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """Edit a message. Only the author can edit their own messages."""
    if not payload.content.strip():
        raise HTTPException(status_code=400, detail="Content cannot be empty")

    result = await db.execute(
        select(Message)
        .where(Message.id == message_id, Message.channel_id == channel_id)
        .options(selectinload(Message.attachments))
    )
    message = result.scalar_one_or_none()
    if not message:
        raise HTTPException(status_code=404, detail="Message not found")

    if message.is_deleted:
        raise HTTPException(status_code=400, detail="Cannot edit deleted message")

    # Only the author can edit
    if message.user_id != user["id"]:
        raise HTTPException(status_code=403, detail="Can only edit own messages")

    # Update content
    message.content = payload.content.strip()
    now = datetime.utcnow()
    message.edited_at = now

    # Extract data before commit (to avoid lazy loading issues)
    content = message.content
    edited_at_str = now.isoformat()

    await db.commit()
    await db.refresh(message)

    # Broadcast edit event via WebSocket
    from ..websocket.manager import chat_manager

    await chat_manager.broadcast_to_channel(
        channel_id,
        {
            "type": "message_edited",
            "message_id": message_id,
            "content": content,
            "edited_at": edited_at_str,
        },
    )

    return _message_to_response(message)


@router.delete("/{message_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_message(
    channel_id: int,
    message_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """Delete (recall) a message. Users can delete own messages within 2 minutes, admins can delete any."""
    # Query message
    result = await db.execute(
        select(Message).where(
            Message.id == message_id, Message.channel_id == channel_id
        )
    )
    message = result.scalar_one_or_none()
    if not message:
        raise HTTPException(status_code=404, detail="Message not found")

    if message.is_deleted:
        raise HTTPException(status_code=400, detail="Message already deleted")

    is_admin = user.get("permission_level", 0) >= 3
    is_owner = message.user_id == user["id"]

    # Permission check
    if not is_admin and not is_owner:
        raise HTTPException(status_code=403, detail="Cannot delete others' messages")

    # Non-admins need to check 2-minute limit
    if not is_admin:
        elapsed = (datetime.utcnow() - message.created_at).total_seconds()
        if elapsed > 120:
            raise HTTPException(
                status_code=403, detail="Can only delete messages within 2 minutes"
            )

    # Soft delete
    message.is_deleted = True
    message.deleted_at = datetime.utcnow()
    message.deleted_by = user["id"]
    await db.commit()

    # Broadcast delete event via WebSocket
    from ..websocket.manager import chat_manager

    await chat_manager.broadcast_to_channel(
        channel_id,
        {
            "type": "message_deleted",
            "message_id": message_id,
            "deleted_by": user["id"],
            "deleted_by_username": user.get("nickname") or user["username"],
        },
    )

    return None


class ChannelMemberResponse(BaseModel):
    """User who has sent messages in a channel."""

    id: int
    username: str


@router.get("/members", response_model=list[ChannelMemberResponse])
async def get_channel_members(
    channel_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
    limit: int = Query(50, le=100),
):
    """Get users who have sent messages in this channel (for @mention autocomplete).

    Returns unique users ordered by most recent message first.
    """
    # Verify channel exists and is text type
    channel_result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = channel_result.scalar_one_or_none()
    if not channel:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found"
        )
    if channel.type != ChannelType.TEXT:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Not a text channel"
        )

    # Get distinct users who have sent messages, ordered by most recent message
    from sqlalchemy import func, desc

    # Subquery to get the latest message id for each user
    subq = (
        select(Message.user_id, func.max(Message.id).label("max_id"))
        .where(Message.channel_id == channel_id, Message.is_deleted == False)
        .group_by(Message.user_id)
        .subquery()
    )

    # Join to get user info, ordered by most recent message
    query = (
        select(Message.user_id, Message.username)
        .join(subq, Message.id == subq.c.max_id)
        .order_by(desc(subq.c.max_id))
        .limit(limit)
    )

    result = await db.execute(query)
    rows = result.all()

    return [
        ChannelMemberResponse(id=row.user_id, username=row.username) for row in rows
    ]
