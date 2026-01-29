# 部署指南

> **[English](./deployment.md)**

本指南介绍如何在生产环境中部署 RMS Discord Clone。

## 部署方式选择

### 推荐：Standalone 二进制部署 ⭐

**适用场景**：生产环境、客户部署、快速部署

**优点**：
- ✅ 无需安装 Python、Node.js 等依赖
- ✅ 单文件部署，开箱即用
- ✅ 自动生成配置文件和数据库
- ✅ 跨平台支持（Windows、Linux、macOS）

**缺点**：
- ❌ 文件体积较大（~100MB 压缩包）
- ❌ 更新需要替换整个可执行文件

### 传统源码部署

**适用场景**：开发环境、需要频繁修改代码

**优点**：
- ✅ 文件体积小
- ✅ 可以直接修改代码
- ✅ 更新方便（git pull）

**缺点**：
- ❌ 需要安装 Python、Node.js 等依赖
- ❌ 配置复杂
- ❌ 环境依赖问题

---

## 方式一：Standalone 二进制部署（推荐）

### 1. 下载二进制包

从 [GitHub Releases](https://github.com/RMS-Server/rms-chatroom/releases) 下载对应平台的 standalone 包：

- **Windows**: `rms-discord-standalone-windows-x64.zip`
- **Linux**: `rms-discord-standalone-linux-x64.tar.gz`
- **macOS**: `rms-discord-standalone-macos-universal.tar.gz`

### 2. 解压并首次运行

```bash
# Linux/macOS
tar -xzf rms-discord-standalone-linux-x64.tar.gz
cd rms-discord-standalone-linux-x64
./rms-discord

# Windows
# 解压 zip 文件
# 双击 rms-discord.exe
```

首次运行会自动生成 `config.json` 配置文件。

### 3. 配置 OAuth 凭据

编辑 `config.json`：

```json
{
  "database_url": "sqlite+aiosqlite:///./discord.db",
  "oauth_base_url": "https://sso.example.com",
  "oauth_client_id": "你的 client_id",
  "oauth_client_secret": "你的 client_secret",
  "oauth_redirect_uri": "http://localhost:8000/api/auth/callback",
  "jwt_secret": "自动生成的随机密钥",
  "host": "0.0.0.0",
  "port": 8000,
  "debug": false,
  "cors_origins": ["http://localhost:8000"]
}
```

**必填项**：
- `oauth_client_id`: OAuth 客户端 ID
- `oauth_client_secret`: OAuth 客户端密钥
- `oauth_redirect_uri`: OAuth 回调地址

**可选项**：
- `host`: 服务器绑定地址（默认 0.0.0.0）
- `port`: 服务器端口（默认 8000）
- `database_url`: 数据库连接（默认 SQLite）

### 4. 重启应用

```bash
# Linux/macOS
./rms-discord

# Windows
# 双击 rms-discord.exe
```

### 5. 访问应用

打开浏览器访问 `http://localhost:8000`

### 6. 配置为系统服务（可选）

#### Linux (systemd)

创建 `/etc/systemd/system/rms-discord.service`：

```ini
[Unit]
Description=RMS Discord Standalone
After=network.target

[Service]
Type=simple
User=www-data
Group=www-data
WorkingDirectory=/opt/rms-discord
ExecStart=/opt/rms-discord/rms-discord
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
sudo systemctl status rms-discord
```

#### Windows (NSSM)

使用 [NSSM](https://nssm.cc/) 将应用注册为 Windows 服务：

```cmd
nssm install RMSDiscord "C:\path\to\rms-discord.exe"
nssm set RMSDiscord AppDirectory "C:\path\to"
nssm start RMSDiscord
```

### 7. 配置反向代理（可选）

如果需要通过域名访问，配置 Nginx：

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

更新 `config.json` 中的配置：

```json
{
  "oauth_redirect_uri": "https://your-domain.com/api/auth/callback",
  "cors_origins": ["https://your-domain.com"]
}
```

### 8. 数据备份

定期备份以下文件：

```bash
# 备份数据库
cp discord.db discord.db.backup

# 备份配置
cp config.json config.json.backup

# 备份上传文件
tar -czf uploads.tar.gz uploads/
```

### 9. 更新应用

1. 下载新版本的 standalone 包
2. 停止当前运行的应用
3. 备份 `config.json` 和 `discord.db`
4. 解压新版本，覆盖可执行文件
5. 恢复 `config.json` 和 `discord.db`
6. 重启应用

```bash
# Linux/macOS 示例
sudo systemctl stop rms-discord
cp config.json config.json.backup
cp discord.db discord.db.backup
tar -xzf rms-discord-standalone-linux-x64-new.tar.gz
cp config.json.backup rms-discord-standalone-linux-x64/config.json
cp discord.db.backup rms-discord-standalone-linux-x64/discord.db
sudo systemctl start rms-discord
```

---

## 方式二：传统源码部署

### 环境要求

- Python 3.11+
- Node.js 18+ 及 pnpm
- MySQL 8.0+（生产环境）或 SQLite（开发环境）
- 反向代理（推荐 nginx）

### 1. 克隆代码

```bash
git clone https://github.com/RMS-Server/rms-chatroom.git
cd rms-chatroom
```

### 2. 构建前端

```bash
pnpm install
pnpm build:web
```

构建产物位于 `packages/web/dist/`。

### 3. 配置后端

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
  "frontend_dist_path": "../packages/web/dist",
  "cors_origins": ["https://your-domain.com"]
}
```

### 4. 安装 Python 依赖

```bash
cd backend
python -m venv .venv
source .venv/bin/activate  # Linux/macOS
# .venv\Scripts\activate   # Windows
pip install -r requirements.txt
```

### 5. 配置数据库（MySQL）

```bash
mysql -u root -p
CREATE DATABASE rms_discord CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'rms_discord'@'localhost' IDENTIFIED BY 'your-password';
GRANT ALL PRIVILEGES ON rms_discord.* TO 'rms_discord'@'localhost';
FLUSH PRIVILEGES;
```

数据库迁移会在启动时通过 Alembic 自动执行。

### 6. 配置 Systemd 服务

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

### 7. 配置 Nginx

参考上面 Standalone 部署中的 Nginx 配置。

---

## 环境变量

所有配置项都可以通过环境变量覆盖（大写形式）：

```bash
export DATABASE_URL="mysql+aiomysql://..."
export OAUTH_CLIENT_ID="..."
export OAUTH_CLIENT_SECRET="..."
export JWT_SECRET="..."
export CORS_ORIGINS="https://domain1.com,https://domain2.com"
```

---

## 健康检查

```bash
# 检查服务状态
curl http://localhost:8000/health

# 检查详细状态
curl http://localhost:8000/health/detailed
```

---

## 故障排查

### Standalone 部署问题

#### 1. 配置文件未生成

**症状**：首次运行后没有生成 `config.json`

**解决**：
- 检查目录权限
- 查看控制台错误信息
- 手动创建 `config.json`（参考上面的配置示例）

#### 2. OAuth 配置错误

**症状**：无法登录，提示 OAuth 错误

**解决**：
- 检查 `oauth_client_id` 和 `oauth_client_secret` 是否正确
- 检查 `oauth_redirect_uri` 是否与 OAuth 服务器配置一致
- 确保 OAuth 服务器可访问

#### 3. 端口被占用

**症状**：启动失败，提示端口已被占用

**解决**：
- 修改 `config.json` 中的 `port` 配置
- 或停止占用端口的进程

#### 4. 数据库连接失败

**症状**：启动失败，提示数据库连接错误

**解决**：
- 检查 `database_url` 配置是否正确
- 如果使用 MySQL，确保数据库已创建且用户有权限
- 默认使用 SQLite，无需额外配置

### 源码部署问题

#### 1. 数据库连接问题

```bash
# 测试 MySQL 连接
mysql -u rms_discord -p -h localhost rms_discord -e "SELECT 1"
```

#### 2. 权限问题

```bash
# 确保正确的文件所有权
sudo chown -R www-data:www-data /path/to/rms-discord
```

#### 3. 查看日志

```bash
# Systemd 日志
sudo journalctl -u rms-discord -f

# 或手动运行时
python -m backend --verbose
```

---

## 性能优化

### 1. 使用 MySQL 替代 SQLite

SQLite 适合小规模部署，生产环境推荐使用 MySQL：

```json
{
  "database_url": "mysql+aiomysql://user:password@localhost/rms_discord"
}
```

### 2. 启用 Nginx 缓存

```nginx
# 缓存静态文件
location ~* \.(jpg|jpeg|png|gif|ico|css|js|woff|woff2)$ {
    expires 1y;
    add_header Cache-Control "public, immutable";
}
```

### 3. 配置 Uvicorn Workers

修改启动命令（仅源码部署）：

```bash
uvicorn backend.app:app --host 0.0.0.0 --port 8000 --workers 4
```

---

## 安全建议

1. **使用 HTTPS**：生产环境必须使用 HTTPS
2. **定期更新**：及时更新到最新版本
3. **备份数据**：定期备份数据库和配置文件
4. **限制访问**：使用防火墙限制不必要的端口访问
5. **强密码**：使用强密码保护数据库和 OAuth 凭据
6. **日志监控**：定期检查日志，发现异常及时处理

---

## 常见问题

### Q: Standalone 和源码部署有什么区别？

A: Standalone 是预编译的二进制文件，包含所有依赖，开箱即用。源码部署需要手动安装依赖，但更灵活。

### Q: 如何从源码部署迁移到 Standalone？

A:
1. 备份 `backend/config.json` 和 `backend/discord.db`
2. 下载 Standalone 包并解压
3. 将备份的文件复制到 Standalone 目录
4. 修改 `config.json` 中的路径配置（如 `frontend_dist_path`）
5. 启动 Standalone 应用

### Q: Standalone 支持哪些数据库？

A: 支持 SQLite（默认）和 MySQL。配置方式与源码部署相同。

### Q: 如何自定义端口？

A: 编辑 `config.json` 中的 `port` 配置，然后重启应用。

### Q: 如何查看应用版本？

A: 访问 `/health/detailed` 接口查看版本信息。
