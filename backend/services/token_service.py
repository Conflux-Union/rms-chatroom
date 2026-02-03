from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any

import jwt
from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.config import get_settings
from ..models.auth import RefreshToken


settings = get_settings()


class TokenService:
    """Service for creating and verifying JWT tokens."""

    @staticmethod
    def create_access_token(
        user_id: str,
        username: str,
        permission_level: int,
        nickname: str | None = None,
    ) -> str:
        """
        Create a JWT access token.

        Args:
            user_id: User's unique identifier
            username: User's username
            permission_level: User's permission level (0-5)
            nickname: User's display name (optional)

        Returns:
            JWT token string
        """
        now = datetime.now(timezone.utc)
        expire = now + timedelta(minutes=settings.access_token_expire_minutes)

        payload = {
            "sub": user_id,
            "id": user_id,
            "username": username,
            "nickname": nickname,
            "permission_level": permission_level,
            "type": "access",
            "iat": now,
            "exp": expire,
        }

        return jwt.encode(
            payload,
            settings.jwt_secret,
            algorithm=settings.jwt_algorithm,
        )

    @staticmethod
    async def create_refresh_token(
        db: AsyncSession,
        user_id: str,
        username: str,
        permission_level: int,
        nickname: str | None = None,
    ) -> str:
        """
        Create a refresh token and persist it in the database.

        Args:
            db: Database session
            user_id: User's unique identifier
            username: User's username
            permission_level: User's permission level (0-5)
            nickname: User's display name (optional)

        Returns:
            Refresh token string
        """
        token = secrets.token_urlsafe(64)
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        expires_at = datetime.now(timezone.utc) + timedelta(days=settings.refresh_token_expire_days)

        db.add(
            RefreshToken(
                token_hash=token_hash,
                user_id=user_id,
                username=username,
                nickname=nickname,
                permission_level=permission_level,
                expires_at=expires_at,
                revoked_at=None,
            )
        )

        return token

    @staticmethod
    def verify_access_token(token: str) -> dict[str, Any] | None:
        """
        Verify a JWT access token.

        Args:
            token: JWT token string

        Returns:
            User data dict if valid, None otherwise
        """
        try:
            payload = jwt.decode(
                token,
                settings.jwt_secret,
                algorithms=[settings.jwt_algorithm],
            )

            if payload.get("type") != "access":
                return None

            return {
                "id": payload["id"],
                "username": payload["username"],
                "nickname": payload.get("nickname"),
                "permission_level": payload["permission_level"],
            }
        except jwt.ExpiredSignatureError:
            return None
        except jwt.InvalidTokenError:
            return None

    @staticmethod
    async def verify_refresh_token(db: AsyncSession, token: str) -> dict[str, Any] | None:
        """
        Verify a refresh token.

        Args:
            db: Database session
            token: Refresh token string

        Returns:
            User data dict if valid, None otherwise
        """
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        result = await db.execute(
            select(RefreshToken).where(RefreshToken.token_hash == token_hash)
        )
        row = result.scalar_one_or_none()
        if not row or row.revoked_at is not None:
            return None

        now = datetime.now(timezone.utc)
        if now > row.expires_at:
            await db.execute(delete(RefreshToken).where(RefreshToken.id == row.id))
            return None

        return {
            "user_id": row.user_id,
            "username": row.username,
            "nickname": row.nickname,
            "permission_level": row.permission_level,
        }

    @staticmethod
    async def revoke_refresh_token(db: AsyncSession, token: str) -> bool:
        """
        Revoke a refresh token.

        Args:
            db: Database session
            token: Refresh token string

        Returns:
            True if token was revoked, False if not found
        """
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        now = datetime.now(timezone.utc)
        result = await db.execute(
            update(RefreshToken)
            .where(RefreshToken.token_hash == token_hash)
            .where(RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        return (result.rowcount or 0) > 0

    @staticmethod
    async def revoke_all_user_tokens(db: AsyncSession, user_id: str) -> int:
        """
        Revoke all refresh tokens for a user.

        Args:
            db: Database session
            user_id: User's unique identifier

        Returns:
            Number of tokens revoked
        """
        now = datetime.now(timezone.utc)
        result = await db.execute(
            update(RefreshToken)
            .where(RefreshToken.user_id == user_id)
            .where(RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        return int(result.rowcount or 0)
