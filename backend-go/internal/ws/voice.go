package ws

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"

	"github.com/labstack/echo/v4"
	"github.com/livekit/protocol/livekit"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	"github.com/RMS-Server/rms-discord-go/internal/lk"
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

// HandleVoiceWS handles the /ws/voice WebSocket endpoint.
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
	lkc := lk.New(cfg)

	g.GET("/:channel_id/token", voiceToken(ssoClient, lkc))
	g.GET("/:channel_id/users", voiceUsers(ssoClient, lkc, db))
	g.POST("/:channel_id/mute/:user_id", voiceMute(ssoClient, lkc, db))
	g.POST("/:channel_id/kick/:user_id", voiceKick(ssoClient, lkc, db))
	g.GET("/:channel_id/host-mode", voiceHostModeGet(db))
	g.POST("/:channel_id/host-mode", voiceHostModeSet(ssoClient, lkc, db))
	g.POST("/:channel_id/screen-share/lock", voiceScreenShareLock(ssoClient, db))
	g.POST("/:channel_id/screen-share/unlock", voiceScreenShareUnlock(ssoClient, db))
	g.GET("/:channel_id/screen-share-status", voiceScreenShareStatus(db))
	g.POST("/:channel_id/invite", voiceInviteCreate(ssoClient, db))
	g.GET("/invite/:token", voiceInviteValidate(db))
	g.POST("/invite/:token/join", voiceInviteJoin(db, lkc))
	g.GET("/user/all", voiceAllUsers(ssoClient, lkc, db))

	qqbot := g.Group("")
	qqbot.GET("/qqbot/get_voice_channel_people", voiceQQBotUsers(lkc, db))
}

func authenticateRequest(c echo.Context, ssoClient *sso.Client) (*permission.UserInfo, error) {
	token := c.QueryParam("token")
	if token == "" {
		a := c.Request().Header.Get("Authorization")
		if len(a) > 7 && a[:7] == "Bearer " {
			token = a[7:]
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

func displayName(u *permission.UserInfo) string {
	if u.Nickname != "" {
		return u.Nickname
	}
	return u.Username
}

func verifyVoiceChannel(db *sql.DB, channelID string) error {
	var chType string
	err := db.QueryRow("SELECT type FROM channels WHERE id = ?", channelID).Scan(&chType)
	if err == sql.ErrNoRows {
		return fmt.Errorf("not_found")
	}
	if err != nil {
		return fmt.Errorf("internal: %w", err)
	}
	if chType != "VOICE" {
		return fmt.Errorf("not_voice")
	}
	return nil
}

func channelError(c echo.Context, err error) error {
	switch {
	case err.Error() == "not_found":
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
	case err.Error() == "not_voice":
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a voice channel"})
	default:
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
}

// --- Token ---

func voiceToken(ssoClient *sso.Client, lkc *lk.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		channelID := c.Param("channel_id")
		roomName := lk.RoomName(channelID)
		identity := fmt.Sprintf("%d", user.ID)

		token, err := lkc.CreateToken(identity, displayName(user), roomName)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to create token"})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"token": token, "url": lkc.Host(), "room_name": roomName,
		})
	}
}

// --- Users ---

func voiceUsers(ssoClient *sso.Client, lkc *lk.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		_, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		channelID := c.Param("channel_id")
		if err := verifyVoiceChannel(db, channelID); err != nil {
			return channelError(c, err)
		}

		ctx := context.Background()
		roomName := lk.RoomName(channelID)
		participants, err := lkc.ListParticipants(ctx, roomName)
		if err != nil {
			return c.JSON(http.StatusOK, []interface{}{})
		}

		hostID := resolveHostID(roomName, participants)
		users := buildUserList(ctx, lkc, ssoClient, roomName, hostID, participants)
		return c.JSON(http.StatusOK, users)
	}
}

// --- Mute ---

func voiceMute(ssoClient *sso.Client, lkc *lk.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		if !permission.IsAdmin(user) {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "admin required"})
		}
		channelID := c.Param("channel_id")
		if err := verifyVoiceChannel(db, channelID); err != nil {
			return channelError(c, err)
		}
		var body struct {
			Muted bool `json:"muted"`
		}
		body.Muted = true
		c.Bind(&body)

		ok, err := lkc.MuteMicrophone(context.Background(), lk.RoomName(channelID), c.Param("user_id"), body.Muted)
		if err != nil || !ok {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "participant not found or no microphone track"})
		}
		go broadcastVoiceUsersUpdate(lkc, ssoClient, db)
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true, "muted": body.Muted})
	}
}

// --- Kick ---

func voiceKick(ssoClient *sso.Client, lkc *lk.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		if !permission.IsAdmin(user) {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "admin required"})
		}
		channelID := c.Param("channel_id")
		if err := verifyVoiceChannel(db, channelID); err != nil {
			return channelError(c, err)
		}
		if err := lkc.RemoveParticipant(context.Background(), lk.RoomName(channelID), c.Param("user_id")); err != nil {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "participant not found"})
		}
		go broadcastVoiceUsersUpdate(lkc, ssoClient, db)
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true})
	}
}

// --- Host Mode ---

func voiceHostModeGet(db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		channelID := c.Param("channel_id")
		if err := verifyVoiceChannel(db, channelID); err != nil {
			return channelError(c, err)
		}
		roomName := lk.RoomName(channelID)
		hostModeMu.RLock()
		host := hostModeState[roomName]
		hostModeMu.RUnlock()

		if host != nil {
			return c.JSON(http.StatusOK, map[string]interface{}{
				"enabled": true, "host_id": host.UserID, "host_name": host.Username,
			})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"enabled": false, "host_id": nil, "host_name": nil,
		})
	}
}

func voiceHostModeSet(ssoClient *sso.Client, lkc *lk.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		if !permission.IsAdmin(user) {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "admin required"})
		}
		channelID := c.Param("channel_id")
		if err := verifyVoiceChannel(db, channelID); err != nil {
			return channelError(c, err)
		}
		var body struct {
			Enabled bool `json:"enabled"`
		}
		if err := c.Bind(&body); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}

		roomName := lk.RoomName(channelID)
		userID := fmt.Sprintf("%d", user.ID)
		username := displayName(user)

		hostModeMu.Lock()
		cur := hostModeState[roomName]
		if body.Enabled && cur != nil && cur.UserID != userID {
			hostModeMu.Unlock()
			return c.JSON(http.StatusConflict, map[string]string{"error": "host mode is already active by another user"})
		}
		if !body.Enabled && cur != nil && cur.UserID != userID {
			hostModeMu.Unlock()
			return c.JSON(http.StatusForbidden, map[string]string{"error": "only the current host can disable host mode"})
		}
		if body.Enabled {
			hostModeState[roomName] = &hostInfo{UserID: userID, Username: username}
		} else {
			delete(hostModeState, roomName)
		}
		hostModeMu.Unlock()

		if body.Enabled {
			ctx := context.Background()
			ps, _ := lkc.ListParticipants(ctx, roomName)
			for _, p := range ps {
				if p.Identity != userID {
					lkc.MuteMicrophone(ctx, roomName, p.Identity, true)
				}
			}
		}

		GlobalStateManager.BroadcastToAllUsers(map[string]interface{}{
			"type": "host_mode_update", "channel_id": channelID,
			"enabled": body.Enabled, "host_id": userID, "host_name": username,
		})
		go broadcastVoiceUsersUpdate(lkc, ssoClient, db)

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

// --- Screen Share ---

func voiceScreenShareStatus(db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		channelID := c.Param("channel_id")
		if err := verifyVoiceChannel(db, channelID); err != nil {
			return channelError(c, err)
		}
		roomName := lk.RoomName(channelID)
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
		if err := verifyVoiceChannel(db, channelID); err != nil {
			return channelError(c, err)
		}
		roomName := lk.RoomName(channelID)
		userID := fmt.Sprintf("%d", user.ID)
		username := displayName(user)

		screenShareLockMu.Lock()
		cur := screenShareLock[roomName]
		if cur != nil {
			if cur.SharerID == userID {
				screenShareLockMu.Unlock()
				return c.JSON(http.StatusOK, map[string]interface{}{
					"success": true, "sharer_id": userID, "sharer_name": username,
				})
			}
			screenShareLockMu.Unlock()
			return c.JSON(http.StatusConflict, map[string]interface{}{
				"success": false, "sharer_id": cur.SharerID, "sharer_name": cur.SharerName,
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
		roomName := lk.RoomName(channelID)
		userID := fmt.Sprintf("%d", user.ID)

		screenShareLockMu.Lock()
		cur := screenShareLock[roomName]
		if cur != nil && cur.SharerID == userID {
			delete(screenShareLock, roomName)
		}
		screenShareLockMu.Unlock()
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true, "sharer_id": nil, "sharer_name": nil,
		})
	}
}

// --- Invites ---

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
		return c.JSON(http.StatusOK, map[string]interface{}{"token": inviteToken})
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
		var channelName string
		var serverID int64
		err = db.QueryRow("SELECT name, server_id FROM channels WHERE id = ?", channelID).Scan(&channelName, &serverID)
		if err != nil {
			return c.JSON(http.StatusOK, map[string]interface{}{"valid": true, "channel_id": channelID})
		}
		var serverName string
		db.QueryRow("SELECT name FROM servers WHERE id = ?", serverID).Scan(&serverName)
		return c.JSON(http.StatusOK, map[string]interface{}{
			"valid": true, "channel_name": channelName, "server_name": serverName,
		})
	}
}

func voiceInviteJoin(db *sql.DB, lkc *lk.Client) echo.HandlerFunc {
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

		_, err = db.Exec(
			"UPDATE voice_invites SET used = TRUE, used_by_name = ?, used_at = UTC_TIMESTAMP() WHERE id = ?",
			username, id,
		)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to use invite"})
		}

		var channelName string
		db.QueryRow("SELECT name FROM channels WHERE id = ?", channelID).Scan(&channelName)

		roomName := lk.RoomName(fmt.Sprintf("%d", channelID))
		guestIdentity := fmt.Sprintf("guest_%s", token[:8])
		lkToken, err := lkc.CreateToken(guestIdentity, fmt.Sprintf("[Guest] %s", username), roomName)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to create token"})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"token": lkToken, "url": lkc.Host(),
			"room_name": roomName, "channel_name": channelName,
		})
	}
}

// --- All Users / QQ Bot ---

func voiceAllUsers(ssoClient *sso.Client, lkc *lk.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		_, err := authenticateRequest(c, ssoClient)
		if err != nil {
			return err
		}
		usersByChannel := collectAllVoiceUsers(context.Background(), lkc, ssoClient, db)
		return c.JSON(http.StatusOK, map[string]interface{}{"users": usersByChannel})
	}
}

func voiceQQBotUsers(lkc *lk.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		ctx := context.Background()
		rows, err := db.Query(`
			SELECT c.id, c.name, s.name
			FROM channels c JOIN servers s ON c.server_id = s.id
			WHERE c.type = 'VOICE'`)
		if err != nil {
			return c.JSON(http.StatusOK, map[string]interface{}{"channels": []interface{}{}, "total_users": 0})
		}
		defer rows.Close()

		type chInfo struct {
			ChannelID   int64    `json:"channel_id"`
			ChannelName string   `json:"channel_name"`
			ServerName  string   `json:"server_name"`
			Users       []string `json:"users"`
		}
		var channels []chInfo
		totalUsers := 0

		for rows.Next() {
			var chID int64
			var chName, sName string
			if err := rows.Scan(&chID, &chName, &sName); err != nil {
				continue
			}
			participants, err := lkc.ListParticipants(ctx, lk.RoomName(fmt.Sprintf("%d", chID)))
			if err != nil || len(participants) == 0 {
				continue
			}
			var names []string
			for _, p := range participants {
				n := p.Name
				if n == "" {
					n = p.Identity
				}
				names = append(names, n)
			}
			channels = append(channels, chInfo{
				ChannelID: chID, ChannelName: chName, ServerName: sName, Users: names,
			})
			totalUsers += len(names)
		}
		if channels == nil {
			channels = []chInfo{}
		}
		return c.JSON(http.StatusOK, map[string]interface{}{"channels": channels, "total_users": totalUsers})
	}
}

// --- Helpers ---

// resolveHostID checks host mode state and clears it if host left the room.
func resolveHostID(roomName string, participants []*livekit.ParticipantInfo) string {
	hostModeMu.RLock()
	host := hostModeState[roomName]
	hostModeMu.RUnlock()
	if host == nil {
		return ""
	}
	for _, p := range participants {
		if p.Identity == host.UserID {
			return host.UserID
		}
	}
	// Host left
	hostModeMu.Lock()
	delete(hostModeState, roomName)
	hostModeMu.Unlock()
	return ""
}

// buildUserList constructs the user list response from LiveKit participants.
func buildUserList(ctx context.Context, lkc *lk.Client, ssoClient *sso.Client, roomName, hostID string, participants []*livekit.ParticipantInfo) []map[string]interface{} {
	var userIDs []int
	for _, p := range participants {
		if !strings.HasPrefix(p.Identity, "guest_") {
			if uid, err := strconv.Atoi(p.Identity); err == nil {
				userIDs = append(userIDs, uid)
			}
		}
	}
	avatarMap := ssoClient.GetAvatarURLsBatch(userIDs)

	users := make([]map[string]interface{}, 0, len(participants))
	for _, p := range participants {
		isMuted := true
		for _, t := range p.Tracks {
			if t.Source == livekit.TrackSource_MICROPHONE {
				isMuted = t.Muted
				break
			}
		}
		// Host mode enforcement
		if hostID != "" && p.Identity != hostID && !isMuted {
			lkc.MuteMicrophone(ctx, roomName, p.Identity, true)
			isMuted = true
		}

		name := p.Name
		if name == "" {
			name = p.Identity
		}
		var avatarURL interface{}
		if !strings.HasPrefix(p.Identity, "guest_") {
			if uid, err := strconv.Atoi(p.Identity); err == nil {
				if url, ok := avatarMap[uid]; ok {
					avatarURL = url
				}
			}
		}
		users = append(users, map[string]interface{}{
			"id": p.Identity, "name": name, "avatar_url": avatarURL,
			"is_muted": isMuted, "is_host": hostID == p.Identity,
		})
	}
	return users
}

// collectAllVoiceUsers gathers participants from all voice channels.
func collectAllVoiceUsers(ctx context.Context, lkc *lk.Client, ssoClient *sso.Client, db *sql.DB) map[string][]map[string]interface{} {
	rows, err := db.Query("SELECT id FROM channels WHERE type = 'VOICE'")
	if err != nil {
		return map[string][]map[string]interface{}{}
	}
	defer rows.Close()

	result := make(map[string][]map[string]interface{})
	for rows.Next() {
		var chID string
		if err := rows.Scan(&chID); err != nil {
			continue
		}
		roomName := lk.RoomName(chID)
		participants, err := lkc.ListParticipants(ctx, roomName)
		if err != nil || len(participants) == 0 {
			continue
		}
		hostID := resolveHostID(roomName, participants)
		result[chID] = buildUserList(ctx, lkc, ssoClient, roomName, hostID, participants)
	}
	return result
}

// broadcastVoiceUsersUpdate fetches all voice channel participants and broadcasts via global WS.
func broadcastVoiceUsersUpdate(lkc *lk.Client, ssoClient *sso.Client, db *sql.DB) {
	usersByChannel := collectAllVoiceUsers(context.Background(), lkc, ssoClient, db)
	GlobalStateManager.BroadcastToAllUsers(map[string]interface{}{
		"type":  "voice_users_update",
		"users": usersByChannel,
	})
	log.Printf("voice: broadcast voice_users_update (%d channels)", len(usersByChannel))
}
