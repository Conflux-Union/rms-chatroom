from __future__ import annotations

import json

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from ..services.sso_client import SSOClient
from .manager import global_state_manager


router = APIRouter()


async def get_user_from_token(token: str) -> dict | None:
    """Verify token and get user info."""
    return await SSOClient.verify_token(token)


@router.websocket("/ws/global")
async def global_state_websocket(websocket: WebSocket, token: str | None = None):
    """
    Global WebSocket endpoint for real-time state updates (non-chat).

    Server pushes:
    - {"type": "voice_users_update", "channel_id": 123, "users": [...]}
    - {"type": "participant_state_update", "channel_id": 123, "participants": [...]}
    - {"type": "host_mode_update", "channel_id": 123, "enabled": true, "host_name": "..."}
    - {"type": "connected"}

    Client sends:
    - {"type": "ping", "data": "tribios"} -> Server responds with {"type": "pong", "data": "cute"}
    """
    if not token:
        await websocket.close(code=4001, reason="Missing token")
        return

    user = await get_user_from_token(token)
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

            # Handle heartbeat ping
            if msg.get("type") == "ping" and msg.get("data") == "tribios":
                await websocket.send_json({"type": "pong", "data": "cute"})
                continue

    except WebSocketDisconnect:
        pass
    finally:
        await global_state_manager.disconnect_global(websocket, user["id"])
