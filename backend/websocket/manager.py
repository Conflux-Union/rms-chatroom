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
        self.global_connections: dict[int, list[tuple[WebSocket, dict[str, Any]]]] = {}
        self._lock = asyncio.Lock()
        # Connection health tracking
        self._last_activity: dict[WebSocket, float] = {}  # ws -> timestamp
        self._heartbeat_task: asyncio.Task | None = None

    async def connect(self, websocket: WebSocket, channel_id: int, user: dict[str, Any]):
        await websocket.accept()
        async with self._lock:
            if channel_id not in self.active_connections:
                self.active_connections[channel_id] = []
            self.active_connections[channel_id].append((websocket, user))
            await self.record_activity(websocket)

    async def disconnect(self, websocket: WebSocket, channel_id: int):
        async with self._lock:
            if channel_id in self.active_connections:
                self.active_connections[channel_id] = [
                    (ws, u) for ws, u in self.active_connections[channel_id] if ws != websocket
                ]
                if not self.active_connections[channel_id]:
                    del self.active_connections[channel_id]
            # Clean up activity tracking
            self._last_activity.pop(websocket, None)

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

    async def send_to_user(self, channel_id: int, user_id: int, message: dict[str, Any]):
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
            await self.record_activity(websocket)

    async def disconnect_global(self, websocket: WebSocket, user_id: int):
        """Disconnect a user from global chat."""
        async with self._lock:
            if user_id in self.global_connections:
                self.global_connections[user_id] = [
                    (ws, u) for ws, u in self.global_connections[user_id] if ws != websocket
                ]
                if not self.global_connections[user_id]:
                    del self.global_connections[user_id]
            # Clean up activity tracking
            self._last_activity.pop(websocket, None)

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

    async def send_to_user_global(self, user_id: int, message: dict[str, Any]):
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

    async def record_activity(self, websocket: WebSocket) -> None:
        """Record last activity time for a connection."""
        self._last_activity[websocket] = asyncio.get_running_loop().time()

    async def start_heartbeat_monitor(self) -> None:
        """Start background task that monitors connection health."""
        self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())

    async def stop_heartbeat_monitor(self) -> None:
        """Stop the heartbeat monitor."""
        if self._heartbeat_task:
            self._heartbeat_task.cancel()
            try:
                await self._heartbeat_task
            except asyncio.CancelledError:
                pass

    async def _heartbeat_loop(self) -> None:
        """Scan connections every 30s, ping inactive ones, close dead ones."""
        while True:
            await asyncio.sleep(30)
            now = asyncio.get_running_loop().time()

            # Collect all websockets
            all_websockets: set[WebSocket] = set()
            async with self._lock:
                for connections in self.active_connections.values():
                    for ws, _ in connections:
                        all_websockets.add(ws)
                for connections in self.global_connections.values():
                    for ws, _ in connections:
                        all_websockets.add(ws)

            # Check each connection
            dead_connections: list[WebSocket] = []
            for ws in all_websockets:
                last_activity = self._last_activity.get(ws, now)
                inactive_time = now - last_activity

                # If inactive > 90s, force close (ping sent but no response)
                if inactive_time > 90:
                    print(f"[Heartbeat] Force closing dead connection (inactive {inactive_time:.1f}s)")
                    dead_connections.append(ws)
                    try:
                        await ws.close(code=1000, reason="Connection timeout")
                    except Exception:
                        pass
                # If inactive > 60s, send ping
                elif inactive_time > 60:
                    try:
                        await ws.send_json({"type": "ping", "data": "tribios"})
                    except Exception:
                        dead_connections.append(ws)

            # Clean up dead connections
            if dead_connections:
                async with self._lock:
                    for ws in dead_connections:
                        # Remove from active_connections
                        for channel_id, connections in list(self.active_connections.items()):
                            self.active_connections[channel_id] = [
                                (w, u) for w, u in connections if w != ws
                            ]
                            if not self.active_connections[channel_id]:
                                del self.active_connections[channel_id]

                        # Remove from global_connections
                        for user_id, connections in list(self.global_connections.items()):
                            self.global_connections[user_id] = [
                                (w, u) for w, u in connections if w != ws
                            ]
                            if not self.global_connections[user_id]:
                                del self.global_connections[user_id]

                        # Remove from activity tracking
                        self._last_activity.pop(ws, None)


# Global manager instance
chat_manager = ConnectionManager()
voice_manager = ConnectionManager()
global_state_manager = ConnectionManager()
