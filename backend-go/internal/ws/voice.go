package ws

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

// In-memory host mode state: room_name -> host user info
var (
	hostModeState = make(map[string]*hostInfo)
	hostModeMu    sync.RWMutex
)

type hostInfo struct {
	UserID   string `json:"host_id"`
	Username string `json:"host_name"`
}

// In-memory screen share lock state: room_name -> sharer info
var (
	screenShareLock   = make(map[string]*sharerInfo)
	screenShareLockMu sync.RWMutex
)

type sharerInfo struct {
	SharerID   string
	SharerName string
}

// HandleVoiceWS handles the /ws/voice WebSocket endpoint for signaling.
func HandleVoiceWS(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		token := c.QueryParam("token")
		if token == "" {
			return c.JSON(http.StatusUnauthorized, map[string]string{"error": "missing token"})
		}

		user, err := ssoClient.VerifyToken(token)
		if err != nil {
			return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid token"})
		}

		ws, err := upgrader.Upgrade(c.Response(), c.Request(), nil)
		if err != nil {
			return err
		}

		conn := newConn(ws, user)
		VoiceManager.ConnectGlobal(conn)
		defer VoiceManager.DisconnectGlobal(conn)

		connected, _ := json.Marshal(map[string]string{"type": "connected"})
		conn.send <- connected

		go conn.WritePump()

		conn.ReadPump(func(raw []byte) {
			var msg map[string]interface{}
			if err := json.Unmarshal(raw, &msg); err != nil {
				return
			}
			if t, _ := msg["type"].(string); t == "ping" {
				if d, _ := msg["data"].(string); d == "tribios" {
					pong, _ := json.Marshal(map[string]string{"type": "pong", "data": "cute"})
					conn.send <- pong
				}
			}
		})

		return nil
	}
}

// RegisterVoiceHTTP registers voice-related HTTP endpoints.
func RegisterVoiceHTTP(g *echo.Group, ssoClient *sso.Client, db *sql.DB, cfg *config.Config) {
	g.GET("/:channel_id/token", voiceToken(ssoClient, cfg))
	g.GET("/:channel_id/users", voiceUsers(ssoClient, cfg))
	g.POST("/:channel_id/mute/:user_id", voiceAdminAction(ssoClient, "mute"))
	g.POST("/:channel_id/kick/:user_id", voiceAdminAction(ssoClient, "kick"))
	g.GET("/:channel_id/host-mode", voiceHostModeGet(db))
	g.POST("/:channel_id/host-mode", voiceHostModeSet(ssoClient, db))
	g.POST("/:channel_id/screen-share/lock", voiceScreenShareLock(ssoClient, db))
	g.POST("/:channel_id/screen-share/unlock", voiceScreenShareUnlock(ssoClient, db))
	g.GET("/:channel_id/screen-share-status", voiceScreenShareStatus(db))
	g.POST("/:channel_id/invite", voiceInviteCreate(ssoClient, db))
	g.GET("/invite/:token", voiceInviteValidate(db))
	g.POST("/invite/:token/join", voiceInviteJoin(db, cfg))
	g.GET("/user/all", voiceAllUsers(ssoClient, db))

	// QQ Bot endpoint (separate group to avoid param conflict)
	qqbot := g.Group("")
	qqbot.GET("/qqbot/get_voice_channel_people", voiceQQBotUsers(db))
}

func voiceToken(ssoClient *sso.Client, cfg *config.Config) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		channelID := c.Param("channel_id")

		// LiveKit token generation stub - requires livekit-server-sdk-go
		return c.JSON(http.StatusOK, map[string]interface{}{
			"token":      fmt.Sprintf("livekit-token-stub-%s-%d", channelID, user.ID),
			"livekit_url": cfg.LivekitHost,
		})
	}
}

func voiceUsers(ssoClient *sso.Client, cfg *config.Config) echo.HandlerFunc {
	return func(c echo.Context) error {
		_, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		// Stub: would query LiveKit API for room participants
		return c.JSON(http.StatusOK, map[string]interface{}{
			"users": []interface{}{},
		})
	}
}

func voiceAdminAction(ssoClient *sso.Client, action string) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		if !permission.IsAdmin(user) {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "admin required"})
		}
		// Stub: would call LiveKit API
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true,
			"action":  action,
		})
	}
}

// voiceHostModeGet returns current host mode status for a voice channel.
func voiceHostModeGet(db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		channelID := c.Param("channel_id")

		// Verify channel exists and is VOICE type
		var chType string
		err := db.QueryRow("SELECT type FROM channels WHERE id = ?", channelID).Scan(&chType)
		if err == sql.ErrNoRows {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
		}
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		if chType != "VOICE" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a voice channel"})
		}

		roomName := fmt.Sprintf("voice_%s", channelID)
		hostModeMu.RLock()
		host := hostModeState[roomName]
		hostModeMu.RUnlock()

		if host != nil {
			return c.JSON(http.StatusOK, map[string]interface{}{
				"enabled":   true,
				"host_id":   host.UserID,
				"host_name": host.Username,
			})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"enabled":   false,
			"host_id":   nil,
			"host_name": nil,
		})
	}
}

// voiceHostModeSet enables or disables host mode (admin only).
func voiceHostModeSet(ssoClient *sso.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		if !permission.IsAdmin(user) {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "admin required"})
		}

		channelID := c.Param("channel_id")
		var chType string
		err = db.QueryRow("SELECT type FROM channels WHERE id = ?", channelID).Scan(&chType)
		if err == sql.ErrNoRows {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
		}
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		if chType != "VOICE" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a voice channel"})
		}

		var body struct {
			Enabled bool `json:"enabled"`
		}
		if err := c.Bind(&body); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}

		roomName := fmt.Sprintf("voice_%s", channelID)
		userID := fmt.Sprintf("%d", user.ID)
		username := user.Nickname
		if username == "" {
			username = user.Username
		}

		hostModeMu.Lock()
		current := hostModeState[roomName]
		if body.Enabled && current != nil && current.UserID != userID {
			hostModeMu.Unlock()
			return c.JSON(http.StatusConflict, map[string]string{"error": "host mode is already active by another user"})
		}
		if !body.Enabled && current != nil && current.UserID != userID {
			hostModeMu.Unlock()
			return c.JSON(http.StatusForbidden, map[string]string{"error": "only the current host can disable host mode"})
		}

		if body.Enabled {
			hostModeState[roomName] = &hostInfo{UserID: userID, Username: username}
		} else {
			delete(hostModeState, roomName)
		}
		hostModeMu.Unlock()

		// Broadcast host mode update
		GlobalStateManager.BroadcastToAllUsers(map[string]interface{}{
			"type":       "host_mode_update",
			"channel_id": channelID,
			"enabled":    body.Enabled,
			"host_id":    userID,
			"host_name":  username,
		})

		if body.Enabled {
			return c.JSON(http.StatusOK, map[string]interface{}{
				"enabled": true, "host_id": userID, "host_name": username,
			})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"enabled": false, "host_id": nil, "host_name": nil,
		})
	}
}

func voiceScreenShareStatus(db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		channelID := c.Param("channel_id")
		var chType string
		err := db.QueryRow("SELECT type FROM channels WHERE id = ?", channelID).Scan(&chType)
		if err == sql.ErrNoRows {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
		}
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		if chType != "VOICE" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a voice channel"})
		}

		roomName := fmt.Sprintf("voice_%s", channelID)
		screenShareLockMu.RLock()
		info := screenShareLock[roomName]
		screenShareLockMu.RUnlock()

		if info != nil {
			return c.JSON(http.StatusOK, map[string]interface{}{
				"locked": true, "sharer_id": info.SharerID, "sharer_name": info.SharerName,
			})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"locked": false, "sharer_id": nil, "sharer_name": nil,
		})
	}
}

func voiceScreenShareLock(ssoClient *sso.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		channelID := c.Param("channel_id")
		var chType string
		err = db.QueryRow("SELECT type FROM channels WHERE id = ?", channelID).Scan(&chType)
		if err == sql.ErrNoRows {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
		}
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		if chType != "VOICE" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a voice channel"})
		}

		roomName := fmt.Sprintf("voice_%s", channelID)
		userID := fmt.Sprintf("%d", user.ID)
		username := user.Nickname
		if username == "" {
			username = user.Username
		}

		screenShareLockMu.Lock()
		current := screenShareLock[roomName]
		if current != nil {
			if current.SharerID == userID {
				screenShareLockMu.Unlock()
				return c.JSON(http.StatusOK, map[string]interface{}{
					"success": true, "sharer_id": userID, "sharer_name": username,
				})
			}
			screenShareLockMu.Unlock()
			return c.JSON(http.StatusConflict, map[string]interface{}{
				"success": false, "sharer_id": current.SharerID, "sharer_name": current.SharerName,
			})
		}
		screenShareLock[roomName] = &sharerInfo{SharerID: userID, SharerName: username}
		screenShareLockMu.Unlock()

		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true, "sharer_id": userID, "sharer_name": username,
		})
	}
}

func voiceScreenShareUnlock(ssoClient *sso.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		channelID := c.Param("channel_id")
		roomName := fmt.Sprintf("voice_%s", channelID)
		userID := fmt.Sprintf("%d", user.ID)

		screenShareLockMu.Lock()
		current := screenShareLock[roomName]
		if current != nil && current.SharerID == userID {
			delete(screenShareLock, roomName)
		}
		screenShareLockMu.Unlock()

		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true, "sharer_id": nil, "sharer_name": nil,
		})
	}
}

func voiceInviteCreate(ssoClient *sso.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		if !permission.IsAdmin(user) {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "admin required"})
		}

		channelID := c.Param("channel_id")
		tokenBytes := make([]byte, 32)
		rand.Read(tokenBytes)
		inviteToken := hex.EncodeToString(tokenBytes)

		_, err = db.Exec(
			"INSERT INTO voice_invites (channel_id, token, created_by) VALUES (?, ?, ?)",
			channelID, inviteToken, user.ID,
		)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to create invite"})
		}

		return c.JSON(http.StatusOK, map[string]interface{}{
			"token": inviteToken,
		})
	}
}

func voiceInviteValidate(db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		token := c.Param("token")

		var id, channelID int64
		var used bool
		err := db.QueryRow(
			"SELECT id, channel_id, used FROM voice_invites WHERE token = ?", token,
		).Scan(&id, &channelID, &used)
		if err != nil {
			return c.JSON(http.StatusNotFound, map[string]interface{}{"valid": false})
		}
		if used {
			return c.JSON(http.StatusGone, map[string]interface{}{"valid": false})
		}

		// Fetch channel name and server name
		var channelName string
		var serverID int64
		err = db.QueryRow("SELECT name, server_id FROM channels WHERE id = ?", channelID).Scan(&channelName, &serverID)
		if err != nil {
			return c.JSON(http.StatusOK, map[string]interface{}{"valid": true, "channel_id": channelID})
		}
		var serverName string
		db.QueryRow("SELECT name FROM servers WHERE id = ?", serverID).Scan(&serverName)

		return c.JSON(http.StatusOK, map[string]interface{}{
			"valid":        true,
			"channel_name": channelName,
			"server_name":  serverName,
		})
	}
}

// voiceInviteJoin allows a guest to join a voice channel using an invite token (no auth).
func voiceInviteJoin(db *sql.DB, cfg *config.Config) echo.HandlerFunc {
	return func(c echo.Context) error {
		token := c.Param("token")

		var body struct {
			Username string `json:"username"`
		}
		if err := c.Bind(&body); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		username := strings.TrimSpace(body.Username)
		if username == "" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "username is required"})
		}
		if len(username) > 50 {
			username = username[:50]
		}

		var id, channelID int64
		var used bool
		err := db.QueryRow(
			"SELECT id, channel_id, used FROM voice_invites WHERE token = ?", token,
		).Scan(&id, &channelID, &used)
		if err == sql.ErrNoRows {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "invite not found"})
		}
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		if used {
			return c.JSON(http.StatusGone, map[string]string{"error": "this invite has already been used"})
		}

		// Mark invite as used
		_, err = db.Exec(
			"UPDATE voice_invites SET used = TRUE, used_by_name = ?, used_at = UTC_TIMESTAMP() WHERE id = ?",
			username, id,
		)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to use invite"})
		}

		// Get channel name
		var channelName string
		db.QueryRow("SELECT name FROM channels WHERE id = ?", channelID).Scan(&channelName)

		roomName := fmt.Sprintf("voice_%d", channelID)
		guestIdentity := fmt.Sprintf("guest_%s", token[:8])

		// Stub: would generate real LiveKit token with livekit-server-sdk-go
		return c.JSON(http.StatusOK, map[string]interface{}{
			"token":        fmt.Sprintf("livekit-guest-stub-%s-%s", guestIdentity, roomName),
			"url":          cfg.LivekitHost,
			"room_name":    roomName,
			"channel_name": channelName,
		})
	}
}

// voiceAllUsers returns all users in all voice channels.
func voiceAllUsers(ssoClient *sso.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		_, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}

		// Stub: would query LiveKit API for all voice channel rooms
		// Return empty map matching Python response format: {users: {channel_id: [users]}}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"users": map[string]interface{}{},
		})
	}
}

// voiceQQBotUsers returns all voice channel users for QQ bot integration.
func voiceQQBotUsers(db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		// Stub: would query LiveKit API for all voice channel rooms
		return c.JSON(http.StatusOK, map[string]interface{}{
			"channels":    []interface{}{},
			"total_users": 0,
		})
	}
}

// authenticateRequest extracts Bearer token or query param and verifies.
func authenticateRequest(c echo.Context, ssoClient *sso.Client) (*permission.UserInfo, error) {
	token := c.QueryParam("token")
	if token == "" {
		auth := c.Request().Header.Get("Authorization")
		if len(auth) > 7 && auth[:7] == "Bearer " {
			token = auth[7:]
		}
	}
	if token == "" {
		return nil, c.JSON(http.StatusUnauthorized, map[string]string{"error": "missing token"})
	}
	user, err := ssoClient.VerifyToken(token)
	if err != nil {
		return nil, c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid token"})
	}
	return user, nil
}
