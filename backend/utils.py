"""Shared utility functions for the backend."""

from __future__ import annotations

import re


def extract_mentioned_usernames(content: str) -> list[str]:
    """
    Extract unique usernames from @mentions in message content.

    Parses @username patterns and returns deduplicated list preserving order.

    Args:
        content: Message content to parse

    Returns:
        List of unique mentioned usernames in order of first appearance
    """
    if not content:
        return []

    mention_pattern = re.compile(r"@(\w+)")
    mentioned_usernames = mention_pattern.findall(content)

    # Deduplicate while preserving order
    seen: set[str] = set()
    result: list[str] = []
    for username in mentioned_usernames:
        if username not in seen:
            seen.add(username)
            result.append(username)

    return result
