# RMS Chat

[中文](./README_CN.md) | **English**

A modern communication platform with real-time chat, voice calls, and music sharing. Built with Vue 3, Go (Echo), and Kotlin.

## Features

- **OAuth 2.0 Authentication** - RMSSSO integration with local JWT + refresh token rotation
- **Real-time Chat** - WebSocket-powered instant messaging with reconnection and heartbeat
- **Voice Calls** - WebRTC-based voice communication via LiveKit
- **Music Sharing** - QQ Music + NetEase Cloud Music with synchronized playback
- **Multi-platform** - Web, Desktop (Electron), and Android
- **Dual-dimension Permissions** - `permission_level` AND/OR `group_level` per resource
- **Noise Cancellation** - RNNoise and DTLN via AudioWorklet
- **Voice Admin Controls** - Mute participants, host mode, guest invites

## Architecture

```
rms-discord/
├── packages/                # pnpm monorepo
│   ├── shared/             # Shared components, stores, composables
│   ├── web/                # Web entry point
│   └── electron-renderer/  # Electron renderer entry point
├── electron/               # Electron main process
├── backend-go/             # Go backend (Echo framework)
├── android/                # Kotlin + Jetpack Compose
└── pnpm-workspace.yaml
```

### Technology Stack

**Backend (Go):**
- Echo framework
- MySQL + golang-migrate + sqlc
- gorilla/websocket for real-time messaging
- LiveKit for voice infrastructure
- JSON config with environment variable overrides

**Frontend (pnpm monorepo):**
- Vue 3 (Composition API) + TypeScript
- Pinia (state management)
- Vite (build tool)
- LiveKit Client SDK

**Android:**
- Kotlin + Jetpack Compose + Material 3
- MVVM + Clean Architecture
- Hilt (dependency injection)
- LiveKit Android SDK

## Quick Start

### Prerequisites

- Go 1.24+
- Node.js 18+ and pnpm
- MySQL
- Android Studio + JDK 17+ (for Android)

### Backend Setup

```bash
cd backend-go
cp config.example.json config.json  # Edit with your settings
go run ./cmd/server/main.go
```

Backend runs on `http://localhost:8000`

### Frontend Setup

```bash
pnpm install
pnpm dev:web
```

Frontend runs on `http://localhost:5173`

### Android Setup

```bash
cd android
./gradlew assembleDebug
./gradlew installDebug
```

## Configuration

### Backend (`backend-go/config.json`)

```json
{
  "database_url": "mysql://user:password@localhost/rmschat?charset=utf8mb4",
  "oauth_base_url": "https://sso.rms.net.cn",
  "oauth_client_id": "your-client-id",
  "oauth_client_secret": "your-client-secret",
  "oauth_redirect_uri": "http://localhost:8000/api/auth/callback",
  "jwt_secret": "your-jwt-secret",
  "cors_origins": ["http://localhost:5173"],
  "host": "0.0.0.0",
  "port": 8000
}
```

### Frontend (`.env`)

```env
VITE_API_BASE=http://localhost:8000
VITE_WS_BASE=ws://localhost:8000
```

### Android (`android/app/build.gradle.kts`)

Build variants automatically configure API endpoints:
- **Debug**: Points to localhost/development server
- **Release**: Points to production server

## Authentication

OAuth 2.0 flow with local JWT tokens:

1. **Login** - Redirects to RMSSSO with JWT-encoded CSRF state
2. **Callback** - Validates state, exchanges code for SSO token, fetches user info, issues local JWT (15min) + refresh token (30 days)
3. **Token delivery** - Web: URL fragment (`#access_token=...`); Native: query string (`?access_token=...`)
4. **Refresh** - `POST /api/auth/refresh` with token rotation (new stored before old deleted for crash safety)
5. **Auto-refresh** - Frontend/Android intercept 401 responses and refresh transparently

Redirect URL validation prevents open redirects: only `/callback` under `cors_origins`, localhost callback servers, or `rmschatroom://callback` are accepted.

## Permissions

Dual-dimension model applied to servers, channel groups, and channels:

| Mode | Logic |
|------|-------|
| AND  | `permission_level >= min` **AND** `group_level >= min` |
| OR   | `permission_level >= min` **OR** `group_level >= min` |

Backward compatible: defaults (`perm_min_level=0`, `logic_operator=AND`) reduce to single-dimension group_level checks.

## Building for Production

```bash
pnpm build:web                # Web frontend
pnpm build:electron           # Electron renderer
cd backend-go && go build ./cmd/server/main.go  # Go binary
cd android && ./gradlew assembleRelease          # Android APK
```

## Deployment

```bash
python deploy.py --release   # Tag + CI/CD (Android, Electron, Server)
python deploy.py --hot-fix   # Hot-fix version
python deploy.py --debug     # Debug deploy (no tag)
```

GitHub Actions builds Android APK, Electron apps (Windows/macOS/Linux), deploys server binary, and creates GitHub Release.

## Platform Support

| Platform | Status | Notes |
|----------|--------|-------|
| Web | Production | Chrome, Firefox, Safari |
| Desktop | Production | Windows, macOS, Linux (Electron) |
| Android | Production | Android 8.0+ |
| iOS | Planned | - |

## License

Proprietary software. All rights reserved.

---

Built by RMS Team
