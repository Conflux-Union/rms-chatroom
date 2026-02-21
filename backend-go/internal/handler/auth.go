package handler

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	"github.com/RMS-Server/rms-discord-go/internal/middleware"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

// AuthHandler handles authentication endpoints.
type AuthHandler struct {
	db     *sql.DB
	sso    *sso.Client
	config *config.Config
	http   *http.Client
}

func NewAuthHandler(db *sql.DB, sso *sso.Client, cfg *config.Config) *AuthHandler {
	return &AuthHandler{
		db:     db,
		sso:    sso,
		config: cfg,
		http:   &http.Client{Timeout: 10 * time.Second},
	}
}

// Login redirects to the OAuth authorize URL.
// GET /api/auth/login
func (h *AuthHandler) Login(c echo.Context) error {
	redirectURL := c.QueryParam("redirect_url")
	if redirectURL == "" {
		redirectURL = "http://localhost:5173/callback"
	}

	baseURL := h.config.OAuthBaseURL
	if baseURL == "" {
		baseURL = h.config.SSOBaseURL
	}
	endpoint := h.config.OAuthAuthorizeEndpoint
	if endpoint == "" {
		endpoint = "/oauth/authorize"
	}

	params := url.Values{}
	params.Set("client_id", h.config.OAuthClientID)
	params.Set("redirect_uri", h.config.OAuthRedirectURI)
	params.Set("response_type", "code")
	scope := h.config.OAuthScope
	if scope == "" {
		scope = "openid profile"
	}
	params.Set("scope", scope)
	params.Set("state", redirectURL)

	loginURL := baseURL + endpoint + "?" + params.Encode()
	return c.Redirect(http.StatusFound, loginURL)
}

// Callback handles the OAuth authorization code exchange.
// GET /api/auth/callback
func (h *AuthHandler) Callback(c echo.Context) error {
	code := c.QueryParam("code")
	redirectURL := c.QueryParam("redirect_url")
	if redirectURL == "" {
		redirectURL = c.QueryParam("state")
	}
	if code == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "missing authorization code"})
	}

	// Exchange code for SSO access token
	baseURL := h.config.OAuthBaseURL
	if baseURL == "" {
		baseURL = h.config.SSOBaseURL
	}
	tokenEndpoint := h.config.OAuthTokenEndpoint
	if tokenEndpoint == "" {
		tokenEndpoint = "/oauth/token"
	}

	form := url.Values{}
	form.Set("grant_type", "authorization_code")
	form.Set("code", code)
	form.Set("redirect_uri", h.config.OAuthRedirectURI)
	form.Set("client_id", h.config.OAuthClientID)
	form.Set("client_secret", h.config.OAuthClientSecret)

	tokenResp, err := h.http.PostForm(baseURL+tokenEndpoint, form)
	if err != nil {
		return c.JSON(http.StatusBadGateway, map[string]string{"error": "failed to contact SSO token endpoint"})
	}
	defer tokenResp.Body.Close()

	body, _ := io.ReadAll(tokenResp.Body)
	if tokenResp.StatusCode != http.StatusOK {
		return c.JSON(http.StatusBadGateway, map[string]string{"error": "SSO token exchange failed"})
	}

	var tokenData struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.Unmarshal(body, &tokenData); err != nil || tokenData.AccessToken == "" {
		return c.JSON(http.StatusBadGateway, map[string]string{"error": "invalid SSO token response"})
	}

	// Fetch user info from SSO
	userinfoEndpoint := h.config.OAuthUserinfoEndpoint
	if userinfoEndpoint == "" {
		userinfoEndpoint = "/oauth/userinfo"
	}
	req, _ := http.NewRequest(http.MethodGet, baseURL+userinfoEndpoint, nil)
	req.Header.Set("Authorization", "Bearer "+tokenData.AccessToken)

	infoResp, err := h.http.Do(req)
	if err != nil {
		return c.JSON(http.StatusBadGateway, map[string]string{"error": "failed to fetch user info from SSO"})
	}
	defer infoResp.Body.Close()

	infoBody, _ := io.ReadAll(infoResp.Body)
	if infoResp.StatusCode != http.StatusOK {
		return c.JSON(http.StatusBadGateway, map[string]string{"error": "SSO userinfo request failed"})
	}

	var userInfo struct {
		ID              int    `json:"id"`
		Username        string `json:"username"`
		Nickname        string `json:"nickname"`
		Email           string `json:"email"`
		PermissionLevel int    `json:"permission_level"`
	}
	if err := json.Unmarshal(infoBody, &userInfo); err != nil || userInfo.ID == 0 {
		return c.JSON(http.StatusBadGateway, map[string]string{"error": "invalid SSO userinfo response"})
	}

	// Generate local JWT access token (7 days)
	now := time.Now().UTC()
	claims := jwt.MapClaims{
		"id":               userInfo.ID,
		"username":         userInfo.Username,
		"nickname":         userInfo.Nickname,
		"email":            userInfo.Email,
		"permission_level": userInfo.PermissionLevel,
		"iat":              now.Unix(),
		"exp":              now.Add(7 * 24 * time.Hour).Unix(),
	}
	localToken, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(h.config.JWTSecret))
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to generate access token"})
	}

	// Generate refresh token (random 64-char hex)
	refreshBytes := make([]byte, 32)
	if _, err := rand.Read(refreshBytes); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to generate refresh token"})
	}
	refreshToken := hex.EncodeToString(refreshBytes)

	// Store SHA-256 hash of refresh token
	hash := sha256.Sum256([]byte(refreshToken))
	tokenHash := hex.EncodeToString(hash[:])
	expiresAt := now.Add(30 * 24 * time.Hour)

	_, err = h.db.Exec(
		"INSERT INTO auth_refresh_tokens (user_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)",
		userInfo.ID, tokenHash, now, expiresAt,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to store refresh token"})
	}

	// Validate and redirect
	if redirectURL == "" {
		redirectURL = "http://localhost:5173/callback"
	}

	if h.isWebRedirect(redirectURL) {
		// Web: use URL fragment to avoid referrer/log leakage
		sep := "#"
		dest := fmt.Sprintf("%s%saccess_token=%s&refresh_token=%s&token=%s",
			redirectURL, sep,
			url.QueryEscape(localToken),
			url.QueryEscape(refreshToken),
			url.QueryEscape(localToken),
		)
		return c.Redirect(http.StatusFound, dest)
	}

	// Native/localhost: use query string
	sep := "?"
	if strings.Contains(redirectURL, "?") {
		sep = "&"
	}
	dest := fmt.Sprintf("%s%saccess_token=%s&refresh_token=%s&token=%s",
		redirectURL, sep,
		url.QueryEscape(localToken),
		url.QueryEscape(refreshToken),
		url.QueryEscape(localToken),
	)
	return c.Redirect(http.StatusFound, dest)
}

// isWebRedirect checks if the redirect URL is a web origin (not localhost/native).
func (h *AuthHandler) isWebRedirect(redirectURL string) bool {
	parsed, err := url.Parse(redirectURL)
	if err != nil {
		return false
	}
	// Localhost callbacks and custom schemes are native
	if parsed.Hostname() == "localhost" || parsed.Hostname() == "127.0.0.1" {
		return false
	}
	if parsed.Scheme == "rmschatroom" {
		return false
	}
	// Check if it's under one of the CORS origins with /callback path
	for _, origin := range h.config.CORSOrigins {
		if strings.HasPrefix(redirectURL, origin) && strings.HasSuffix(parsed.Path, "/callback") {
			return true
		}
	}
	return true
}

// isValidRedirect validates the redirect URL against allowed patterns.
func (h *AuthHandler) isValidRedirect(redirectURL string) bool {
	parsed, err := url.Parse(redirectURL)
	if err != nil {
		return false
	}
	// Allow rmschatroom://callback
	if parsed.Scheme == "rmschatroom" && parsed.Path == "/callback" {
		return true
	}
	// Allow localhost callbacks
	if parsed.Hostname() == "localhost" || parsed.Hostname() == "127.0.0.1" {
		return true
	}
	// Allow /callback under CORS origins
	for _, origin := range h.config.CORSOrigins {
		if strings.HasPrefix(redirectURL, origin) && strings.HasSuffix(parsed.Path, "/callback") {
			return true
		}
	}
	return false
}

// Refresh exchanges a refresh token for new access + refresh tokens.
// POST /api/auth/refresh
func (h *AuthHandler) Refresh(c echo.Context) error {
	// Accept JSON body or query param
	var refreshToken string
	var body struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := c.Bind(&body); err == nil && body.RefreshToken != "" {
		refreshToken = body.RefreshToken
	}
	if refreshToken == "" {
		refreshToken = c.QueryParam("refresh_token")
	}
	if refreshToken == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "refresh_token is required"})
	}

	hash := sha256.Sum256([]byte(refreshToken))
	tokenHash := hex.EncodeToString(hash[:])

	var userID int64
	var expiresAt time.Time
	err := h.db.QueryRow(
		"SELECT user_id, expires_at FROM auth_refresh_tokens WHERE token_hash = ?", tokenHash,
	).Scan(&userID, &expiresAt)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid refresh token"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	if time.Now().UTC().After(expiresAt) {
		h.db.Exec("DELETE FROM auth_refresh_tokens WHERE token_hash = ?", tokenHash)
		return c.JSON(http.StatusUnauthorized, map[string]string{"error": "refresh token expired"})
	}

	// Delete old token
	h.db.Exec("DELETE FROM auth_refresh_tokens WHERE token_hash = ?", tokenHash)

	// Fetch user info from SSO to get current data
	user, err := h.sso.GetUserByID(int(userID))
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to fetch user info"})
	}

	// Generate new JWT
	now := time.Now().UTC()
	claims := jwt.MapClaims{
		"id":               user.ID,
		"username":         user.Username,
		"nickname":         user.Nickname,
		"email":            user.Email,
		"permission_level": user.PermissionLevel,
		"iat":              now.Unix(),
		"exp":              now.Add(7 * 24 * time.Hour).Unix(),
	}
	newAccessToken, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(h.config.JWTSecret))
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to generate access token"})
	}

	// Generate new refresh token
	refreshBytes := make([]byte, 32)
	rand.Read(refreshBytes)
	newRefreshToken := hex.EncodeToString(refreshBytes)

	newHash := sha256.Sum256([]byte(newRefreshToken))
	newTokenHash := hex.EncodeToString(newHash[:])
	newExpiresAt := now.Add(30 * 24 * time.Hour)

	h.db.Exec(
		"INSERT INTO auth_refresh_tokens (user_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)",
		userID, newTokenHash, now, newExpiresAt,
	)

	return c.JSON(http.StatusOK, map[string]string{
		"access_token":  newAccessToken,
		"refresh_token": newRefreshToken,
		"token":         newAccessToken,
	})
}

// Logout revokes a refresh token.
// POST /api/auth/logout  (also POST /api/auth/revoke as alias)
func (h *AuthHandler) Logout(c echo.Context) error {
	var body struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := c.Bind(&body); err == nil && body.RefreshToken != "" {
		hash := sha256.Sum256([]byte(body.RefreshToken))
		tokenHash := hex.EncodeToString(hash[:])
		h.db.Exec("DELETE FROM auth_refresh_tokens WHERE token_hash = ?", tokenHash)
	}
	return c.JSON(http.StatusOK, map[string]bool{"success": true})
}

// DevLogin generates a mock JWT for testing (debug mode only).
// GET /api/auth/dev-login
func (h *AuthHandler) DevLogin(c echo.Context) error {
	if !h.config.Debug {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "dev login only available in debug mode"})
	}

	now := time.Now().UTC()
	claims := jwt.MapClaims{
		"id":               1,
		"username":         "testuser",
		"nickname":         "Test User",
		"email":            "test@example.com",
		"permission_level": 3,
		"iat":              now.Unix(),
		"exp":              now.Add(7 * 24 * time.Hour).Unix(),
	}
	token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(h.config.JWTSecret))
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to generate token"})
	}

	redirectURL := c.QueryParam("redirect_url")
	if redirectURL == "" {
		redirectURL = "http://localhost:5173/callback"
	}
	return c.Redirect(http.StatusFound, redirectURL+"?token="+url.QueryEscape(token))
}

// Me returns the current authenticated user info.
// GET /api/auth/me
func (h *AuthHandler) Me(c echo.Context) error {
	user := middleware.GetUser(c)
	if user == nil {
		return c.JSON(http.StatusUnauthorized, map[string]string{"error": "not authenticated"})
	}
	return c.JSON(http.StatusOK, map[string]interface{}{
		"success": true,
		"user":    user,
	})
}
