from __future__ import annotations

import logging
from typing import Any
from urllib.parse import urlencode

import httpx

from ..core.config import get_settings


settings = get_settings()
logger = logging.getLogger(__name__)


class OAuthClient:
    """Client for OAuth 2.0 authentication with RMSSSO."""

    @staticmethod
    def get_authorize_url(state: str, redirect_uri: str | None = None) -> str:
        """
        Generate the OAuth authorization URL.

        Args:
            state: Random state string for CSRF protection
            redirect_uri: Override redirect URI (optional)

        Returns:
            Full authorization URL to redirect user to
        """
        params = {
            "response_type": "code",
            "client_id": settings.oauth_client_id,
            "redirect_uri": redirect_uri or settings.oauth_redirect_uri,
            "scope": settings.oauth_scope,
            "state": state,
        }

        base_url = f"{settings.oauth_base_url}{settings.oauth_authorize_endpoint}"
        return f"{base_url}?{urlencode(params)}"

    @staticmethod
    async def exchange_code(
        code: str,
        redirect_uri: str | None = None,
    ) -> dict[str, Any] | None:
        """
        Exchange authorization code for access token.

        Args:
            code: Authorization code from OAuth callback
            redirect_uri: Override redirect URI (must match authorize request)

        Returns:
            Token response dict containing access_token, or None on failure
        """
        url = f"{settings.oauth_base_url}{settings.oauth_token_endpoint}"
        data = {
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": redirect_uri or settings.oauth_redirect_uri,
            "client_id": settings.oauth_client_id,
            "client_secret": settings.oauth_client_secret,
        }

        async with httpx.AsyncClient(timeout=10.0) as client:
            try:
                resp = await client.post(
                    url,
                    data=data,
                    headers={"Content-Type": "application/x-www-form-urlencoded"},
                )
                if resp.status_code == 200:
                    return resp.json()
                logger.warning(
                    f"OAuth token exchange failed: {resp.status_code} - {resp.text}"
                )
                return None
            except (httpx.RequestError, httpx.TimeoutException) as e:
                logger.error(f"OAuth token exchange error: {e}")
                return None

    @staticmethod
    async def get_user_info(access_token: str) -> dict[str, Any] | None:
        """
        Get user information from OAuth provider.

        Args:
            access_token: OAuth access token

        Returns:
            User info dict, or None on failure
        """
        url = f"{settings.oauth_base_url}{settings.oauth_userinfo_endpoint}"
        headers = {"Authorization": f"Bearer {access_token}"}

        async with httpx.AsyncClient(timeout=10.0) as client:
            try:
                resp = await client.get(url, headers=headers)
                if resp.status_code == 200:
                    return resp.json()
                logger.warning(
                    f"OAuth userinfo request failed: {resp.status_code} - {resp.text}"
                )
                return None
            except (httpx.RequestError, httpx.TimeoutException) as e:
                logger.error(f"OAuth userinfo error: {e}")
                return None
