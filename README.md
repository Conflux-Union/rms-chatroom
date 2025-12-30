# RMS Chat

[中文](./README_CN.md) | **English**

A modern communication platform with real-time chat, voice calls, and music sharing capabilities. Built with Vue3, FastAPI, and Kotlin.

## ✨ Features

- 🔐 **SSO Authentication** - Integrated with RMSSSO for secure authentication
- 💬 **Real-time Chat** - WebSocket-powered instant messaging
- 🎙️ **Voice Calls** - WebRTC-based voice communication using LiveKit
- 🎵 **Music Sharing** - QQ Music integration with queue management
- 📱 **Multi-platform** - Web, Desktop (Electron), and Android apps
- 🎨 **Modern UI** - Beautiful dark theme with Material 3 design
- 👥 **Server & Channels** - Organize conversations with servers and channels
- 🔊 **Voice Admin Controls** - Mute participants, host mode, guest invites

## 🏗️ Architecture

```
rms-discord/
├── backend/              # Python FastAPI backend
├── frontend/             # Vue3 + TypeScript web app
├── electron/             # Electron desktop wrapper
└── android/              # Kotlin + Jetpack Compose mobile app
```

### Technology Stack

**Backend:**
- FastAPI (Python 3.11+)
- SQLAlchemy (async ORM)
- WebSocket for real-time messaging
- LiveKit for voice infrastructure

**Frontend:**
- Vue 3 (Composition API)
- TypeScript
- Pinia (state management)
- Vite (build tool)
- LiveKit Client SDK

**Android:**
- Kotlin
- Jetpack Compose + Material 3
- MVVM + Clean Architecture
- Hilt (dependency injection)
- LiveKit Android SDK

## 🚀 Quick Start

### Prerequisites

- Python 3.11+
- Node.js 18+
- Android Studio (for Android development)
- JDK 17+ (for Android builds)

### Backend Setup

```bash
cd backend
python -m venv .venv
source .venv/bin/activate  # On Windows: .venv\Scripts\activate
pip install -r requirements.txt
python -m backend
```

Backend runs on `http://localhost:8000`

### Frontend Setup

```bash
cd frontend
npm install
npm run dev
```

Frontend runs on `http://localhost:5173`

### Android Setup

```bash
cd android
./gradlew assembleDebug
./gradlew installDebug  # Install on connected device
```

## ⚙️ Configuration

### Backend (`backend/config.json`)

```json
{
  "sso_base_url": "https://your-sso-server.com",
  "database_url": "sqlite+aiosqlite:///./chatroom.db",
  "cors_origins": ["http://localhost:5173"]
}
```

### Frontend (`frontend/.env`)

```env
VITE_API_BASE=http://localhost:8000
VITE_WS_BASE=ws://localhost:8000
```

### Android (`android/app/build.gradle.kts`)

Build variants automatically configure API endpoints:
- **Debug**: Points to localhost/development server
- **Release**: Points to production server

## 📦 Building for Production

### Web Application

```bash
cd frontend
npm run build
# Output: frontend/dist/
```

### Desktop Application (Electron)

```bash
cd electron
npm install
npm run build
# Output: electron/dist/
```

### Android APK

```bash
cd android
./gradlew assembleRelease
# Output: android/app/build/outputs/apk/release/
```

## 🔄 CI/CD

Automatic builds are triggered on git tag push:

```bash
python deploy.py --release  # Creates tag and triggers GitHub Actions
```

GitHub Actions will build:
- Android APK (signed)
- Electron apps (Windows, macOS, Linux)
- GitHub Release with all artifacts

### Setup Requirements

Configure GitHub Secrets:
- `ANDROID_KEYSTORE_BASE64`: Base64-encoded keystore
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`: Signing credentials

See `.github/SETUP.md` for detailed instructions.

## 🎯 Key Features Explained

### Voice Calls

- WebRTC-based using LiveKit infrastructure
- Admin controls: mute participants, host mode
- Guest invite links for non-authenticated users
- Foreground service on Android for background calls

### Music Sharing

- QQ Music integration
- Shared playback queue
- Search and add songs
- Synchronized playback across users

### Authentication

- RMSSSO integration (no local user storage)
- Permission levels (>=3 for admin features)
- Deep link handling for mobile SSO flow

## 📱 Platform Support

| Platform | Status | Notes |
|----------|--------|-------|
| Web | ✅ Production | Chrome, Firefox, Safari |
| Desktop | ✅ Production | Windows, macOS, Linux |
| Android | ✅ Production | Android 8.0+ |
| iOS | ❌ Planned | Future development |

## 🛠️ Development

### Project Structure

```
backend/
├── core/            # Configuration, database
├── models/          # SQLAlchemy models
├── routers/         # API endpoints
├── services/        # Business logic
└── websocket/       # WebSocket handlers

frontend/src/
├── components/      # Vue components
├── composables/     # Composition API hooks
├── stores/          # Pinia stores
├── types/           # TypeScript definitions
└── views/           # Page components

android/app/src/main/java/cn/net/rms/chatroom/
├── data/            # Repository, API, WebSocket
├── ui/              # Compose screens
└── service/         # Background services
```

### Coding Standards

- **Python**: PEP8, type hints, async/await
- **TypeScript**: Strict mode, Composition API
- **Kotlin**: Official conventions, Jetpack Compose
- **Commits**: Conventional Commits (feat/fix/chore)

## 📄 License

This project is proprietary software. All rights reserved.

## 🤝 Contributing

This is a private project. Contact the maintainers for contribution guidelines.

## 📞 Support

For issues and questions, please contact the development team.

---

Built with ❤️ by RMS Team
