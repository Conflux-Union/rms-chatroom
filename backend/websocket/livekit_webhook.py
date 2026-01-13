from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import APIRouter, Request, HTTPException
from livekit import api

from ..core.config import get_settings

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/api/livekit/webhook")
async def livekit_webhook(request: Request):
    """
    Handle LiveKit webhook events.

    Events we care about:
    - participant_joined: User joined a room
    - participant_left: User left a room
    - track_published: User published a track (mic/camera)
    - track_unpublished: User unpublished a track
    """
    settings = get_settings()

    # Verify webhook signature
    body = await request.body()
    auth_header = request.headers.get("Authorization", "")

    try:
        # Verify the webhook token
        receiver = api.WebhookReceiver(
            settings.livekit_api_key,
            settings.livekit_api_secret
        )
        event = receiver.receive(body.decode(), auth_header)
    except Exception as e:
        logger.error(f"Failed to verify webhook: {e}")
        raise HTTPException(status_code=401, detail="Invalid webhook signature")

    # Handle different event types
    event_type = event.event
    logger.info(f"Received LiveKit webhook: {event_type}")

    if event_type in ["participant_joined", "participant_left", "track_published", "track_unpublished"]:
        # Broadcast voice users update
        from .voice import broadcast_voice_users_update
        asyncio.create_task(broadcast_voice_users_update())

    return {"status": "ok"}
