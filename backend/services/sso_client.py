from __future__ import annotations

import asyncio
import logging
from typing import Any

import httpx

from ..core.config import get_settings


settings = get_settings()
logger = logging.getLogger(__name__)

# In-memory cache for avatar URLs: user_id -> (avatar_url, timestamp)
_avatar_cache: dict[int, tuple[str, float]] = {}
_AVATAR_CACHE_TTL = 300  # 5 minutes


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
                        user = data["user"]
                        # Cache avatar_url if present
                        if user.get("id") and user.get("avatar_url"):
                            import time

                            _avatar_cache[user["id"]] = (
                                user["avatar_url"],
                                time.time(),
                            )
                        return user
                return None
            except (httpx.RequestError, httpx.TimeoutException):
                return None

    @staticmethod
    async def get_avatar_url(user_id: int) -> str | None:
        """
        Get avatar URL for a user by ID using SSO's public account_info API.
        Results are cached for 5 minutes.
        """
        import time

        # Check cache first
        if user_id in _avatar_cache:
            avatar_url, cached_at = _avatar_cache[user_id]
            if time.time() - cached_at < _AVATAR_CACHE_TTL:
                return avatar_url

        # Fetch from SSO
        url = f"{settings.sso_base_url}/api/account_info?uid={user_id}"

        async with httpx.AsyncClient(timeout=5.0) as client:
            try:
                resp = await client.get(url)
                if resp.status_code == 200:
                    data = resp.json()
                    if data.get("success") and data.get("user"):
                        avatar_url = data["user"].get("avatar_url")
                        if avatar_url:
                            _avatar_cache[user_id] = (avatar_url, time.time())
                            return avatar_url
                return None
            except (httpx.RequestError, httpx.TimeoutException) as e:
                logger.warning(f"Failed to fetch avatar for user {user_id}: {e}")
                return None

    @staticmethod
    async def get_avatar_urls_batch(user_ids: list[int]) -> dict[int, str]:
        """
        Get avatar URLs for multiple users in parallel.
        Returns a dict mapping user_id to avatar_url.
        """
        import time

        result: dict[int, str] = {}
        to_fetch: list[int] = []

        # Check cache first
        for uid in user_ids:
            if uid in _avatar_cache:
                avatar_url, cached_at = _avatar_cache[uid]
                if time.time() - cached_at < _AVATAR_CACHE_TTL:
                    result[uid] = avatar_url
                    continue
            to_fetch.append(uid)

        # Fetch missing ones in parallel
        if to_fetch:
            tasks = [SSOClient.get_avatar_url(uid) for uid in to_fetch]
            fetched = await asyncio.gather(*tasks, return_exceptions=True)
            for uid, avatar_url in zip(to_fetch, fetched):
                if isinstance(avatar_url, str):
                    result[uid] = avatar_url

        return result

    @staticmethod
    def get_login_url(redirect_url: str) -> str:
        """Generate SSO login URL with redirect callback."""
        return f"{settings.sso_base_url}/?redirect_url={redirect_url}"
