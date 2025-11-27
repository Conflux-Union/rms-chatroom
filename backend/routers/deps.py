from __future__ import annotations

from typing import Annotated, Any

from fastapi import Depends, HTTPException, Header, status

from ..services.sso_client import SSOClient


async def get_current_user(authorization: Annotated[str | None, Header()] = None) -> dict[str, Any]:
    """Extract and verify user from Authorization header."""
    if not authorization:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing authorization header")

    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid authorization format")

    token = authorization[7:]
    user = await SSOClient.verify_token(token)
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or expired token")

    return user


CurrentUser = Annotated[dict[str, Any], Depends(get_current_user)]


def require_permission(min_level: int):
    """Factory for permission check dependency."""
    async def checker(user: CurrentUser) -> dict[str, Any]:
        if user.get("permission_level", 0) < min_level:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Insufficient permissions")
        return user
    return checker


AdminUser = Annotated[dict[str, Any], Depends(require_permission(5))]
