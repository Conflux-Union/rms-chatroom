# Feature Implementation Plan: Mentions, Reply, Reactions

## Overview

Adding three core chat features to RMS Discord:
1. **Reply** - Reply to specific messages ✅ DONE
2. **@Mentions** - Mention users in messages ✅ DONE
3. **Reactions** - Add emoji reactions to messages

---

## Phase 1: Reply Feature (Priority: HIGH) ✅ COMPLETED

### 1.1 Backend Model Changes ✅

**File:** `backend/models/server.py`

```python
# Added to Message model
reply_to_id: Mapped[int | None] = mapped_column(ForeignKey("messages.id", ondelete="SET NULL"), nullable=True, index=True)
reply_to: Mapped["Message | None"] = relationship("Message", remote_side="Message.id", foreign_keys=[reply_to_id], lazy="joined")
```

### 1.2 Backend API/WebSocket Changes ✅

**File:** `backend/websocket/chat.py`
- Accept `reply_to_id` in message payload
- Include `reply_to` summary in broadcast (id, username, content preview)

**File:** `backend/routers/messages.py`
- Include `reply_to` in message list response
- Added `ReplyToResponse` model
- Modified `_message_to_response` to include reply info

### 1.3 Frontend Type Changes ✅

**File:** `packages/shared/src/types/index.ts`

```typescript
export interface ReplyTo {
  id: number
  user_id: number
  username: string
  content: string  // Truncated preview
}

export interface Message {
  // ... existing fields
  reply_to_id?: number
  reply_to?: ReplyTo
}
```

### 1.4 Frontend UI Changes ✅

**File:** `packages/shared/src/components/ChatArea.vue`

- Added "Reply" button to message context menu
- Show reply preview above input when replying
- Display replied message preview in message bubble
- Click on reply preview scrolls to original message

### 1.5 Database Migration ✅

```sql
ALTER TABLE messages ADD COLUMN reply_to_id INTEGER REFERENCES messages(id);
CREATE INDEX ix_messages_reply_to_id ON messages(reply_to_id);
```

---

## Phase 2: @Mentions Feature (Priority: MEDIUM) ✅ COMPLETED

### 2.1 Backend Model Changes ✅

**File:** `backend/models/server.py`

```python
# Add to Message model
mentioned_user_ids: Mapped[str | None] = mapped_column(Text, nullable=True)  # JSON array: "[1, 2, 3]"
```

### 2.2 Backend API Changes ✅

**File:** `backend/routers/messages.py`

- Added endpoint: `GET /api/channels/{channel_id}/messages/members` - List channel members for mention autocomplete
- Added `MentionResponse` and `ChannelMemberResponse` models
- Updated `MessageResponse` to include `mentions` field

**File:** `backend/websocket/chat.py`

- Parse `@username` patterns from content using regex
- Store mentioned user IDs as JSON in `mentioned_user_ids` field
- Include `mentions` data in broadcast message

### 2.3 Frontend Changes ✅

**File:** `packages/shared/src/types/index.ts`

- Added `Mention` interface
- Updated `Message` interface with `mentions` field

**File:** `packages/shared/src/components/ChatArea.vue`

- Implemented `@` trigger in input field with cursor position tracking
- Show user autocomplete dropdown with keyboard navigation (↑↓ Enter Esc)
- Highlight mentions in message content with special styling
- `renderMessageContent()` function to parse and highlight @mentions

### 2.4 Database Migration ✅

```sql
ALTER TABLE messages ADD COLUMN mentioned_user_ids TEXT;
```

---

## Phase 3: Reactions Feature (Priority: MEDIUM) ✅ COMPLETED

### 3.1 Backend Model Changes ✅

**File:** `backend/models/server.py`

```python
class Reaction(Base):
    __tablename__ = "reactions"
    
    id: Mapped[int] = mapped_column(primary_key=True)
    message_id: Mapped[int] = mapped_column(ForeignKey("messages.id", ondelete="CASCADE"))
    user_id: Mapped[int] = mapped_column(index=True)
    username: Mapped[str] = mapped_column(String(100))
    emoji: Mapped[str] = mapped_column(String(32))  # Unicode emoji
    created_at: Mapped[datetime] = mapped_column(default=datetime.now)
    
    # Unique constraint: one reaction per user per emoji per message
    __table_args__ = (
        UniqueConstraint("message_id", "user_id", "emoji", name="uq_reaction"),
    )

# Added to Message model
reactions: Mapped[list["Reaction"]] = relationship("Reaction", lazy="selectin", cascade="all, delete-orphan")
```

### 3.2 Backend API Changes ✅

**File:** `backend/routers/reactions.py`

```python
# Add reaction
POST /api/messages/{message_id}/reactions
Body: { "emoji": "👍" }

# Remove reaction
DELETE /api/messages/{message_id}/reactions/{emoji}

# Get reactions (grouped by emoji)
GET /api/messages/{message_id}/reactions
```

### 3.3 Backend WebSocket Changes ✅

**File:** `backend/websocket/chat.py` (via reactions.py broadcast)

Broadcast events:
```json
{ "type": "reaction_added", "message_id": 123, "emoji": "👍", "user_id": 456, "username": "user" }
{ "type": "reaction_removed", "message_id": 123, "emoji": "👍", "user_id": 456 }
```

### 3.4 Frontend Type Changes ✅

**File:** `packages/shared/src/types/index.ts`

```typescript
export interface ReactionUser {
  id: number
  username: string
}

export interface ReactionGroup {
  emoji: string
  count: number
  users: ReactionUser[]
  reacted?: boolean  // Whether current user has reacted
}

export interface Message {
  // ... existing fields
  reactions?: ReactionGroup[]
}
```

### 3.5 Frontend UI Changes ✅

**File:** `packages/shared/src/components/ChatArea.vue`

- Added emoji picker with common emojis (👍 ❤️ 😂 😮 😢 🎉 🔥 👀)
- Show reaction bar below message content
- Click existing reaction to toggle (add/remove)
- Hover reaction to show who reacted (tooltip)
- "Add Reaction" option in context menu

### 3.6 Database Migration ✅

```sql
CREATE TABLE reactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL,
    username VARCHAR(100) NOT NULL,
    emoji VARCHAR(32) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(message_id, user_id, emoji)
);
CREATE INDEX ix_reactions_message_id ON reactions(message_id);
CREATE INDEX ix_reactions_user_id ON reactions(user_id);
```

---

## Phase 4: Android Adaptation (Priority: LOW)

### 4.1 Data Model Updates

**File:** `android/app/src/main/java/cn/net/rms/chatroom/data/local/MessageEntity.kt`

```kotlin
data class MessageEntity(
    // ... existing fields
    val replyToId: Long? = null,
    val replyToUsername: String? = null,
    val replyToContent: String? = null,
    val mentionedUserIds: List<Long>? = null,
    val reactions: List<ReactionGroup>? = null
)
```

### 4.2 WebSocket Handler Updates

**File:** `android/app/src/main/java/cn/net/rms/chatroom/data/websocket/ChatWebSocket.kt`

- Handle `reaction_added` / `reaction_removed` events
- Parse reply_to and mentions from message payload

### 4.3 UI Updates

- Reply preview in message item
- Mention highlighting with AnnotatedString
- Reaction bar with emoji display

---

## Implementation Order

| # | Task | Est. Time | Dependencies |
|---|------|-----------|--------------|
| 1 | Reply - Backend Model | 0.5h | None |
| 2 | Reply - Backend API/WS | 1h | #1 |
| 3 | Reply - Frontend Types | 0.5h | #1 |
| 4 | Reply - Frontend UI | 2h | #2, #3 |
| 5 | Mentions - Backend | 1h | None |
| 6 | Mentions - Frontend | 3h | #5 |
| 7 | Reactions - Backend | 2h | None |
| 8 | Reactions - Frontend | 3h | #7 |
| 9 | Android - All features | 6h | #4, #6, #8 |

**Total Estimated Time:** ~19 hours

---

## Notes

- All database changes are additive (no breaking changes)
- WebSocket events are backward compatible (new fields are optional)
- Android can be done later without blocking web release
- Consider adding notification system for mentions in future
