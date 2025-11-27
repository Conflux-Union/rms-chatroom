#!/usr/bin/env python
"""Initialize database with default server and channels."""

import asyncio
import os
import sys

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.core.database import async_session_maker, init_db
from backend.models.server import Server, Channel, ChannelType


async def main():
    print("Initializing database...")
    await init_db()

    async with async_session_maker() as db:
        # Check if default server exists
        from sqlalchemy import select
        result = await db.execute(select(Server).where(Server.name == "RMS Community"))
        existing = result.scalar_one_or_none()

        if existing:
            print(f"Default server already exists (id={existing.id})")
            return

        # Create default server
        server = Server(name="RMS Community", owner_id=1)
        db.add(server)
        await db.flush()
        print(f"Created server: {server.name} (id={server.id})")

        # Create default channels
        channels = [
            Channel(server_id=server.id, name="general", type=ChannelType.TEXT, position=0),
            Channel(server_id=server.id, name="random", type=ChannelType.TEXT, position=1),
            Channel(server_id=server.id, name="General", type=ChannelType.VOICE, position=2),
            Channel(server_id=server.id, name="Gaming", type=ChannelType.VOICE, position=3),
        ]
        db.add_all(channels)
        await db.commit()
        print(f"Created {len(channels)} channels")

    print("Done!")


if __name__ == "__main__":
    asyncio.run(main())
