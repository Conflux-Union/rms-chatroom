from __future__ import annotations

from urllib.parse import urlencode

from fastapi import APIRouter
from fastapi.responses import RedirectResponse

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


@router.get("/me")
async def get_me(user: CurrentUser):
    """Get current user info (validates token)."""
    return {"success": True, "user": user}
