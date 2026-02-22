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
	"strconv"
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

// Login redirects to the OAuth authorize URL with JWT-encoded state.
// GET /api/auth/login
func (h *AuthHandler) Login(c echo.Context) error {
	redirectURL := c.QueryParam("redirect_url")
	if redirectURL == "" {
		redirectURL = "http://localhost:5173/callback"
	}

	if !h.isValidRedirect(redirectURL) {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid redirect_url"})
	}

	// Generate nonce
	nonceBytes := make([]byte, 16)
	if _, err := rand.Read(nonceBytes); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to generate nonce"})
	}

	// Encode state as JWT (redirect_url + nonce + 10min expiry)
	now := time.Now().UTC()
	stateClaims := jwt.MapClaims{
		"redirect_url": redirectURL,
		"nonce":        hex.EncodeToString(nonceBytes),
		"iat":          now.Unix(),
		"exp":          now.Add(10 * time.Minute).Unix(),
	}
	stateToken, err := jwt.NewWithClaims(jwt.SigningMethodHS256, stateClaims).SignedString([]byte(h.config.JWTSecret))
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to generate state"})
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
	params.Set("state", stateToken)

	loginURL := baseURL + endpoint + "?" + params.Encode()
	return c.Redirect(http.StatusFound, loginURL)
}

// Callback handles the OAuth authorization code exchange.
// GET /api/auth/callback
func (h *AuthHandler) Callback(c echo.Context) error {
	code := c.QueryParam("code")
	stateParam := c.QueryParam("state")
	if code == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "missing authorization code"})
	}

	// Validate state JWT
	var redirectURL string
	if stateParam != "" {
		token, err := jwt.Parse(stateParam, func(t *jwt.Token) (interface{}, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing method")
			}
			return []byte(h.config.JWTSecret), nil
		})
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid or expired state"})
		}
		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok || !token.Valid {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid state claims"})
		}
		if ru, ok := claims["redirect_url"].(string); ok {
			redirectURL = ru
		}
	}

	// Also accept redirect_url query param as fallback (must be validated)
	if redirectURL == "" {
		if ru := c.QueryParam("redirect_url"); ru != "" && h.isValidRedirect(ru) {
			redirectURL = ru
		}
	}
	if redirectURL == "" {
		redirectURL = "http://localhost:5173/callback"
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

	// Parse userinfo with nested group.level
	// OIDC returns "sub" as string, e.g. "42"
	var userInfo struct {
		Sub             string `json:"sub"`
		Username        string `json:"username"`
		Nickname        string `json:"nickname"`
		Email           string `json:"email"`
		PermissionLevel int    `json:"permission_level"`
		AvatarURL       string `json:"avatar_url"`
		Group           struct {
			ID    int    `json:"id"`
			Name  string `json:"name"`
			Level int    `json:"level"`
		} `json:"group"`
	}
	if err := json.Unmarshal(infoBody, &userInfo); err != nil || userInfo.Sub == "" {
		return c.JSON(http.StatusBadGateway, map[string]string{"error": "invalid SSO userinfo response"})
	}
	userID, err := strconv.Atoi(userInfo.Sub)
	if err != nil {
		return c.JSON(http.StatusBadGateway, map[string]string{"error": "invalid sub in userinfo"})
	}

	// Generate local JWT access token with configurable expiry
	now := time.Now().UTC()
	expireMinutes := h.config.AccessTokenExpireMinutes
	if expireMinutes <= 0 {
		expireMinutes = 15
	}
	claims := jwt.MapClaims{
		"id":               userID,
		"username":         userInfo.Username,
		"nickname":         userInfo.Nickname,
		"email":            userInfo.Email,
		"permission_level": userInfo.PermissionLevel,
		"group_level":      userInfo.Group.Level,
		"avatar_url":       userInfo.AvatarURL,
		"iat":              now.Unix(),
		"exp":              now.Add(time.Duration(expireMinutes) * time.Minute).Unix(),
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

	// Store SHA-256 hash of refresh token with user metadata
	hash := sha256.Sum256([]byte(refreshToken))
	tokenHash := hex.EncodeToString(hash[:])
	expireDays := h.config.RefreshTokenExpireDays
	if expireDays <= 0 {
		expireDays = 30
	}
	expiresAt := now.Add(time.Duration(expireDays) * 24 * time.Hour)

	_, err = h.db.Exec(
		`INSERT INTO auth_refresh_tokens (user_id, username, nickname, email, permission_level, group_level, token_hash, created_at, expires_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		userID, userInfo.Username, userInfo.Nickname, userInfo.Email,
		userInfo.PermissionLevel, userInfo.Group.Level,
		tokenHash, now, expiresAt,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to store refresh token"})
	}

	// Validate and redirect
	if h.isWebRedirect(redirectURL) {
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
	if parsed.Hostname() == "localhost" || parsed.Hostname() == "127.0.0.1" {
		return false
	}
	if parsed.Scheme == "rmschatroom" {
		return false
	}
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
	if parsed.Scheme == "rmschatroom" && parsed.Hostname() == "callback" {
		return true
	}
	if parsed.Hostname() == "localhost" || parsed.Hostname() == "127.0.0.1" {
		return true
	}
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

	// Read stored metadata along with the token
	var userID int64
	var expiresAt time.Time
	var storedUsername, storedNickname, storedEmail string
	var storedPermLevel, storedGroupLevel int
	err := h.db.QueryRow(
		`SELECT user_id, expires_at, username, nickname, email, permission_level, group_level
		 FROM auth_refresh_tokens WHERE token_hash = ?`, tokenHash,
	).Scan(&userID, &expiresAt, &storedUsername, &storedNickname, &storedEmail, &storedPermLevel, &storedGroupLevel)
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

	// Best-effort SSO refresh, fall back to stored metadata
	username := storedUsername
	nickname := storedNickname
	email := storedEmail
	permLevel := storedPermLevel
	groupLevel := storedGroupLevel
	avatarURL := ""

	if user, err := h.sso.GetUserByID(int(userID)); err == nil {
		username = user.Username
		nickname = user.Nickname
		email = user.Email
		permLevel = user.PermissionLevel
		groupLevel = user.GroupLevel
		avatarURL = user.AvatarURL
	}

	// Generate new JWT
	now := time.Now().UTC()
	expireMinutes := h.config.AccessTokenExpireMinutes
	if expireMinutes <= 0 {
		expireMinutes = 15
	}
	claims := jwt.MapClaims{
		"id":               userID,
		"username":         username,
		"nickname":         nickname,
		"email":            email,
		"permission_level": permLevel,
		"group_level":      groupLevel,
		"avatar_url":       avatarURL,
		"iat":              now.Unix(),
		"exp":              now.Add(time.Duration(expireMinutes) * time.Minute).Unix(),
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
	expireDays := h.config.RefreshTokenExpireDays
	if expireDays <= 0 {
		expireDays = 30
	}
	newExpiresAt := now.Add(time.Duration(expireDays) * 24 * time.Hour)

	// Store new token BEFORE deleting old one (safe rotation)
	_, err = h.db.Exec(
		`INSERT INTO auth_refresh_tokens (user_id, username, nickname, email, permission_level, group_level, token_hash, created_at, expires_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		userID, username, nickname, email, permLevel, groupLevel,
		newTokenHash, now, newExpiresAt,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to store new refresh token"})
	}

	// Delete old token after new one is stored
	h.db.Exec("DELETE FROM auth_refresh_tokens WHERE token_hash = ?", tokenHash)

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
	expireMinutes := h.config.AccessTokenExpireMinutes
	if expireMinutes <= 0 {
		expireMinutes = 15
	}
	claims := jwt.MapClaims{
		"id":               1,
		"username":         "testuser",
		"nickname":         "Test User",
		"email":            "test@example.com",
		"permission_level": 3,
		"group_level":      99,
		"iat":              now.Unix(),
		"exp":              now.Add(time.Duration(expireMinutes) * time.Minute).Unix(),
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
