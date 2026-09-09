# Forward Bot API

Message-sync bot API for FORWARD channels (e.g. channel 36 「消息同步」).
The bot posts structured messages that are attributed to the real platform
account when it can be matched, and to the bot proxy account otherwise.

## Authentication

All `/api/forward/*` routes require a static token configured on the server
(`forward_bot_token` in config.json):

```
Authorization: Bearer <forward_bot_token>
```

The routes are only registered when the token is configured non-empty.

## Upload a media file

Forwarded QQ images/videos must be uploaded first; the returned `id` is then
referenced in the message's `attachment_ids`.

```
POST /api/forward/channels/{channel_id}/upload
Content-Type: multipart/form-data

file=<binary>
```

Response `200`:

```json
{
  "id": 123,
  "filename": "photo.webp",
  "content_type": "image/webp",
  "size": 45678,
  "url": "/api/files/123"
}
```

Limits: 10 MB max, executable extensions blocked. Attachments are owned by the
bot account and must be linked to a message sent afterwards (they stay
unlinked/orphaned otherwise).

## Post a forwarded message

```
POST /api/forward/channels/{channel_id}/messages
Content-Type: application/json
```

```json
{
  "source": "qq",
  "sender": { "qq": 123456789, "nickname": "Trirrin" },
  "content": "text content",
  "source_message_id": "qq-message-id-123",
  "reply": {
    "source_message_id": "qq-message-id-100",
    "nickname": "重华",
    "content": "quoted text preview"
  },
  "attachment_ids": [123]
}
```

Fields:

- `source` — `"qq"` or `"game"`.
- `sender.qq` — QQ number. Matched to a platform account via the SSO email
  `<qq>@qq.com`. Requires the SSO `/api/account_info?email=` lookup.
- `sender.username` — for `source: "game"`: matched to a platform account by
  username (`/api/account_info?username=`).
- `sender.nickname` — original nickname, kept for display.
- `content` — required unless `attachment_ids` is non-empty.
- `source_message_id` — the ID on the origin platform. Used for later quote
  resolution; also deduplicates nothing (re-sending creates a new message).
- `reply` — optional quote. If the quoted `source_message_id` was already
  forwarded into the same channel, the new message becomes a real reply
  (`reply_to_id`, clickable jump). Otherwise it degrades to a quote block
  rendered from `reply.nickname` / `reply.content`.
- `attachment_ids` — attachment IDs from the upload endpoint.

Author resolution:

- Matched platform account → message is posted as that user with their
  avatar/nickname, plus a small `QQ` / `服务器` source badge.
- No match → posted as the bot proxy account (`forward_bot_user_id`),
  display name `昵称(未知用户)`.

Response `201` returns the full message object (same shape as
`GET /api/channels/:id/messages`), including `source_platform` and
`forward_meta`. The message is broadcast live over the chat WebSocket.

Errors: `400` (bad body, not a FORWARD channel), `401` (bad token),
`404` (unknown channel).

## End-to-end flow

1. For each media file: `POST /api/forward/channels/{id}/upload` → collect IDs.
2. `POST /api/forward/channels/{id}/messages` with content + attachment_ids.
3. To quote a QQ message, include `reply.source_message_id` of the message
   that was forwarded earlier.

## Channel setup

FORWARD channels are read-only for normal users (no composer in web/Android;
WS/REST sends are rejected). An admin converts a channel via
`PATCH /api/servers/{server_id}/channels/{id}` with `{"type": "FORWARD"}`
or creates one with `type: "forward"`.
