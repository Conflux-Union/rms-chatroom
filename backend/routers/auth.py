from __future__ import annotations

import secrets
from datetime import datetime, timedelta, timezone
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import jwt
from fastapi import APIRouter, Body, Depends, HTTPException, Query, status
from fastapi.responses import RedirectResponse, JSONResponse
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.config import get_settings
from ..core.database import get_db
from ..services.oauth_client import OAuthClient
from ..services.token_service import TokenService
from .deps import CurrentUser


router = APIRouter(prefix="/api/auth", tags=["auth"])
settings = get_settings()

_STATE_EXPIRE_MINUTES = 10


def _with_query_params(url: str, params: dict[str, str]) -> str:
    """Return URL with params merged into query string (preserves existing query)."""
    parts = urlsplit(url)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    query.update(params)
    return urlunsplit(parts._replace(query=urlencode(query)))


def _with_fragment_params(url: str, params: dict[str, str]) -> str:
    """Return URL with params merged into fragment (preserves existing fragment)."""
    parts = urlsplit(url)
    fragment = dict(parse_qsl(parts.fragment, keep_blank_values=True))
    fragment.update(params)
    return urlunsplit(parts._replace(fragment=urlencode(fragment)))


def _default_redirect_url() -> str:
    origin = (settings.cors_origins or ["http://localhost:5173"])[0]
    return origin.rstrip("/") + "/callback"


def _is_localhost(hostname: str) -> bool:
    return hostname in {"localhost", "127.0.0.1", "::1", "[::1]"}


def _normalize_redirect_url(redirect_url: str | None) -> str:
    """
    Validate and normalize redirect_url to prevent open redirects.

    Supported redirect targets:
    - Absolute URLs with origin in settings.cors_origins and path starting with /callback
    - Localhost callback servers: http(s)://localhost|127.0.0.1:<port>/callback
    - Android deep link: rmschatroom://callback
    - Relative path starting with /callback (resolved against the first cors_origins entry)
    """
    if not redirect_url:
        return _default_redirect_url()

    parts = urlsplit(redirect_url)

    # Relative path
    if not parts.scheme and not parts.netloc:
        if parts.path.startswith("/callback"):
            return (settings.cors_origins or ["http://localhost:5173"])[0].rstrip("/") + urlunsplit(
                parts._replace(scheme="", netloc="")
            )
        raise HTTPException(status_code=400, detail="Invalid redirect_url")

    # Android deep link
    if parts.scheme == "rmschatroom":
        if parts.netloc == "callback" and parts.path in {"", "/"}:
            return redirect_url
        raise HTTPException(status_code=400, detail="Invalid redirect_url")

    # HTTP(S)
    if parts.scheme in {"http", "https"}:
        hostname = parts.hostname or ""
        if _is_localhost(hostname):
            if parts.path.startswith("/callback"):
                return redirect_url
            raise HTTPException(status_code=400, detail="Invalid redirect_url")

        origin = f"{parts.scheme}://{parts.netloc}"
        allowed = {o.rstrip("/") for o in (settings.cors_origins or [])}
        if origin.rstrip("/") in allowed and parts.path.startswith("/callback"):
            return redirect_url
        raise HTTPException(status_code=400, detail="Invalid redirect_url")

    raise HTTPException(status_code=400, detail="Invalid redirect_url")


def _issue_state(redirect_url: str) -> str:
    now = datetime.now(timezone.utc)
    payload = {
        "r": redirect_url,
        "nonce": secrets.token_urlsafe(16),
        "exp": now + timedelta(minutes=_STATE_EXPIRE_MINUTES),
        "iat": now,
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def _decode_state(state: str) -> dict[str, Any] | None:
    try:
        return jwt.decode(
            state,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
            options={"require": ["exp"]},
        )
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None


def _should_put_tokens_in_fragment(redirect_url: str) -> bool:
    parts = urlsplit(redirect_url)
    if parts.scheme not in {"http", "https"}:
        return False
    hostname = parts.hostname or ""
    return not _is_localhost(hostname)


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "Bearer"
    expires_in: int


class RefreshRequest(BaseModel):
    refresh_token: str


class RefreshResponse(BaseModel):
    access_token: str
    token_type: str = "Bearer"
    expires_in: int


class LogoutRequest(BaseModel):
    refresh_token: str


@router.get("/login")
async def login(redirect_url: str | None = None):
    """
    Redirect to OAuth provider's authorization page.

    Args:
        redirect_url: URL to redirect to after successful login (frontend callback)
    """
    normalized_redirect_url = _normalize_redirect_url(redirect_url)
    state = _issue_state(normalized_redirect_url)

    authorize_url = OAuthClient.get_authorize_url(state)
    return RedirectResponse(authorize_url)


@router.get("/callback")
async def callback(
    code: str | None = Query(None),
    state: str | None = Query(None),
    error: str | None = Query(None),
    error_description: str | None = Query(None),
    db: AsyncSession = Depends(get_db),
):
    """
    OAuth callback endpoint. Exchanges code for tokens and issues local JWT.
    """
    # Handle OAuth errors
    if error:
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={"error": error, "error_description": error_description},
        )

    if not code or not state:
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={"error": "missing_params", "message": "Missing code or state"},
        )

    # Verify state
    state_data = _decode_state(state)
    if not state_data:
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={"error": "invalid_state", "message": "Invalid or expired state"},
        )
    try:
        redirect_url = _normalize_redirect_url(state_data.get("r"))
    except HTTPException:
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={"error": "invalid_redirect_url", "message": "Invalid redirect_url"},
        )

    # Exchange code for OAuth access token
    token_response = await OAuthClient.exchange_code(code)
    if not token_response:
        return JSONResponse(
            status_code=status.HTTP_502_BAD_GATEWAY,
            content={"error": "token_exchange_failed", "message": "Failed to exchange code for token"},
        )

    oauth_access_token = token_response.get("access_token")
    if not oauth_access_token:
        return JSONResponse(
            status_code=status.HTTP_502_BAD_GATEWAY,
            content={"error": "no_access_token", "message": "No access token in response"},
        )

    # Get user info from OAuth provider
    user_info = await OAuthClient.get_user_info(oauth_access_token)
    if not user_info:
        return JSONResponse(
            status_code=status.HTTP_502_BAD_GATEWAY,
            content={"error": "userinfo_failed", "message": "Failed to get user info"},
        )

    # Extract user data (adapt field names to your SSO's response format)
    user_id = user_info.get("id") or user_info.get("sub")
    username = user_info.get("username") or user_info.get("preferred_username")
    nickname = user_info.get("nickname") or user_info.get("name")
    permission_level = user_info.get("permission_level", 0)

    if not user_id or not username:
        return JSONResponse(
            status_code=status.HTTP_502_BAD_GATEWAY,
            content={"error": "invalid_userinfo", "message": "Missing user id or username"},
        )

    try:
        user_id_int = int(str(user_id))
    except (TypeError, ValueError):
        return JSONResponse(
            status_code=status.HTTP_502_BAD_GATEWAY,
            content={"error": "invalid_user_id", "message": "Unsupported user id format"},
        )

    # Create local JWT tokens
    access_token = TokenService.create_access_token(
        user_id=user_id_int,
        username=username,
        permission_level=permission_level,
        nickname=nickname,
    )
    refresh_token = await TokenService.create_refresh_token(
        db=db,
        user_id=user_id_int,
        username=username,
        permission_level=permission_level,
        nickname=nickname,
    )

    # Redirect to frontend with tokens
    # Backward compatibility: some clients read `token`, others read `access_token`.
    params = {"access_token": access_token, "token": access_token, "refresh_token": refresh_token}
    if _should_put_tokens_in_fragment(redirect_url):
        url = _with_fragment_params(redirect_url, params)
    else:
        url = _with_query_params(redirect_url, params)
    return RedirectResponse(url)


@router.post("/refresh", response_model=RefreshResponse)
async def refresh(
    payload: RefreshRequest | None = Body(default=None),
    refresh_token: str | None = Query(default=None, alias="refresh_token"),
    db: AsyncSession = Depends(get_db),
):
    """
    Refresh access token using refresh token.
    """
    token = payload.refresh_token if payload else refresh_token
    if not token:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Missing refresh_token",
        )

    user_data = await TokenService.verify_refresh_token(db=db, token=token)
    if not user_data:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired refresh token",
        )

    access_token = TokenService.create_access_token(
        user_id=user_data["user_id"],
        username=user_data["username"],
        permission_level=user_data["permission_level"],
        nickname=user_data.get("nickname"),
    )

    return RefreshResponse(
        access_token=access_token,
        expires_in=settings.access_token_expire_minutes * 60,
    )


@router.post("/logout")
async def logout(payload: LogoutRequest, db: AsyncSession = Depends(get_db)):
    """
    Logout by revoking refresh token.
    """
    await TokenService.revoke_refresh_token(db=db, token=payload.refresh_token)
    return {"success": True, "message": "Logged out successfully"}


@router.post("/revoke")
async def revoke(payload: LogoutRequest, db: AsyncSession = Depends(get_db)):
    """
    Backward compatible alias for logout endpoint.
    """
    return await logout(payload, db=db)


@router.get("/me")
async def get_me(user: CurrentUser):
    """Get current user info (validates token)."""
    return {"success": True, "user": user}


@router.get("/dev-login")
async def dev_login(redirect_url: str | None = None, db: AsyncSession = Depends(get_db)):
    """
    Development login endpoint - generates a mock token for testing.
    Only available when DEBUG=True in config.
    """
    if not settings.debug:
        return JSONResponse(
            status_code=403,
            content={"error": "Dev login only available in debug mode"}
        )

    # Create mock user tokens
    access_token = TokenService.create_access_token(
        user_id=1,
        username="testuser",
        permission_level=3,  # Admin for testing
        nickname="Test User",
    )

    # Redirect to callback with tokens
    callback = _normalize_redirect_url(redirect_url)
    refresh_token = await TokenService.create_refresh_token(
        db=db,
        user_id=1,
        username="testuser",
        permission_level=3,
        nickname="Test User",
    )

    params = {"access_token": access_token, "token": access_token, "refresh_token": refresh_token}
    if _should_put_tokens_in_fragment(callback):
        url = _with_fragment_params(callback, params)
    else:
        url = _with_query_params(callback, params)
    return RedirectResponse(url)
