# Deployment Guide

> **[中文版](./deployment_CN.md)**

This guide covers deploying RMS Discord Clone in production environments.

## Prerequisites

- Python 3.11+
- Node.js 18+ with pnpm
- MySQL 8.0+ (production) or SQLite (development)
- Reverse proxy (nginx recommended)

## Quick Start (Development)

```bash
# 1. Install frontend dependencies
pnpm install

# 2. Setup backend
cd backend
python -m venv .venv
source .venv/bin/activate  # Linux/macOS
# .venv\Scripts\activate   # Windows
pip install -r requirements.txt

# 3. Configure backend
cp config.json.example config.json
# Edit config.json with your settings

# 4. Start backend
cd ..
source backend/.venv/bin/activate
python -m backend

# 5. Start frontend (new terminal)
pnpm dev:web
```

## Production Deployment

### 1. Build Frontend

```bash
pnpm install
pnpm build:web
```

Output will be in `packages/web/dist/`.

### 2. Configure Backend

Create `backend/config.json`:

```json
{
  "database_url": "mysql+aiomysql://user:password@localhost/rms_discord",
  "oauth_base_url": "https://sso.example.com",
  "oauth_authorize_endpoint": "/oauth/authorize",
  "oauth_token_endpoint": "/oauth/token",
  "oauth_userinfo_endpoint": "/oauth/userinfo",
  "oauth_client_id": "your-client-id",
  "oauth_client_secret": "your-client-secret",
  "oauth_redirect_uri": "https://your-domain.com/api/auth/callback",
  "oauth_scope": "openid profile",
  "jwt_secret": "generate-a-secure-random-string",
  "host": "127.0.0.1",
  "port": 8000,
  "debug": false,
  "frontend_dist_path": "/path/to/packages/web/dist",
  "cors_origins": ["https://your-domain.com"]
}
```

### 3. Setup Database

For MySQL:

```bash
mysql -u root -p
CREATE DATABASE rms_discord CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'rms_discord'@'localhost' IDENTIFIED BY 'your-password';
GRANT ALL PRIVILEGES ON rms_discord.* TO 'rms_discord'@'localhost';
FLUSH PRIVILEGES;
```

Database migrations run automatically on startup via Alembic.

### 4. Setup Systemd Service

Create `/etc/systemd/system/rms-discord.service`:

```ini
[Unit]
Description=RMS Discord Clone
After=network.target mysql.service

[Service]
Type=simple
User=www-data
Group=www-data
WorkingDirectory=/path/to/rms-discord
Environment="PATH=/path/to/rms-discord/backend/.venv/bin"
ExecStart=/path/to/rms-discord/backend/.venv/bin/python -m backend
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable rms-discord
sudo systemctl start rms-discord
```

### 5. Configure Nginx

```nginx
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    # API and WebSocket proxy
    location /api/ {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /ws/ {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400;
    }

    # Static files (frontend)
    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
    }
}
```

## Automated Deployment

Use the deployment script for streamlined releases:

```bash
# Release deployment (creates git tag, triggers CI/CD)
python deploy.py --release

# Hot-fix deployment
python deploy.py --hot-fix

# Debug deployment (no tag)
python deploy.py --debug

# Dry run (test packaging)
python deploy.py --dry-run --debug
```

## Environment Variables

All config options can be overridden via environment variables (uppercase):

```bash
export DATABASE_URL="mysql+aiomysql://..."
export OAUTH_CLIENT_ID="..."
export OAUTH_CLIENT_SECRET="..."
export JWT_SECRET="..."
export CORS_ORIGINS="https://domain1.com,https://domain2.com"
```

## Health Check

```bash
# Check if backend is running
curl http://localhost:8000/api/auth/me
# Expected: 401 Unauthorized (no token) or user info (with token)
```

## Troubleshooting

### Database Connection Issues

```bash
# Test MySQL connection
mysql -u rms_discord -p -h localhost rms_discord -e "SELECT 1"
```

### Permission Issues

```bash
# Ensure correct ownership
sudo chown -R www-data:www-data /path/to/rms-discord
```

### View Logs

```bash
# Systemd logs
sudo journalctl -u rms-discord -f

# Or if running manually
python -m backend --verbose
```
