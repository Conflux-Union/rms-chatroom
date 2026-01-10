from __future__ import annotations

from urllib.parse import urlencode
import jwt
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter
from fastapi.responses import RedirectResponse, JSONResponse

from ..core.config import get_settings
from ..services.sso_client import SSOClient
from .deps import CurrentUser


router = APIRouter(prefix="/api/auth", tags=["auth"])
settings = get_settings()


@router.get("/login")
async def login(redirect_url: str | None = None):
    """Redirect to SSO login page."""
    # Use frontend callback URL
    callback = redirect_url or "http://localhost:5173/callback"
    login_url = SSOClient.get_login_url(callback)
    return RedirectResponse(login_url)


@router.get("/dev-login")
async def dev_login(redirect_url: str | None = None):
    """
    Development login endpoint - generates a mock token for testing.
    Only available when DEBUG=True in config.
    """
    if not settings.debug:
        return JSONResponse(
            status_code=403,
            content={"error": "Dev login only available in debug mode"}
        )
    
    # Create a mock JWT token
    payload = {
        "id": 1,
        "username": "testuser",
        "nickname": "Test User",
        "email": "test@example.com",
        "permission_level": 3,  # Admin for testing
        "exp": datetime.now(timezone.utc) + timedelta(days=7),
        "iat": datetime.now(timezone.utc),
    }
    
    # Use a simple secret for development
    token = jwt.encode(payload, "test-secret-key", algorithm="HS256")
    
    # Redirect to callback with token
    callback = redirect_url or "http://localhost:5173/callback"
    return RedirectResponse(f"{callback}?token={token}")


@router.get("/me")
async def get_me(user: CurrentUser):
    """Get current user info (validates token)."""
    return {"success": True, "user": user}
