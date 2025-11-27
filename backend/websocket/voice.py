from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from livekit.api import AccessToken, VideoGrants
from pydantic import BaseModel
from sqlalchemy import select

from ..core.config import get_settings
from ..core.database import get_db
from ..models.server import Channel, ChannelType
from ..routers.deps import CurrentUser
from sqlalchemy.ext.asyncio import AsyncSession


router = APIRouter()


class LiveKitTokenResponse(BaseModel):
    token: str
    url: str
    room_name: str


@router.get("/api/voice/{channel_id}/token", response_model=LiveKitTokenResponse)
async def get_voice_token(
    channel_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """
    Get LiveKit token for joining a voice channel.
    The room name is 'voice_{channel_id}'.
    """
    # Verify channel exists and is voice type
    result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Channel not found"
        )
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Not a voice channel"
        )
    
    settings = get_settings()
    room_name = f"voice_{channel_id}"
    
    # Create access token with grants
    token = (
        AccessToken(
            api_key=settings.livekit_api_key,
            api_secret=settings.livekit_api_secret,
        )
        .with_identity(str(user["id"]))
        .with_name(user.get("nickname") or user["username"])
        .with_grants(VideoGrants(
            room_join=True,
            room=room_name,
            can_publish=True,
            can_subscribe=True,
        ))
    )
    
    jwt_token = token.to_jwt()
    
    return LiveKitTokenResponse(
        token=jwt_token,
        url=settings.livekit_host,
        room_name=room_name,
    )


@router.get("/api/voice/{channel_id}/users")
async def get_voice_users(channel_id: int):
    """
    Get list of users currently in a voice channel.
    Note: With LiveKit, this info comes from LiveKit's room API.
    This endpoint is kept for compatibility.
    """
    # LiveKit handles participant tracking
    # Frontend should use LiveKit's participant events
    return {"users": []}
