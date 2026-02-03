from __future__ import annotations

import asyncio
import json
from typing import Any

from fastapi import WebSocket


class ConnectionManager:
    """Manages WebSocket connections for chat and voice signaling."""

    def __init__(self):
        # channel_id -> list of (websocket, user_info)
        self.active_connections: dict[int, list[tuple[WebSocket, dict[str, Any]]]] = {}
        # Global connections: user_id -> list of (websocket, user_info)
        self.global_connections: dict[str, list[tuple[WebSocket, dict[str, Any]]]] = {}
        self._lock = asyncio.Lock()

    async def connect(self, websocket: WebSocket, channel_id: int, user: dict[str, Any]):
        await websocket.accept()
        async with self._lock:
            if channel_id not in self.active_connections:
                self.active_connections[channel_id] = []
            self.active_connections[channel_id].append((websocket, user))

    async def disconnect(self, websocket: WebSocket, channel_id: int):
        async with self._lock:
            if channel_id in self.active_connections:
                self.active_connections[channel_id] = [
                    (ws, u) for ws, u in self.active_connections[channel_id] if ws != websocket
                ]
                if not self.active_connections[channel_id]:
                    del self.active_connections[channel_id]

    async def broadcast_to_channel(self, channel_id: int, message: dict[str, Any], exclude: WebSocket | None = None):
        """Broadcast message to all connections in a channel."""
        if channel_id not in self.active_connections:
            return

        data = json.dumps(message)
        disconnected = []

        for ws, user in self.active_connections[channel_id]:
            if ws == exclude:
                continue
            try:
                await ws.send_text(data)
            except Exception:
                disconnected.append(ws)

        # Clean up disconnected
        for ws in disconnected:
            await self.disconnect(ws, channel_id)

    async def send_to_user(self, channel_id: int, user_id: str, message: dict[str, Any]):
        """Send message to a specific user in a channel."""
        if channel_id not in self.active_connections:
            return

        data = json.dumps(message)
        for ws, user in self.active_connections[channel_id]:
            if user.get("id") == user_id:
                try:
                    await ws.send_text(data)
                except Exception:
                    await self.disconnect(ws, channel_id)
                break

    async def broadcast_binary(self, channel_id: int, data: bytes, exclude: WebSocket | None = None):
        """Broadcast binary data to all connections in a channel."""
        if channel_id not in self.active_connections:
            return

        disconnected = []
        for ws, user in self.active_connections[channel_id]:
            if ws == exclude:
                continue
            try:
                await ws.send_bytes(data)
            except Exception:
                disconnected.append(ws)

        for ws in disconnected:
            await self.disconnect(ws, channel_id)

    def get_channel_users(self, channel_id: int) -> list[dict[str, Any]]:
        """Get list of users connected to a channel."""
        if channel_id not in self.active_connections:
            return []
        return [user for _, user in self.active_connections[channel_id]]

    # Global connection methods
    async def connect_global(self, websocket: WebSocket, user: dict[str, Any]):
        """Connect a user to global chat (receives all messages from all channels)."""
        await websocket.accept()
        user_id = user["id"]
        async with self._lock:
            if user_id not in self.global_connections:
                self.global_connections[user_id] = []
            self.global_connections[user_id].append((websocket, user))

    async def disconnect_global(self, websocket: WebSocket, user_id: str):
        """Disconnect a user from global chat."""
        async with self._lock:
            if user_id in self.global_connections:
                self.global_connections[user_id] = [
                    (ws, u) for ws, u in self.global_connections[user_id] if ws != websocket
                ]
                if not self.global_connections[user_id]:
                    del self.global_connections[user_id]

    async def broadcast_to_all_users(self, message: dict[str, Any]):
        """Broadcast message to all global connections."""
        data = json.dumps(message)
        disconnected = []

        async with self._lock:
            for user_id, connections in list(self.global_connections.items()):
                for ws, user in connections:
                    try:
                        await ws.send_text(data)
                    except Exception:
                        disconnected.append((ws, user_id))

        # Clean up disconnected
        for ws, user_id in disconnected:
            await self.disconnect_global(ws, user_id)

    async def send_to_user_global(self, user_id: str, message: dict[str, Any]):
        """Send message to a specific user's global connections."""
        if user_id not in self.global_connections:
            return

        data = json.dumps(message)
        disconnected = []

        for ws, user in self.global_connections[user_id]:
            try:
                await ws.send_text(data)
            except Exception:
                disconnected.append(ws)

        # Clean up disconnected
        for ws in disconnected:
            await self.disconnect_global(ws, user_id)


# Global manager instance
chat_manager = ConnectionManager()
voice_manager = ConnectionManager()
global_state_manager = ConnectionManager()
