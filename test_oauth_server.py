#!/usr/bin/env python3
"""
Simple OAuth2 Test Server
Provides basic OAuth2 authorization code flow for testing purposes.
"""

from fastapi import FastAPI, Request, Form, HTTPException
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import secrets
import uvicorn
from typing import Optional
from datetime import datetime, timedelta

app = FastAPI(title="OAuth2 Test Server")

# CORS configuration
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# In-memory storage
authorization_codes = {}
access_tokens = {}
refresh_tokens = {}

# Test users
TEST_USERS = {
    "admin": {"id": 1, "password": "admin123", "username": "Admin User", "permission_level": 5},
    "user": {"id": 2, "password": "user123", "username": "Normal User", "permission_level": 1},
    "test": {"id": 3, "password": "test123", "username": "Test User", "permission_level": 3},
}

# OAuth2 client configuration
CLIENT_ID = "test-client-id"
CLIENT_SECRET = "test-client-secret"


class TokenRequest(BaseModel):
    grant_type: str
    code: Optional[str] = None
    redirect_uri: Optional[str] = None
    client_id: Optional[str] = None
    client_secret: Optional[str] = None
    refresh_token: Optional[str] = None


@app.get("/", response_class=HTMLResponse)
async def root():
    """Root endpoint with server info"""
    return """
    <html>
        <head><title>OAuth2 Test Server</title></head>
        <body style="font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px;">
            <h1>OAuth2 Test Server</h1>
            <p>This is a simple OAuth2 test server for development purposes.</p>

            <h2>Configuration</h2>
            <ul>
                <li><strong>Client ID:</strong> test-client-id</li>
                <li><strong>Client Secret:</strong> test-client-secret</li>
                <li><strong>Authorization Endpoint:</strong> /oauth/authorize</li>
                <li><strong>Token Endpoint:</strong> /oauth/token</li>
                <li><strong>UserInfo Endpoint:</strong> /oauth/userinfo</li>
            </ul>

            <h2>Test Users</h2>
            <ul>
                <li><strong>admin</strong> / admin123 (permission_level: 5)</li>
                <li><strong>user</strong> / user123 (permission_level: 1)</li>
                <li><strong>test</strong> / test123 (permission_level: 3)</li>
            </ul>

            <h2>Endpoints</h2>
            <ul>
                <li>GET /oauth/authorize - Authorization endpoint</li>
                <li>POST /oauth/token - Token exchange endpoint</li>
                <li>GET /oauth/userinfo - User info endpoint</li>
            </ul>
        </body>
    </html>
    """


@app.get("/oauth/authorize", response_class=HTMLResponse)
async def authorize(
    response_type: str,
    client_id: str,
    redirect_uri: str,
    scope: Optional[str] = "openid profile",
    state: Optional[str] = None
):
    """OAuth2 authorization endpoint"""
    if client_id != CLIENT_ID:
        raise HTTPException(status_code=400, detail="Invalid client_id")

    if response_type != "code":
        raise HTTPException(status_code=400, detail="Unsupported response_type")

    # Return login page
    return f"""
    <html>
        <head>
            <title>Login - OAuth2 Test Server</title>
            <style>
                body {{
                    font-family: Arial, sans-serif;
                    max-width: 400px;
                    margin: 100px auto;
                    padding: 20px;
                    background: #f5f5f5;
                }}
                .login-box {{
                    background: white;
                    padding: 30px;
                    border-radius: 8px;
                    box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                }}
                h2 {{
                    margin-top: 0;
                    color: #333;
                }}
                input {{
                    width: 100%;
                    padding: 10px;
                    margin: 10px 0;
                    border: 1px solid #ddd;
                    border-radius: 4px;
                    box-sizing: border-box;
                }}
                button {{
                    width: 100%;
                    padding: 12px;
                    background: #5865F2;
                    color: white;
                    border: none;
                    border-radius: 4px;
                    cursor: pointer;
                    font-size: 16px;
                }}
                button:hover {{
                    background: #4752C4;
                }}
                .info {{
                    margin-top: 20px;
                    padding: 15px;
                    background: #f0f0f0;
                    border-radius: 4px;
                    font-size: 12px;
                }}
            </style>
        </head>
        <body>
            <div class="login-box">
                <h2>Login to OAuth2 Test Server</h2>
                <form method="post" action="/oauth/login">
                    <input type="hidden" name="redirect_uri" value="{redirect_uri}">
                    <input type="hidden" name="state" value="{state or ''}">
                    <input type="hidden" name="scope" value="{scope}">

                    <input type="text" name="username" placeholder="Username" required autofocus>
                    <input type="password" name="password" placeholder="Password" required>
                    <button type="submit">Login</button>
                </form>

                <div class="info">
                    <strong>Test Accounts:</strong><br>
                    admin / admin123 (Admin)<br>
                    user / user123 (User)<br>
                    test / test123 (Tester)
                </div>
            </div>
        </body>
    </html>
    """


@app.post("/oauth/login")
async def login(
    username: str = Form(...),
    password: str = Form(...),
    redirect_uri: str = Form(...),
    state: Optional[str] = Form(None),
    scope: Optional[str] = Form("openid profile")
):
    """Handle login and generate authorization code"""
    # Validate credentials
    if username not in TEST_USERS or TEST_USERS[username]["password"] != password:
        raise HTTPException(status_code=401, detail="Invalid credentials")

    # Generate authorization code
    code = secrets.token_urlsafe(32)
    authorization_codes[code] = {
        "username": username,
        "redirect_uri": redirect_uri,
        "scope": scope,
        "expires_at": datetime.now() + timedelta(minutes=10)
    }

    # Build redirect URL
    redirect_url = f"{redirect_uri}?code={code}"
    if state:
        redirect_url += f"&state={state}"

    return RedirectResponse(url=redirect_url, status_code=302)


@app.post("/oauth/token")
async def token(request: Request):
    """OAuth2 token endpoint"""
    # Support both JSON and form data
    content_type = request.headers.get("content-type", "")

    if "application/json" in content_type:
        data = await request.json()
    else:
        form_data = await request.form()
        data = dict(form_data)

    grant_type = data.get("grant_type")

    if grant_type == "authorization_code":
        return await exchange_code(data)
    elif grant_type == "refresh_token":
        return await refresh_access_token(data)
    else:
        raise HTTPException(status_code=400, detail="Unsupported grant_type")


async def exchange_code(data: dict):
    """Exchange authorization code for tokens"""
    code = data.get("code")
    redirect_uri = data.get("redirect_uri")
    client_id = data.get("client_id")
    client_secret = data.get("client_secret")

    # Validate client credentials
    if client_id != CLIENT_ID or client_secret != CLIENT_SECRET:
        raise HTTPException(status_code=401, detail="Invalid client credentials")

    # Validate authorization code
    if code not in authorization_codes:
        raise HTTPException(status_code=400, detail="Invalid authorization code")

    code_data = authorization_codes[code]

    # Check expiration
    if datetime.now() > code_data["expires_at"]:
        del authorization_codes[code]
        raise HTTPException(status_code=400, detail="Authorization code expired")

    # Validate redirect_uri
    if redirect_uri != code_data["redirect_uri"]:
        raise HTTPException(status_code=400, detail="Invalid redirect_uri")

    # Generate tokens
    access_token = secrets.token_urlsafe(32)
    refresh_token = secrets.token_urlsafe(32)

    username = code_data["username"]
    user_data = TEST_USERS[username]

    # Store tokens
    access_tokens[access_token] = {
        "username": username,
        "scope": code_data["scope"],
        "expires_at": datetime.now() + timedelta(hours=1)
    }

    refresh_tokens[refresh_token] = {
        "username": username,
        "scope": code_data["scope"],
        "expires_at": datetime.now() + timedelta(days=30)
    }

    # Delete used authorization code
    del authorization_codes[code]

    return JSONResponse({
        "access_token": access_token,
        "token_type": "Bearer",
        "expires_in": 3600,
        "refresh_token": refresh_token,
        "scope": code_data["scope"]
    })


async def refresh_access_token(data: dict):
    """Refresh access token using refresh token"""
    refresh_token_value = data.get("refresh_token")
    client_id = data.get("client_id")
    client_secret = data.get("client_secret")

    # Validate client credentials
    if client_id != CLIENT_ID or client_secret != CLIENT_SECRET:
        raise HTTPException(status_code=401, detail="Invalid client credentials")

    # Validate refresh token
    if refresh_token_value not in refresh_tokens:
        raise HTTPException(status_code=400, detail="Invalid refresh token")

    token_data = refresh_tokens[refresh_token_value]

    # Check expiration
    if datetime.now() > token_data["expires_at"]:
        del refresh_tokens[refresh_token_value]
        raise HTTPException(status_code=400, detail="Refresh token expired")

    # Generate new access token
    access_token = secrets.token_urlsafe(32)

    access_tokens[access_token] = {
        "username": token_data["username"],
        "scope": token_data["scope"],
        "expires_at": datetime.now() + timedelta(hours=1)
    }

    return JSONResponse({
        "access_token": access_token,
        "token_type": "Bearer",
        "expires_in": 3600,
        "scope": token_data["scope"]
    })


@app.get("/oauth/userinfo")
async def userinfo(request: Request):
    """OAuth2 userinfo endpoint"""
    # Extract access token from Authorization header
    auth_header = request.headers.get("Authorization")
    if not auth_header or not auth_header.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing or invalid Authorization header")

    access_token = auth_header.replace("Bearer ", "")

    # Validate access token
    if access_token not in access_tokens:
        raise HTTPException(status_code=401, detail="Invalid access token")

    token_data = access_tokens[access_token]

    # Check expiration
    if datetime.now() > token_data["expires_at"]:
        del access_tokens[access_token]
        raise HTTPException(status_code=401, detail="Access token expired")

    # Return user info
    username = token_data["username"]
    user_data = TEST_USERS[username]

    return JSONResponse({
        "sub": str(user_data["id"]),
        "id": user_data["id"],
        "username": user_data["username"],
        "permission_level": user_data["permission_level"],
        "email": f"{username}@test.local"
    })


@app.get("/health")
async def health():
    """Health check endpoint"""
    return {"status": "ok", "timestamp": datetime.now().isoformat()}


if __name__ == "__main__":
    print("=" * 60)
    print("OAuth2 Test Server Starting...")
    print("=" * 60)
    print(f"Server URL: http://localhost:9000")
    print(f"Client ID: {CLIENT_ID}")
    print(f"Client Secret: {CLIENT_SECRET}")
    print()
    print("Test Users:")
    for username, data in TEST_USERS.items():
        print(f"  - {username} / {data['password']} (level: {data['permission_level']})")
    print("=" * 60)

    uvicorn.run(app, host="0.0.0.0", port=9000, log_level="info")
