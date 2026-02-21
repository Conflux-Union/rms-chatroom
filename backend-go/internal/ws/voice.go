package ws

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

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
	g.POST("/:channel_id/host-mode", voiceHostMode(ssoClient))
	g.POST("/:channel_id/screen-share/lock", voiceScreenShare(ssoClient, "lock"))
	g.POST("/:channel_id/screen-share/unlock", voiceScreenShare(ssoClient, "unlock"))
	g.GET("/:channel_id/screen-share/status", voiceScreenShare(ssoClient, "status"))
	g.POST("/:channel_id/invite", voiceInviteCreate(ssoClient, db))
	g.GET("/invite/:token", voiceInviteValidate(db))
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

func voiceHostMode(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		if !permission.IsAdmin(user) {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "admin required"})
		}
		var body struct {
			Enabled bool `json:"enabled"`
		}
		if err := c.Bind(&body); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		// Broadcast host mode update via GlobalStateManager
		GlobalStateManager.BroadcastToAllUsers(map[string]interface{}{
			"type":      "host_mode_update",
			"channel_id": c.Param("channel_id"),
			"enabled":   body.Enabled,
			"host_name": user.Username,
		})
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true})
	}
}

func voiceScreenShare(ssoClient *sso.Client, action string) echo.HandlerFunc {
	return func(c echo.Context) error {
		_, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true,
			"action":  action,
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
			return c.JSON(http.StatusNotFound, map[string]string{"error": "invite not found"})
		}
		if used {
			return c.JSON(http.StatusGone, map[string]string{"error": "invite already used"})
		}

		return c.JSON(http.StatusOK, map[string]interface{}{
			"valid":      true,
			"channel_id": channelID,
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
