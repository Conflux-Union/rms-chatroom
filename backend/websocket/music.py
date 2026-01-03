"""WebSocket endpoint for music state synchronization."""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from ..services.sso_client import SSOClient


logger = logging.getLogger(__name__)

router = APIRouter()

# Global set of connected music WebSocket clients
_music_clients: set[WebSocket] = set()
_lock = asyncio.Lock()


async def broadcast_music_state(event_type: str, data: dict[str, Any]) -> None:
    """Broadcast music commands/state to all connected clients."""
    if not _music_clients:
        logger.warning(f"No music WebSocket clients connected, cannot broadcast {event_type}")
        return

    # For play/pause/resume/seek commands, send directly without wrapping in "data"
    if event_type in ("play", "pause", "resume", "seek"):
        message = json.dumps({"type": event_type, **data})
    else:
        # For music_state, wrap in "data" field
        message = json.dumps({"type": event_type, "data": data})

    logger.info(f"Broadcasting music event '{event_type}' to {len(_music_clients)} clients")

    disconnected: list[WebSocket] = []

    async with _lock:
        for ws in _music_clients:
            try:
                await ws.send_text(message)
            except Exception as e:
                logger.error(f"Failed to send to client: {e}")
                disconnected.append(ws)

        for ws in disconnected:
            _music_clients.discard(ws)

    if disconnected:
        logger.warning(f"Removed {len(disconnected)} disconnected clients")


async def get_user_from_token(token: str) -> dict | None:
    """Verify token and get user info."""
    return await SSOClient.verify_token(token)


@router.websocket("/ws/music")
async def music_websocket(websocket: WebSocket, token: str | None = None):
    """
    WebSocket endpoint for music state synchronization.
    Clients receive real-time updates when playback state changes.
    
    Messages sent to clients:
    - {"type": "music_state", "data": {...}}
    - {"type": "connected"}
    """
    if not token:
        await websocket.close(code=4001, reason="Missing token")
        return
    
    user = await get_user_from_token(token)
    if not user:
        await websocket.close(code=4001, reason="Invalid token")
        return
    
    await websocket.accept()
    
    async with _lock:
        _music_clients.add(websocket)
    
    logger.info(f"Music WebSocket connected: user {user.get('username')}")
    
    try:
        await websocket.send_json({"type": "connected"})
        
        # Keep connection alive, handle ping/pong
        while True:
            try:
                data = await websocket.receive_text()
                msg = json.loads(data)
                # Handle ping
                if msg.get("type") == "ping":
                    await websocket.send_json({"type": "pong"})
            except json.JSONDecodeError:
                continue
    
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.error(f"Music WebSocket error: {e}")
    finally:
        async with _lock:
            _music_clients.discard(websocket)
        logger.info(f"Music WebSocket disconnected: user {user.get('username')}")
