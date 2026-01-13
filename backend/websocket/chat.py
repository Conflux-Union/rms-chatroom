from __future__ import annotations

import json
import re
from datetime import datetime, timezone, timedelta

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import async_session_maker
from ..models.server import Attachment, Channel, ChannelType, Message
from ..services.sso_client import SSOClient
from .manager import chat_manager


def _truncate_content(content: str, max_len: int = 100) -> str:
    """Truncate content for reply preview."""
    if len(content) <= max_len:
        return content
    return content[: max_len - 3] + "..."


router = APIRouter()


async def get_user_from_token(token: str) -> dict | None:
    """Verify token and get user info."""
    return await SSOClient.verify_token(token)


@router.websocket("/ws/chat")
async def chat_websocket(websocket: WebSocket, token: str | None = None):
    """
    Global WebSocket endpoint for real-time chat across all channels.

    Client sends:
    - {"type": "message", "channel_id": 123, "content": "...", "attachment_ids": [...], "reply_to_id": 456}

    Server broadcasts:
    - {"type": "message", "id": ..., "channel_id": ..., "user_id": ..., "username": ..., "content": ..., "created_at": ..., "attachments": [...], "mentions": [...]}
    - {"type": "connected"}
    """
    if not token:
        await websocket.close(code=4001, reason="Missing token")
        return

    user = await get_user_from_token(token)
    if not user:
        await websocket.close(code=4001, reason="Invalid token")
        return

    await chat_manager.connect_global(websocket, user)

    try:
        await websocket.send_json({"type": "connected"})

        while True:
            data = await websocket.receive_text()
            try:
                msg = json.loads(data)
            except json.JSONDecodeError:
                continue

            # Handle heartbeat ping
            if msg.get("type") == "ping" and msg.get("data") == "tribios":
                await websocket.send_json({"type": "pong", "data": "cute"})
                continue

            if msg.get("type") == "message":
                channel_id = msg.get("channel_id")
                if not channel_id:
                    await websocket.send_json(
                        {
                            "type": "error",
                            "code": "missing_channel_id",
                            "message": "channel_id is required",
                        }
                    )
                    continue

                # Verify channel exists and is text type
                async with async_session_maker() as db:
                    result = await db.execute(
                        select(Channel).where(Channel.id == channel_id)
                    )
                    channel = result.scalar_one_or_none()
                    if not channel or channel.type != ChannelType.TEXT:
                        await websocket.send_json(
                            {
                                "type": "error",
                                "code": "invalid_channel",
                                "message": "Channel not found or not a text channel",
                            }
                        )
                        continue

                    # Store server_id before session closes (to avoid DetachedInstanceError)
                    server_id = channel.server_id

                    # Check if user is muted
                    from ..services.moderation import check_user_muted

                    is_muted, reason = await check_user_muted(
                        user["id"], channel_id, db
                    )
                    if is_muted:
                        await websocket.send_json(
                            {
                                "type": "error",
                                "code": "muted",
                                "message": reason or "You are muted",
                            }
                        )
                        continue

                content = (msg.get("content") or "").strip()
                attachment_ids = msg.get("attachment_ids") or []
                reply_to_id = msg.get("reply_to_id")

                # Must have content or attachments
                if not content and not attachment_ids:
                    continue

                # Save to database
                async with async_session_maker() as db:
                    # Validate reply_to_id if provided
                    reply_to_data = None
                    if reply_to_id:
                        reply_result = await db.execute(
                            select(Message).where(
                                Message.id == reply_to_id,
                                Message.channel_id == channel_id,
                            )
                        )
                        reply_to_msg = reply_result.scalar_one_or_none()
                        if reply_to_msg:
                            preview_content = (
                                "[Message deleted]"
                                if reply_to_msg.is_deleted
                                else _truncate_content(reply_to_msg.content)
                            )
                            reply_to_data = {
                                "id": reply_to_msg.id,
                                "user_id": reply_to_msg.user_id,
                                "username": reply_to_msg.username,
                                "content": preview_content,
                            }
                        else:
                            reply_to_id = None

                    # Parse @mentions from content
                    # Search server-wide for mentioned users (not just current channel)
                    mentioned_user_ids_json = None
                    mentions_data = []
                    if content:
                        mention_pattern = re.compile(r"@(\w+)")
                        mentioned_usernames = set(mention_pattern.findall(content))

                        if mentioned_usernames:
                            # Find users who have sent messages in ANY channel of this server
                            mention_result = await db.execute(
                                select(Message.user_id, Message.username)
                                .join(Channel, Message.channel_id == Channel.id)
                                .where(
                                    Channel.server_id == server_id,
                                    Message.username.in_(mentioned_usernames),
                                )
                                .group_by(Message.user_id, Message.username)
                            )
                            found_users = mention_result.all()

                            if found_users:
                                mentioned_ids = [u.user_id for u in found_users]
                                mentioned_user_ids_json = json.dumps(mentioned_ids)
                                mentions_data = [
                                    {"id": u.user_id, "username": u.username}
                                    for u in found_users
                                ]

                    message = Message(
                        channel_id=channel_id,
                        user_id=user["id"],
                        username=user.get("nickname") or user["username"],
                        content=content,
                        reply_to_id=reply_to_id,
                        mentioned_user_ids=mentioned_user_ids_json,
                    )
                    db.add(message)
                    await db.flush()

                    # Link attachments to message
                    attachments_data = []
                    if attachment_ids:
                        att_result = await db.execute(
                            select(Attachment).where(
                                Attachment.id.in_(attachment_ids),
                                Attachment.channel_id == channel_id,
                                Attachment.user_id == user["id"],
                                Attachment.message_id.is_(None),
                            )
                        )
                        attachments = att_result.scalars().all()
                        for att in attachments:
                            att.message_id = message.id
                            attachments_data.append(
                                {
                                    "id": att.id,
                                    "filename": att.filename,
                                    "content_type": att.content_type,
                                    "size": att.size,
                                    "url": f"/api/files/{att.id}",
                                }
                            )

                    await db.commit()

                    # Determine created time to broadcast
                    def parse_client_time(val):
                        if val is None:
                            return None
                        try:
                            if isinstance(val, (int, float)):
                                v = float(val)
                                if v > 1e12:
                                    return datetime.fromtimestamp(
                                        v / 1000.0, tz=timezone.utc
                                    )
                                return datetime.fromtimestamp(v, tz=timezone.utc)
                        except Exception:
                            pass

                        if isinstance(val, str):
                            s = val.strip()
                            try:
                                if s.endswith("Z"):
                                    s2 = s[:-1] + "+00:00"
                                    return datetime.fromisoformat(s2)
                                return datetime.fromisoformat(s)
                            except Exception:
                                try:
                                    v = float(s)
                                    if v > 1e12:
                                        return datetime.fromtimestamp(
                                            v / 1000.0, tz=timezone.utc
                                        )
                                    return datetime.fromtimestamp(v, tz=timezone.utc)
                                except Exception:
                                    return None
                        return None

                    client_ts = msg.get("created_at") if isinstance(msg, dict) else None
                    parsed = parse_client_time(client_ts)
                    beijing = timezone(timedelta(hours=8))
                    if parsed is not None:
                        if parsed.tzinfo is None:
                            parsed = parsed.replace(tzinfo=timezone.utc)
                        created_beijing = parsed.astimezone(beijing)
                        created_str = created_beijing.strftime("%Y-%m-%d %H:%M")
                    else:
                        created = message.created_at
                        if created.tzinfo is None:
                            created = created.replace(tzinfo=timezone.utc)
                        created_beijing = created.astimezone(beijing)
                        created_str = created_beijing.strftime("%Y-%m-%d %H:%M")

                    # Get avatar URL for the sender
                    avatar_url = user.get("avatar_url")
                    if not avatar_url:
                        avatar_url = await SSOClient.get_avatar_url(user["id"])

                    broadcast_msg = {
                        "type": "message",
                        "id": message.id,
                        "channel_id": channel_id,
                        "user_id": message.user_id,
                        "username": message.username,
                        "avatar_url": avatar_url,
                        "content": message.content,
                        "created_at": created_str,
                        "attachments": attachments_data,
                        "reply_to_id": reply_to_id,
                        "reply_to": reply_to_data,
                        "mentions": mentions_data,
                        "reactions": [],
                    }

                    # Broadcast to all global connections
                    await chat_manager.broadcast_to_all_users(broadcast_msg)

    except WebSocketDisconnect:
        pass
    finally:
        await chat_manager.disconnect_global(websocket, user["id"])
