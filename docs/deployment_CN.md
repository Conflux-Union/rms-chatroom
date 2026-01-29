# 部署指南

> **[English](./deployment.md)**

本指南介绍如何在生产环境中部署 RMS Discord Clone。

## 环境要求

- Python 3.11+
- Node.js 18+ 及 pnpm
- MySQL 8.0+（生产环境）或 SQLite（开发环境）
- 反向代理（推荐 nginx）

## 快速开始（开发环境）

```bash
# 1. 安装前端依赖
pnpm install

# 2. 配置后端环境
cd backend
python -m venv .venv
source .venv/bin/activate  # Linux/macOS
# .venv\Scripts\activate   # Windows
pip install -r requirements.txt

# 3. 配置后端
cp config.json.example config.json
# 编辑 config.json 填写配置

# 4. 启动后端
cd ..
source backend/.venv/bin/activate
python -m backend

# 5. 启动前端（新终端）
pnpm dev:web
```

## 生产环境部署

### 1. 构建前端

```bash
pnpm install
pnpm build:web
```

构建产物位于 `packages/web/dist/`。

### 2. 配置后端

创建 `backend/config.json`：

```json
{
  "database_url": "mysql+aiomysql://user:password@localhost/rms_discord",
  "oauth_base_url": "https://sso.example.com",
  "oauth_authorize_endpoint": "/oauth/authorize",
  "oauth_token_endpoint": "/oauth/token",
  "oauth_userinfo_endpoint": "/oauth/userinfo",
  "oauth_client_id": "你的 client_id",
  "oauth_client_secret": "你的 client_secret",
  "oauth_redirect_uri": "https://your-domain.com/api/auth/callback",
  "oauth_scope": "openid profile",
  "jwt_secret": "生成一个安全的随机字符串",
  "host": "127.0.0.1",
  "port": 8000,
  "debug": false,
  "frontend_dist_path": "/path/to/packages/web/dist",
  "cors_origins": ["https://your-domain.com"]
}
```

### 3. 配置数据库

MySQL 配置：

```bash
mysql -u root -p
CREATE DATABASE rms_discord CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'rms_discord'@'localhost' IDENTIFIED BY 'your-password';
GRANT ALL PRIVILEGES ON rms_discord.* TO 'rms_discord'@'localhost';
FLUSH PRIVILEGES;
```

数据库迁移会在启动时通过 Alembic 自动执行。

### 4. 配置 Systemd 服务

创建 `/etc/systemd/system/rms-discord.service`：

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

启用并启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable rms-discord
sudo systemctl start rms-discord
```

### 5. 配置 Nginx

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

    # API 和 WebSocket 代理
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

    # 静态文件（前端）
    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
    }
}
```

## 自动化部署

使用部署脚本简化发布流程：

```bash
# 正式发布（创建 git tag，触发 CI/CD）
python deploy.py --release

# 热修复发布
python deploy.py --hot-fix

# 调试发布（不创建 tag）
python deploy.py --debug

# 测试打包（不上传）
python deploy.py --dry-run --debug
```

## 环境变量

所有配置项都可以通过环境变量覆盖（大写形式）：

```bash
export DATABASE_URL="mysql+aiomysql://..."
export OAUTH_CLIENT_ID="..."
export OAUTH_CLIENT_SECRET="..."
export JWT_SECRET="..."
export CORS_ORIGINS="https://domain1.com,https://domain2.com"
```

## 健康检查

```bash
# 检查后端是否运行
curl http://localhost:8000/api/auth/me
# 预期：401 Unauthorized（无 token）或用户信息（有 token）
```

## 故障排查

### 数据库连接问题

```bash
# 测试 MySQL 连接
mysql -u rms_discord -p -h localhost rms_discord -e "SELECT 1"
```

### 权限问题

```bash
# 确保正确的文件所有权
sudo chown -R www-data:www-data /path/to/rms-discord
```

### 查看日志

```bash
# Systemd 日志
sudo journalctl -u rms-discord -f

# 或手动运行时
python -m backend --verbose
```
