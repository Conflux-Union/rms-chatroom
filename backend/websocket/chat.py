from __future__ import annotations

import json
from datetime import datetime, timezone, timedelta

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import async_session_maker
from ..models.server import Attachment, Channel, ChannelType, Message
from ..services.sso_client import SSOClient
from .manager import chat_manager


router = APIRouter()


async def get_user_from_token(token: str) -> dict | None:
    """Verify token and get user info."""
    return await SSOClient.verify_token(token)


@router.websocket("/ws/chat/{channel_id}")
async def chat_websocket(websocket: WebSocket, channel_id: int, token: str | None = None):
    """
    WebSocket endpoint for real-time chat.

    Messages:
    - Client sends: {"type": "message", "content": "...", "attachment_ids": [...]}
    - Server broadcasts: {"type": "message", "id": ..., "user_id": ..., "username": ..., "content": ..., "created_at": ..., "attachments": [...]}
    - Server sends on connect: {"type": "connected", "channel_id": ...}
    """
    if not token:
        await websocket.close(code=4001, reason="Missing token")
        return

    user = await get_user_from_token(token)
    if not user:
        await websocket.close(code=4001, reason="Invalid token")
        return

    # Verify channel exists and is text type
    async with async_session_maker() as db:
        result = await db.execute(select(Channel).where(Channel.id == channel_id))
        channel = result.scalar_one_or_none()
        if not channel or channel.type != ChannelType.TEXT:
            await websocket.close(code=4004, reason="Channel not found or not a text channel")
            return

    await chat_manager.connect(websocket, channel_id, user)

    try:
        await websocket.send_json({"type": "connected", "channel_id": channel_id})

        while True:
            data = await websocket.receive_text()
            try:
                msg = json.loads(data)
            except json.JSONDecodeError:
                continue

            if msg.get("type") == "message":
                content = (msg.get("content") or "").strip()
                attachment_ids = msg.get("attachment_ids") or []

                # Must have content or attachments
                if not content and not attachment_ids:
                    continue

                # Save to database
                async with async_session_maker() as db:
                    message = Message(
                        channel_id=channel_id,
                        user_id=user["id"],
                        username=user.get("nickname") or user["username"],
                        content=content,
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
                            attachments_data.append({
                                "id": att.id,
                                "filename": att.filename,
                                "content_type": att.content_type,
                                "size": att.size,
                                "url": f"/api/files/{att.id}",
                            })

                    await db.commit()

                    # Determine created time to broadcast: prefer client-sent timestamp if valid
                    def parse_client_time(val):
                        if val is None:
                            return None
                        # numeric epoch (ms or s)
                        try:
                            if isinstance(val, (int, float)):
                                v = float(val)
                                # heuristic: > 1e12 -> ms
                                if v > 1e12:
                                    return datetime.fromtimestamp(v / 1000.0, tz=timezone.utc)
                                return datetime.fromtimestamp(v, tz=timezone.utc)
                        except Exception:
                            pass

                        # string ISO or numeric string
                        if isinstance(val, str):
                            s = val.strip()
                            try:
                                if s.endswith('Z'):
                                    s2 = s[:-1] + '+00:00'
                                    return datetime.fromisoformat(s2)
                                return datetime.fromisoformat(s)
                            except Exception:
                                try:
                                    v = float(s)
                                    if v > 1e12:
                                        return datetime.fromtimestamp(v / 1000.0, tz=timezone.utc)
                                    return datetime.fromtimestamp(v, tz=timezone.utc)
                                except Exception:
                                    return None
                        return None

                    client_ts = msg.get('created_at') if isinstance(msg, dict) else None
                    parsed = parse_client_time(client_ts)
                    beijing = timezone(timedelta(hours=8))
                    if parsed is not None:
                        # if parsed has no tzinfo, assume UTC
                        if parsed.tzinfo is None:
                            parsed = parsed.replace(tzinfo=timezone.utc)
                        created_beijing = parsed.astimezone(beijing)
                        created_str = created_beijing.strftime('%Y-%m-%d %H:%M')
                    else:
                        # fallback to DB timestamp
                        created = message.created_at
                        if created.tzinfo is None:
                            created = created.replace(tzinfo=timezone.utc)
                        created_beijing = created.astimezone(beijing)
                        created_str = created_beijing.strftime('%Y-%m-%d %H:%M')

                    # Broadcast to channel
                    broadcast_msg = {
                        "type": "message",
                        "id": message.id,
                        "channel_id": channel_id,
                        "user_id": message.user_id,
                        "username": message.username,
                        "content": message.content,
                        "created_at": created_str,
                        "attachments": attachments_data,
                    }
                    await chat_manager.broadcast_to_channel(channel_id, broadcast_msg)

    except WebSocketDisconnect:
        pass
    finally:
        await chat_manager.disconnect(websocket, channel_id)
