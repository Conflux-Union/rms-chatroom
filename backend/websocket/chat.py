from __future__ import annotations

import json
from datetime import datetime

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import async_session_maker
from ..models.server import Channel, ChannelType, Message
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
    - Client sends: {"type": "message", "content": "..."}
    - Server broadcasts: {"type": "message", "id": ..., "user_id": ..., "username": ..., "content": ..., "created_at": ...}
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

            if msg.get("type") == "message" and msg.get("content"):
                content = msg["content"].strip()
                if not content:
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
                    await db.commit()
                    await db.refresh(message)

                    # Broadcast to channel
                    broadcast_msg = {
                        "type": "message",
                        "id": message.id,
                        "user_id": message.user_id,
                        "username": message.username,
                        "content": message.content,
                        "created_at": message.created_at.isoformat(),
                    }
                    await chat_manager.broadcast_to_channel(channel_id, broadcast_msg)

    except WebSocketDisconnect:
        pass
    finally:
        await chat_manager.disconnect(websocket, channel_id)
