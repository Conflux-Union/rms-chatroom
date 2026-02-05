from __future__ import annotations

import logging
from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from .config import get_settings

logger = logging.getLogger(__name__)


class Base(DeclarativeBase):
    pass


settings = get_settings()
engine = create_async_engine(settings.database_url, echo=settings.debug)
async_session_maker = async_sessionmaker(
    engine, class_=AsyncSession, expire_on_commit=False
)


def run_alembic_migrations() -> None:
    """Run Alembic migrations to upgrade database to latest version.

    This function runs synchronously and should be called during app startup
    before any async database operations.
    """
    from alembic import command
    from alembic.config import Config

    # Get the backend directory (where alembic.ini lives)
    backend_dir = Path(__file__).resolve().parent.parent
    alembic_ini = backend_dir / "alembic.ini"

    if not alembic_ini.exists():
        logger.warning(f"alembic.ini not found at {alembic_ini}, skipping migrations")
        return

    alembic_cfg = Config(str(alembic_ini))
    # Set script_location relative to backend directory
    alembic_cfg.set_main_option("script_location", str(backend_dir / "migrations"))

    try:
        logger.info("Running database migrations...")
        command.upgrade(alembic_cfg, "head")
        logger.info("Database migrations completed")
    except SQLAlchemyError as e:
        logger.error(f"Migration failed: {e}")
        raise


async def init_db():
    """Initialize database by running Alembic migrations.

    Alembic handles both:
    - Creating new tables for fresh deployments
    - Adding missing columns/tables for existing deployments
    """
    # Run Alembic migrations (synchronous, but handles async DB internally)
    run_alembic_migrations()


async def get_db():
    async with async_session_maker() as session:
        try:
            yield session
            await session.commit()
        except SQLAlchemyError:
            await session.rollback()
            raise
