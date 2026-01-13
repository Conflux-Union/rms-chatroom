"""Shared Pydantic schemas for API responses."""

from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, ConfigDict


def serialize_datetime(dt: datetime) -> str:
    """Serialize datetime to ISO 8601 with Z suffix for UTC."""
    if dt is None:
        return None
    # Ensure timezone-aware
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    # Convert to UTC and format with Z suffix
    utc_dt = dt.astimezone(timezone.utc)
    return utc_dt.strftime("%Y-%m-%dT%H:%M:%S") + "Z"


class UTCDateTimeModel(BaseModel):
    """Base model that serializes all datetime fields to ISO 8601 with Z suffix."""

    model_config = ConfigDict(
        from_attributes=True,
        json_encoders={datetime: serialize_datetime},
    )


class ReactionUserResponse(BaseModel):
    """User who reacted."""

    id: int
    username: str


class ReactionGroupResponse(BaseModel):
    """Grouped reactions by emoji."""

    emoji: str
    count: int
    users: list[ReactionUserResponse]
