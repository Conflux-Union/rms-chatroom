# Repository Guidelines

RMS Discord Clone - A Discord-like web application with Vue3 frontend and Go (Echo) backend, using RMSSSO for authentication.

## Project Structure & Module Organization

```
rms-discord/
├── packages/                # pnpm monorepo packages
│   ├── shared/             # Shared Vue components, stores, composables
│   │   └── src/
│   │       ├── components/ # Vue components (shared between web & desktop)
│   │       ├── composables/# Vue composables (useWebSocket, useGlowEffect, noiseCancle)
│   │       ├── stores/     # Pinia stores (auth, chat, voice, music)
│   │       ├── utils/      # Utility functions (authFetch)
│   │       ├── types/      # TypeScript types
│   │       └── views/      # Page components
│   ├── web/                # Web frontend entry point
│   │   └── src/
│   │       ├── main.ts     # Web entry
│   │       ├── router.ts   # Web router (createWebHistory)
│   │       └── App.vue
│   └── desktop/            # Desktop (Tauri) renderer entry point
│       └── src/
│           ├── main.ts     # Desktop entry
│           ├── router.ts   # Desktop router (createWebHashHistory)
│           └── App.vue     # Includes AppUpdater
├── src-tauri/              # Tauri v2 Rust backend (replaces Electron)
│   ├── Cargo.toml          # Rust dependencies
│   ├── tauri.conf.json     # Tauri config (window, CSP, updater, bundle)
│   ├── src/
│   │   ├── main.rs         # Entry point
│   │   ├── lib.rs          # Tauri commands + setup (shortcuts, store)
│   │   └── oauth.rs        # OAuth callback HTTP server (axum)
│   └── capabilities/       # Permission configuration
├── backend-go/            # Go backend (Echo framework)
│   ├── cmd/server/       # Entry point (main.go)
│   ├── internal/
│   │   ├── config/       # JSON config with env overrides
│   │   ├── db/           # Migrations (golang-migrate), sqlc queries
│   │   ├── handler/      # HTTP API handlers (Echo)
│   │   ├── middleware/    # Auth + permission middleware
│   │   ├── ws/           # WebSocket handlers (gorilla/websocket)
│   │   ├── sso/          # SSO client (avatar cache, user lookup)
│   │   ├── permission/   # Pure permission check functions
│   │   ├── jwtutil/      # Local JWT token parsing
│   │   └── music/        # QQ Music + NetEase Cloud Music API clients
│   ├── sqlc.yaml         # sqlc configuration
│   └── go.mod
├── android/              # Kotlin + Jetpack Compose Android app
├── pnpm-workspace.yaml   # pnpm workspace config
└── package.json          # Root package.json with workspace scripts
```

## Build, Test, and Development Commands

### Frontend (Monorepo)
```bash
# Install all dependencies
pnpm install

# Development
pnpm dev:web              # Web dev server on port 5173
pnpm dev:desktop          # Tauri dev (starts Vite + Rust backend)

# Production build
pnpm build:web            # Build web frontend
pnpm build:desktop        # Build desktop frontend (Vite only)

# Desktop app (Tauri)
pnpm desktop:dev          # Tauri dev mode
pnpm desktop:build        # Build Tauri distributable
```

### Backend (Go)
```bash
cd backend-go
go build ./...                    # Verify compilation
go run ./cmd/server/main.go       # Run dev server on port 8000
# Database migrations: use golang-migrate CLI
# sqlc generate: sqlc generate (requires sqlc CLI)
```

### Android
```bash
cd android
./gradlew assembleDebug    # Build debug APK
./gradlew assembleRelease  # Build release APK
./gradlew installDebug     # Install on connected device
```
Note: Requires Android Studio or command-line SDK with JDK 17+

### Testing & Validation
- Frontend: `pnpm build:web` and `pnpm build:desktop` (includes type checking)
- Backend: `cd backend-go && go build ./...` to verify compilation
- Android: `./gradlew assembleDebug` as quick build check

## Coding Style & Naming Conventions

- **Go**: Follow standard Go conventions, use `gofmt`
- **TypeScript**: Use strict mode, prefer composition API in Vue
- **Kotlin**: Follow Kotlin coding conventions, use Jetpack Compose
- **Naming**: snake_case for Go (DB fields), camelCase for TypeScript/Vue/Kotlin, PascalCase for Go exported symbols
- **Components**: PascalCase for Vue components, PascalCase for Compose screens

## Key Features

- **WebSocket Architecture**: Unified reconnecting WebSocket system
  - Base module: `packages/shared/src/composables/useReconnectingWebSocket.ts`
  - Singleton composables: `useGlobalWebSocket.ts` (state updates), `useChatWebSocket.ts` (chat)
  - Per-instance composable: `useWebSocket.ts` (voice/other)
  - Heartbeat protocol: `{"type":"ping","data":"tribios"}` / `{"type":"pong","data":"cute"}`
  - Frontend: 5s heartbeat interval, 3s pong timeout, exponential backoff (1s→30s), max 10 retries, generation IDs to ignore stale socket callbacks
  - Backend: 30s health scan, 60s inactive → server ping, 90s dead → force close
  - Backend heartbeat monitors managed by Go goroutines
- **Authentication**: OAuth 2.0 with local JWT + refresh tokens
  - Login flow: OAuth redirect with JWT-encoded CSRF state (nonce + 10min expiry)
  - Callback validates state JWT, exchanges code → SSO token → userinfo, generates local JWT
  - Local JWT contains: id, username, nickname, email, permission_level, group_level, avatar_url
  - Access token: configurable expiry (default 15 minutes), signed with `jwt_secret`
  - Refresh token: configurable expiry (default 30 days), stored as SHA-256 hash in DB with user metadata
  - Auth middleware (`middleware/auth.go`): local JWT parsing via `jwtutil.ParseToken()` — no SSO round-trip per request
  - All WebSocket handlers also use `jwtutil.ParseToken()` for authentication
  - Frontend stores `access_token` and `refresh_token` in localStorage
  - OAuth callback provides both `access_token` and legacy `token` for cross-client compatibility
    - Web: tokens are delivered via URL fragment (`#access_token=...`) to avoid referrer/log leakage
    - Native/local callback servers (Android deep link, Tauri localhost callback): tokens are delivered via query string (`?access_token=...`)
  - `redirect_url` is validated to prevent open redirects (only `/callback` under `cors_origins`, localhost callback servers, or `rmschatroom://callback`)
  - Refresh endpoint accepts JSON body `{"refresh_token": "..."}` and legacy query param `?refresh_token=...`
  - Refresh flow: best-effort SSO user lookup, falls back to stored metadata; new token stored BEFORE old deleted (crash safety)
  - Refresh tokens stored in DB (`auth_refresh_tokens`) with user metadata columns (username, nickname, email, permission_level, group_level)
  - Legacy migrations:
    - Web: migrate localStorage `token` -> `access_token`
    - Android: migrate DataStore `auth_token` -> `access_token`
  - Auto-refresh on 401 response via `doRefreshToken()` (internally deduped — concurrent 401s share one in-flight refresh)
  - `authFetch()` utility (`utils/authFetch.ts`): fetch wrapper with Bearer token + 401 retry, used by voice/music stores and FilePreview
  - axios interceptor handles 401 retry for axios-based requests (auth store, channel permissions, read positions)
  - Android: OkHttp `TokenAuthenticator` (`data/auth/TokenAuthenticator.kt`) handles 401 auto-refresh with synchronized lock; depends only on DataStore + Gson to avoid circular DI; skips refresh for logout/revoke endpoints
  - Android: Shared token keys in `data/auth/TokenKeys.kt` (single source of truth for DataStore keys)
  - Logout revokes refresh token on server (`POST /api/auth/logout`); `POST /api/auth/revoke` is a backward-compatible alias
  - Tauri callback server (axum) receives tokens and emits to frontend via Tauri event
- **Permissions**: Dual-dimension model based on `permission_level` AND/OR `group_level`
  - `PermRule{PermMinLevel, GroupMinLevel, LogicOperator}` — configurable AND/OR logic per resource
  - AND mode: `user.permission_level >= perm_min_level && user.group_level >= min_level`
  - OR mode: `user.permission_level >= perm_min_level || user.group_level >= min_level`
  - Backward compatible: defaults `perm_min_level=0, logic_operator='AND'` make `perm>=0` always true, reducing to single-dimension
  - `permission.CanAccess(user, PermRule)` for visibility, `permission.CanSpeak(user, PermRule)` for posting
  - Chat WS: messages checked with CanAccess + CanSpeak before insert; broadcasts filtered by CanAccess per recipient
  - HTTP BroadcastFunc: permission-filtered with graceful fallback to unfiltered on DB error
  - `permission.IsAdmin(user)` for admin checks (`permission_level >= 3`, unchanged)
  - DB columns: `servers.min_level`, `servers.perm_min_level`, `servers.logic_operator`
  - DB columns: `channel_groups.min_level`, `channel_groups.perm_min_level`, `channel_groups.logic_operator`
  - DB columns: `channels.min_level`, `channels.perm_min_level`, `channels.logic_operator` + `channels.speak_min_level`, `channels.speak_perm_min_level`, `channels.speak_logic_operator`
  - `UserInfo` struct: `GroupLevel` (from SSO `group.level`), `PermissionLevel` (from SSO `permission_level`)
  - Frontend: `DualPermissionSettings.vue` component with AND/OR toggle and dual level selectors
- **Real-time**: WebSocket for chat, WebRTC signaling for voice
- **Database**: MySQL (prod), managed via golang-migrate
- **Database Migrations**: golang-migrate for schema evolution
  - Migration files in `backend-go/internal/db/migrations/`
  - SQL queries generated via sqlc (`backend-go/sqlc.yaml`)
- **Noise Cancellation**: RNNoise and DTLN support via AudioWorklet (shared between web & desktop)
- **Channel Sorting**: Unified position system
  - `ChannelGroup.position` and `Channel.top_position` share a sequence for top-level ordering
  - `Channel.position` used for within-group ordering
  - API: `POST /servers/{id}/reorder` for top-level, `POST /servers/{id}/channel-groups/{gid}/reorder-channels` for group channels
  - Android: Edit mode with up/down arrows for reordering (admin only)
  - Web: Drag-and-drop reordering with vue-draggable-plus (admin only)
- **Voice Admin** (permission_level >= 3):
  - Mute other participants' microphones
  - Host mode (auto-mute all except host)
  - Generate single-use guest invite links
- **Voice join TTS**: When a user joins the voice channel, a short announcement ("xxx进入了语音") is spoken via the browser Web Speech API (no server TTS, no extra deps). Toggle in voice panel (Bell icon); preference stored in localStorage (`rms-voice-announce-enabled`).
- **Music Playback**: Client-side direct playback architecture
  - Backend acts as master controller, maintains playback state
  - Go backend (`internal/music/`): QQ Music + NetEase Cloud Music API clients (search, song URL, QR login)
  - QQ Music: SHA1-based request signing, credential persistence, 320k/128k quality fallback
  - QQ Music login: supports both QQ and WeChat QR code login (`login_type=qq|wx` query param)
  - QQ Music WeChat login: fetches QR from `open.weixin.qq.com`, polls `lp.open.weixin.qq.com`, authorizes via `music.login.LoginServer.Login` with `tmeLoginType: "1"`
  - NetEase: WEAPI (AES-CBC+RSA) and EAPI (AES-ECB) encryption, cookie-based auth
  - NetEase QR login: URL includes `chainId` param (`v1_{sDeviceId}_web_login_{timestamp}`) for anti-fraud
  - Progress timer: 1s tick goroutine per room, auto-advances to next song on finish
  - WebSocket broadcasts play/pause/resume/seek commands to all clients
  - Clients play audio URLs directly (Web: HTML5 Audio, Android: ExoPlayer)
- **File Upload** (Web & Android):
  - Supports images, videos, audio, documents, archives
  - Max file size: 10MB
  - Files stored in `uploads/{channel_id}/`
  - Attachments linked to messages via `attachment_ids`
- **Read Position Tracking** (Cross-device sync):
  - Server stores read positions in `read_positions` table (user_id, channel_id, last_read_message_id, has_mention)
  - Web/Desktop: Syncs via `/ws/global` WebSocket (`read_position_update` / `read_position_sync` messages)
  - Android: Syncs via `GlobalWebSocket` and `ReadPositionRepository`
  - REST API: `GET /read-positions` fetches all positions on login
  - Local storage used as cache, server state takes precedence
  - Debounced sync (500ms) to avoid excessive server calls
- **User Avatars**:
  - Generated client-side using `UserAvatar` component
  - Displays first letter of username with hash-based background color
  - Color palette: Discord blurple, green, yellow, pink, red, dark green, orange, purple, material pink, cyan
  - No server-side avatar storage or fetching

## Configuration

Backend config in `backend-go/config.json` (JSON-based with environment variable overrides):
- `database_url`: MySQL connection string
- `oauth_base_url`: OAuth 2.0 server base URL (e.g., `https://sso.rms.net.cn`)
- `oauth_authorize_endpoint`: Authorization endpoint path (default: `/oauth/authorize`)
- `oauth_token_endpoint`: Token exchange endpoint path (default: `/oauth/token`)
- `oauth_userinfo_endpoint`: User info endpoint path (default: `/oauth/userinfo`)
- `oauth_client_id`: OAuth client ID (required, obtain from RMSSSO)
- `oauth_client_secret`: OAuth client secret (required, obtain from RMSSSO)
- `oauth_redirect_uri`: OAuth callback URI (e.g., `http://localhost:8000/api/auth/callback`)
- `oauth_scope`: OAuth scopes to request (default: `openid profile`)
- `jwt_secret`: Secret for signing local JWT tokens (required)
- `access_token_expire_minutes`: Access token lifetime in minutes (default: 15)
- `refresh_token_expire_days`: Refresh token lifetime in days (default: 30)
- `cors_origins`: Allowed frontend origins
- `host`, `port`, `debug`: Server settings
- `frontend_dist_path`: Path to built frontend files

Frontend config via environment variables (in `.env` files or vite.config.ts):
- `VITE_API_BASE`: Backend API URL
- `VITE_WS_BASE`: Backend WebSocket URL

Android config in `android/app/build.gradle.kts`:
- `API_BASE_URL`: Backend API URL (per build type)
- `WS_BASE_URL`: Backend WebSocket URL (per build type)

## Git / Commit
- Use Conventional Commits (feat/fix/chore/refactor/docs/test)
- Before committing: `git status`, `git diff`, ensure no secrets/configs are added

## Deployment

### Deploy Script (`deploy.py`)

```bash
python deploy.py --release   # Release deploy, creates tag, triggers CI/CD
python deploy.py --hot-fix   # Hot-fix deploy (x.x.x-fix-x version), creates tag
python deploy.py --debug     # Debug deploy (no tag, no CI/CD)
python deploy.py --dry-run --debug  # Build without deploying
```

### Deployment Flow

1. Generate version files (frontend `version.ts`)
2. Build web frontend (`pnpm build:web`)
3. Cross-compile Go binary (`CGO_ENABLED=0 GOOS=linux GOARCH=amd64`)
4. (Optional) Create git tag + push
5. Package binary + frontend into tar.gz, upload to `POST /api/system/update?token=<deploy_token>`
6. Server extracts, replaces binary, auto-restarts via systemd

### Server Details

- **SSH**: `ssh root@rms.net.cn` (admin only, not needed for deploy)
- **Self-deploy API**: `POST /api/system/update?token=<deploy_token>`
- **Binary**: `/www/wwwroot/test-discord.rms.net.cn/rms-discord-server`
- **Frontend**: `/www/wwwroot/test-discord.rms.net.cn/packages/web/dist/`
- **Service**: `rms-discord` (systemd)

Server directory structure:
```
/www/wwwroot/test-discord.rms.net.cn/
├── rms-discord-server           # Go binary
├── config.json                  # Server config (not deployed)
├── packages/web/dist/           # Pre-built frontend
└── uploads/                     # User uploads
```

### Server Config

**Note**: Server config is maintained separately and NOT overwritten by deployment.

Production server config example (`/www/wwwroot/test-discord.rms.net.cn/backend-go/config.json`):
```json
{
  "database_url": "mysql://rmschat:password@localhost/rmschat?charset=utf8mb4",
  "frontend_dist_path": "/www/wwwroot/test-discord.rms.net.cn/packages/web/dist",
  "oauth_base_url": "https://sso.rms.net.cn",
  "oauth_authorize_endpoint": "/oauth/authorize",
  "oauth_token_endpoint": "/oauth/token",
  "oauth_userinfo_endpoint": "/oauth/userinfo",
  "oauth_client_id": "your-client-id",
  "oauth_client_secret": "your-client-secret",
  "oauth_redirect_uri": "https://test-discord.rms.net.cn/api/auth/callback",
  "oauth_scope": "openid profile",
  "jwt_secret": "your-jwt-secret",
  "host": "127.0.0.1",
  "port": 8000,
  "debug": false,
  "cors_origins": ["https://test-discord.rms.net.cn"]
}
```

Local development config example (`backend-go/config.json`):
```json
{
  "database_url": "sqlite+aiosqlite:///./discord.db",
  "frontend_dist_path": "../packages/web/dist",
  "oauth_base_url": "https://sso.rms.net.cn",
  "oauth_authorize_endpoint": "/oauth/authorize",
  "oauth_token_endpoint": "/oauth/token",
  "oauth_userinfo_endpoint": "/oauth/userinfo",
  "oauth_client_id": "your-client-id",
  "oauth_client_secret": "your-client-secret",
  "oauth_redirect_uri": "http://localhost:8000/api/auth/callback",
  "oauth_scope": "openid profile",
  "jwt_secret": "your-jwt-secret",
  "host": "0.0.0.0",
  "port": 8000,
  "debug": true,
  "cors_origins": ["http://localhost:5173", "http://127.0.0.1:5173"]
}
```

### Server SSH Access

Production server:
- **SSH**: `ssh root@rms.net.cn`
- **Service**: `rms-discord.service` (systemd)
- **Database**: MySQL (`rmschat` database)
- **Restart**: `systemctl restart rms-discord`

Database migrations (run on server):
```bash
ssh root@rms.net.cn
cd /www/wwwroot/test-discord.rms.net.cn/backend-go
# Use golang-migrate CLI for migrations
```

### Backend Utilities

Shared utility functions in `backend-go/internal/handler/` and `backend-go/internal/ws/`.

## CI/CD - GitHub Actions

### Automatic Builds on Tag Push

When `deploy.py` runs in `--release` or `--hot-fix` mode, it creates a git tag and pushes to GitHub, triggering automatic builds:

- **Android APK**: Built on Ubuntu with Gradle
- **Desktop Apps (Tauri)**: Built on Windows, macOS, and Linux in parallel
- **Server Deploy**: Go cross-compile + SSH upload + systemd restart
- **GitHub Release**: Automatically created with all build artifacts

### Workflow Files

- `.github/workflows/build-release.yml`: Main CI/CD workflow (Android, Tauri Desktop, Server deploy)
- `.github/workflows/pr-checks.yml`: PR checks (frontend build, Go build/vet, Android build)
- Triggered by tags matching `v*(*)`  (e.g., `v1.0.0(1)`, `v1.0.6-fix-1(9)`)

### Security Notes

- `android/app/release.keystore` is in `.gitignore` and should NEVER be committed
- Keystore is decoded from GitHub Secrets during build only
- `DEPLOY_TOKEN` and `DEPLOY_SERVER` secrets required for server deployment

## Troubleshooting

### Android ProGuard/R8 Issues

When adding new API data classes in `android/app/src/main/java/cn/net/rms/chatroom/data/api/`, they are automatically kept by ProGuard rules. The rule `-keep class cn.net.rms.chatroom.data.api.** { *; }` in `proguard-rules.pro` ensures all API classes are preserved.

**Symptom**: `ClassCastException` during Gson deserialization in release builds, but works fine in debug builds.

**Cause**: R8 obfuscates data class field names, breaking Gson's reflection-based deserialization.

**Solution**: Ensure the package is covered by keep rules in `proguard-rules.pro`:
```proguard
-keep class cn.net.rms.chatroom.data.api.** { *; }
-keepclassmembers class cn.net.rms.chatroom.data.api.** {
    <fields>;
    <init>(...);
}
```
