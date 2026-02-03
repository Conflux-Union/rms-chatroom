from __future__ import annotations

import json
from datetime import datetime

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from sqlalchemy import select

from ..core.database import async_session_maker
from ..models.server import ReadPosition
from ..services.token_service import TokenService
from .manager import global_state_manager


router = APIRouter()


def get_user_from_token(token: str) -> dict | None:
    """Verify token and get user info."""
    return TokenService.verify_access_token(token)


async def update_read_position(
    user_id: str,
    channel_id: int,
    last_read_message_id: int,
    has_mention: bool = False,
    last_mention_message_id: int | None = None,
) -> None:
    """Update or create read position in database."""
    async with async_session_maker() as db:
        result = await db.execute(
            select(ReadPosition).where(
                ReadPosition.user_id == user_id,
                ReadPosition.channel_id == channel_id,
            )
        )
        position = result.scalar_one_or_none()

        if position:
            # Only update if new message ID is greater (don't go backwards)
            if last_read_message_id > position.last_read_message_id:
                position.last_read_message_id = last_read_message_id
                position.updated_at = datetime.utcnow()
            # Always update mention status (can be cleared)
            position.has_mention = has_mention
            position.last_mention_message_id = last_mention_message_id
        else:
            position = ReadPosition(
                user_id=user_id,
                channel_id=channel_id,
                last_read_message_id=last_read_message_id,
                has_mention=has_mention,
                last_mention_message_id=last_mention_message_id,
            )
            db.add(position)

        await db.commit()


@router.websocket("/ws/global")
async def global_state_websocket(websocket: WebSocket, token: str | None = None):
    """
    Global WebSocket endpoint for real-time state updates (non-chat).

    Server pushes:
    - {"type": "voice_users_update", "channel_id": 123, "users": [...]}
    - {"type": "participant_state_update", "channel_id": 123, "participants": [...]}
    - {"type": "host_mode_update", "channel_id": 123, "enabled": true, "host_name": "..."}
    - {"type": "read_position_sync", "channel_id": 123, "last_read_message_id": 456, ...}
    - {"type": "connected"}

    Client sends:
    - {"type": "ping", "data": "tribios"} -> Server responds with {"type": "pong", "data": "cute"}
    - {"type": "read_position_update", "channel_id": 123, "last_read_message_id": 456, "has_mention": false}
    """
    if not token:
        await websocket.close(code=4001, reason="Missing token")
        return

    user = get_user_from_token(token)
    if not user:
        await websocket.close(code=4001, reason="Invalid token")
        return

    await global_state_manager.connect_global(websocket, user)

    try:
        await websocket.send_json({"type": "connected"})

        while True:
            data = await websocket.receive_text()
            try:
                msg = json.loads(data)
            except json.JSONDecodeError:
                continue

            msg_type = msg.get("type")

            # Handle heartbeat ping
            if msg_type == "ping" and msg.get("data") == "tribios":
                await websocket.send_json({"type": "pong", "data": "cute"})
                continue

            # Handle read position update from client
            if msg_type == "read_position_update":
                channel_id = msg.get("channel_id")
                last_read_message_id = msg.get("last_read_message_id")
                has_mention = msg.get("has_mention", False)
                last_mention_message_id = msg.get("last_mention_message_id")

                if channel_id is None or last_read_message_id is None:
                    continue

                # Save to database
                await update_read_position(
                    user_id=user["id"],
                    channel_id=channel_id,
                    last_read_message_id=last_read_message_id,
                    has_mention=has_mention,
                    last_mention_message_id=last_mention_message_id,
                )

                # Broadcast to user's other devices
                await global_state_manager.send_to_user_global(
                    user["id"],
                    {
                        "type": "read_position_sync",
                        "channel_id": channel_id,
                        "last_read_message_id": last_read_message_id,
                        "has_mention": has_mention,
                        "last_mention_message_id": last_mention_message_id,
                    },
                )

    except WebSocketDisconnect:
        pass
    finally:
        await global_state_manager.disconnect_global(websocket, user["id"])
