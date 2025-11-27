from __future__ import annotations

from typing import Any

import httpx

from ..core.config import get_settings


settings = get_settings()


class SSOClient:
    """Client to verify tokens against RMSSSO."""

    @staticmethod
    async def verify_token(token: str) -> dict[str, Any] | None:
        """
        Verify token with RMSSSO and return user info.
        Returns None if token is invalid.
        """
        url = f"{settings.sso_base_url}{settings.sso_verify_endpoint}"
        headers = {"Authorization": f"Bearer {token}"}

        async with httpx.AsyncClient(timeout=10.0) as client:
            try:
                resp = await client.get(url, headers=headers)
                if resp.status_code == 200:
                    data = resp.json()
                    if data.get("success") and data.get("user"):
                        return data["user"]
                return None
            except (httpx.RequestError, httpx.TimeoutException):
                return None

    @staticmethod
    def get_login_url(redirect_url: str) -> str:
        """Generate SSO login URL with redirect callback."""
        return f"{settings.sso_base_url}/?redirect_url={redirect_url}"
