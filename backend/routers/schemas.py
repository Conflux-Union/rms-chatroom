"""Shared Pydantic schemas for API responses."""

from pydantic import BaseModel


class ReactionUserResponse(BaseModel):
    """User who reacted."""

    id: int
    username: str


class ReactionGroupResponse(BaseModel):
    """Grouped reactions by emoji."""

    emoji: str
    count: int
    users: list[ReactionUserResponse]
