"""Permission utilities for SSO-based access control.

SSO has two permission dimensions:
1. Server Permission Level: 1-4 (1=lowest, 4=highest)
2. Internal/External Level: 1=external, 2=internal

Permission Checking Rules:
- Servers: Only check internal/external level (min_internal_level)
  防止外服用户看到内服服务器
  
- Channel Groups: Check BOTH server permission level AND internal/external level
  防止外服用户直接调用API访问权限等级内容
  防止权限不足的用户访问高权限内容
  
- Channels: Check BOTH server permission level AND internal/external level
  (visibility and speak permissions)
  防止外服用户直接调用API发送消息
  防止权限不足的用户发言
"""
from __future__ import annotations

from typing import Any


def _check_permission(
    user: dict[str, Any],
    min_server_level: int,
    min_internal_level: int
) -> bool:
    """
    Core permission check helper.

    Args:
        user: User info from SSO token
        min_server_level: Minimum server permission level (1-4)
        min_internal_level: Minimum internal/external level (1-2)

    Returns:
        True if user meets both level requirements
    """
    user_server_level = user.get("server_permission_level", 1)
    user_internal_level = user.get("internal_level", 1)
    return (
        user_server_level >= min_server_level and
        user_internal_level >= min_internal_level
    )


def check_server_access(
    user: dict[str, Any],
    min_server_level: int,
    min_internal_level: int
) -> bool:
    """Check if user has permission to access a server (internal level only)."""
    # Servers only check internal level, so pass 1 for server level (always passes)
    return _check_permission(user, 1, min_internal_level)


def check_channel_group_access(
    user: dict[str, Any],
    min_server_level: int,
    min_internal_level: int
) -> bool:
    """Check if user has permission to see a channel group."""
    return _check_permission(user, min_server_level, min_internal_level)


def check_channel_visibility(
    user: dict[str, Any],
    visibility_min_server_level: int,
    visibility_min_internal_level: int
) -> bool:
    """Check if user has permission to see a channel."""
    return _check_permission(user, visibility_min_server_level, visibility_min_internal_level)


def check_channel_speak_permission(
    user: dict[str, Any],
    speak_min_server_level: int,
    speak_min_internal_level: int
) -> bool:
    """Check if user has permission to speak/post in a channel."""
    return _check_permission(user, speak_min_server_level, speak_min_internal_level)


def get_user_permission_level(user: dict[str, Any]) -> tuple[int, int]:
    """
    Get user's permission levels from SSO token.
    
    Returns:
        Tuple of (server_permission_level, internal_level)
    """
    return (
        user.get("server_permission_level", 1),
        user.get("internal_level", 1)
    )
