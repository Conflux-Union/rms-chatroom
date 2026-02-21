package handler

import (
	"net/http"
	"sync"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
	"github.com/RMS-Server/rms-discord-go/internal/ws"
)

// RoomMusicState holds per-room playback state.
type RoomMusicState struct {
	mu                sync.Mutex
	RoomName          string
	PlayQueue         []queueItem
	CurrentIndex      int
	IsPlaying         bool
	PositionMS        int64
	PlayStartTime     float64
	PlayStartPosition int64
	CurrentSongURL    string
}

type queueItem struct {
	Song        songInfo `json:"song"`
	RequestedBy string   `json:"requested_by"`
}

type songInfo struct {
	Mid      string `json:"mid"`
	Name     string `json:"name"`
	Artist   string `json:"artist"`
	Album    string `json:"album"`
	Duration int    `json:"duration"`
	Cover    string `json:"cover"`
	Platform string `json:"platform"`
}

type searchRequest struct {
	Keyword  string `json:"keyword"`
	Num      int    `json:"num"`
	Platform string `json:"platform"`
}

type queueAddRequest struct {
	RoomName string   `json:"room_name"`
	Song     songInfo `json:"song"`
}

type roomRequest struct {
	RoomName string `json:"room_name"`
}

type seekRequest struct {
	RoomName   string `json:"room_name"`
	PositionMS int64  `json:"position_ms"`
}

var (
	roomStates   = make(map[string]*RoomMusicState)
	roomStatesMu sync.RWMutex
)

func getRoomState(roomName string) *RoomMusicState {
	roomStatesMu.Lock()
	defer roomStatesMu.Unlock()
	if s, ok := roomStates[roomName]; ok {
		return s
	}
	s := &RoomMusicState{RoomName: roomName}
	roomStates[roomName] = s
	return s
}

func init() {
	// Wire GetRoomPlaybackState for late-joiner support in ws/music.go
	ws.GetRoomPlaybackState = func(roomName string) map[string]interface{} {
		roomStatesMu.RLock()
		s, ok := roomStates[roomName]
		roomStatesMu.RUnlock()
		if !ok || !s.IsPlaying || s.CurrentSongURL == "" {
			return nil
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		if len(s.PlayQueue) == 0 || s.CurrentIndex >= len(s.PlayQueue) {
			return nil
		}
		elapsed := int64((float64(time.Now().UnixMilli())/1000.0 - s.PlayStartTime) * 1000)
		pos := s.PlayStartPosition + elapsed
		cur := s.PlayQueue[s.CurrentIndex]
		return map[string]interface{}{
			"room_name":   roomName,
			"song":        cur.Song,
			"url":         s.CurrentSongURL,
			"position_ms": pos,
			"is_playing":  true,
		}
	}
}

// RegisterMusicRoutes registers all /api/music routes.
func RegisterMusicRoutes(g *echo.Group, ssoClient *sso.Client) {
	g.GET("/login/qrcode", musicLoginQRCode())
	g.GET("/login/status", musicLoginStatus())
	g.GET("/login/check", musicLoginCheck())
	g.GET("/login/check/all", musicLoginCheckAll())
	g.POST("/login/logout", musicLogout())
	g.POST("/search", musicSearch(ssoClient))
	g.GET("/song/:mid/url", musicSongURL())
	g.POST("/queue/add", musicQueueAdd(ssoClient))
	g.DELETE("/queue/:room_name/:index", musicQueueRemove(ssoClient))
	g.GET("/queue/:room_name", musicQueueGet(ssoClient))
	g.POST("/queue/clear", musicQueueClear(ssoClient))
	g.POST("/bot/start", musicBotStart(ssoClient))
	g.POST("/bot/stop", musicBotStop(ssoClient))
	g.GET("/bot/status/:room_name", musicBotStatus(ssoClient))
	g.POST("/bot/play", musicBotPlay(ssoClient))
	g.POST("/bot/pause", musicBotPause(ssoClient))
	g.POST("/bot/resume", musicBotResume(ssoClient))
	g.POST("/bot/skip", musicBotSkip(ssoClient))
	g.POST("/bot/previous", musicBotPrevious(ssoClient))
	g.POST("/bot/seek", musicBotSeek(ssoClient))
	g.GET("/bot/progress/:room_name", musicBotProgress(ssoClient))
}

// Stub: QQ/NetEase APIs not available in Go
func musicLoginQRCode() echo.HandlerFunc {
	return func(c echo.Context) error {
		return c.JSON(http.StatusNotImplemented, map[string]string{
			"error": "music login not implemented in Go backend; requires QQ/NetEase API libraries",
		})
	}
}

func musicLoginStatus() echo.HandlerFunc {
	return func(c echo.Context) error {
		return c.JSON(http.StatusNotImplemented, map[string]string{"error": "not implemented"})
	}
}

func musicLoginCheck() echo.HandlerFunc {
	return func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]interface{}{"logged_in": false, "platform": "qq"})
	}
}

func musicLoginCheckAll() echo.HandlerFunc {
	return func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]interface{}{
			"qq":      map[string]interface{}{"logged_in": false},
			"netease": map[string]interface{}{"logged_in": false},
		})
	}
}

func musicLogout() echo.HandlerFunc {
	return func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true})
	}
}

func musicSearch(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		return c.JSON(http.StatusNotImplemented, map[string]string{
			"error": "music search not implemented; requires QQ/NetEase API libraries",
		})
	}
}

func musicSongURL() echo.HandlerFunc {
	return func(c echo.Context) error {
		return c.JSON(http.StatusNotImplemented, map[string]string{
			"error": "song URL retrieval not implemented; requires QQ/NetEase API libraries",
		})
	}
}

func musicQueueAdd(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateFromEcho(c, ssoClient)
		if err != nil {
			return err
		}
		var req queueAddRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		state.PlayQueue = append(state.PlayQueue, queueItem{
			Song:        req.Song,
			RequestedBy: user.Username,
		})
		pos := len(state.PlayQueue)
		state.mu.Unlock()
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true, "position": pos, "room_name": req.RoomName,
		})
	}
}

func musicQueueRemove(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		roomName := c.Param("room_name")
		var index int
		if err := echo.PathParamsBinder(c).Int("index", &index).BindError(); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid index"})
		}
		state := getRoomState(roomName)
		state.mu.Lock()
		defer state.mu.Unlock()
		if index < 0 || index >= len(state.PlayQueue) {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid index"})
		}
		state.PlayQueue = append(state.PlayQueue[:index], state.PlayQueue[index+1:]...)
		if index < state.CurrentIndex {
			state.CurrentIndex--
		} else if index == state.CurrentIndex && state.CurrentIndex >= len(state.PlayQueue) {
			state.CurrentIndex = max(0, len(state.PlayQueue)-1)
		}
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true, "room_name": roomName})
	}
}

func musicQueueGet(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		roomName := c.Param("room_name")
		state := getRoomState(roomName)
		state.mu.Lock()
		defer state.mu.Unlock()
		var currentSong *songInfo
		if len(state.PlayQueue) > 0 && state.CurrentIndex < len(state.PlayQueue) {
			s := state.PlayQueue[state.CurrentIndex].Song
			currentSong = &s
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"room_name":     roomName,
			"is_playing":    state.IsPlaying,
			"current_song":  currentSong,
			"current_index": state.CurrentIndex,
			"queue":         state.PlayQueue,
		})
	}
}

func musicQueueClear(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		state.IsPlaying = false
		state.PlayQueue = nil
		state.CurrentIndex = 0
		state.mu.Unlock()
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true, "room_name": req.RoomName})
	}
}

func musicBotStart(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		getRoomState(req.RoomName)
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true, "room": req.RoomName})
	}
}

func musicBotStop(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		roomStatesMu.Lock()
		delete(roomStates, req.RoomName)
		roomStatesMu.Unlock()
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true, "room_name": req.RoomName})
	}
}

func musicBotStatus(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		roomName := c.Param("room_name")
		roomStatesMu.RLock()
		s, ok := roomStates[roomName]
		roomStatesMu.RUnlock()
		resp := map[string]interface{}{
			"connected":    ok,
			"room":         roomName,
			"is_playing":   false,
			"queue_length": 0,
		}
		if ok {
			s.mu.Lock()
			resp["is_playing"] = s.IsPlaying
			resp["queue_length"] = len(s.PlayQueue)
			s.mu.Unlock()
		}
		return c.JSON(http.StatusOK, resp)
	}
}

func musicBotPlay(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		defer state.mu.Unlock()
		if len(state.PlayQueue) == 0 || state.CurrentIndex >= len(state.PlayQueue) {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "no song in queue"})
		}
		// Stub: actual playback requires song URL fetching from QQ/NetEase
		return c.JSON(http.StatusNotImplemented, map[string]string{
			"error": "playback not implemented; requires QQ/NetEase API libraries",
		})
	}
}

func musicBotPause(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		if state.IsPlaying {
			now := float64(time.Now().UnixMilli()) / 1000.0
			elapsed := int64((now - state.PlayStartTime) * 1000)
			state.PositionMS = state.PlayStartPosition + elapsed
			state.IsPlaying = false
		}
		state.mu.Unlock()
		broadcastMusicCommand("pause", map[string]interface{}{"room_name": req.RoomName})
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true, "room_name": req.RoomName})
	}
}

func musicBotResume(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		if !state.IsPlaying && state.CurrentSongURL != "" {
			state.IsPlaying = true
			state.PlayStartTime = float64(time.Now().UnixMilli()) / 1000.0
			state.PlayStartPosition = state.PositionMS
			state.mu.Unlock()
			broadcastMusicCommand("resume", map[string]interface{}{
				"room_name":   req.RoomName,
				"position_ms": state.PositionMS,
			})
			return c.JSON(http.StatusOK, map[string]interface{}{
				"success": true, "is_playing": true, "room_name": req.RoomName,
			})
		}
		state.mu.Unlock()
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": false, "message": "no player", "room_name": req.RoomName,
		})
	}
}

func musicBotSkip(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		defer state.mu.Unlock()
		if state.CurrentIndex < len(state.PlayQueue)-1 {
			state.CurrentIndex++
			return c.JSON(http.StatusOK, map[string]interface{}{
				"success": true, "current_index": state.CurrentIndex, "room_name": req.RoomName,
			})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": false, "message": "no more songs in queue", "room_name": req.RoomName,
		})
	}
}

func musicBotPrevious(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		defer state.mu.Unlock()
		if state.CurrentIndex > 0 {
			state.CurrentIndex--
			return c.JSON(http.StatusOK, map[string]interface{}{
				"success": true, "current_index": state.CurrentIndex, "room_name": req.RoomName,
			})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": false, "message": "already at first song", "room_name": req.RoomName,
		})
	}
}

func musicBotSeek(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		var req seekRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		state.PositionMS = req.PositionMS
		state.PlayStartTime = float64(time.Now().UnixMilli()) / 1000.0
		state.PlayStartPosition = req.PositionMS
		state.mu.Unlock()
		broadcastMusicCommand("seek", map[string]interface{}{
			"room_name":   req.RoomName,
			"position_ms": req.PositionMS,
		})
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true, "position_ms": req.PositionMS, "room_name": req.RoomName,
		})
	}
}

func musicBotProgress(ssoClient *sso.Client) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}
		roomName := c.Param("room_name")
		state := getRoomState(roomName)
		state.mu.Lock()
		defer state.mu.Unlock()

		var currentSong *songInfo
		if len(state.PlayQueue) > 0 && state.CurrentIndex < len(state.PlayQueue) {
			s := state.PlayQueue[state.CurrentIndex].Song
			currentSong = &s
		}
		posMS := state.PositionMS
		if state.IsPlaying && state.PlayStartTime > 0 {
			now := float64(time.Now().UnixMilli()) / 1000.0
			elapsed := int64((now - state.PlayStartTime) * 1000)
			posMS = state.PlayStartPosition + elapsed
		}
		durationMS := 0
		if currentSong != nil {
			durationMS = currentSong.Duration * 1000
		}
		playState := "paused"
		if state.IsPlaying {
			playState = "playing"
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"room_name":    roomName,
			"position_ms":  posMS,
			"duration_ms":  durationMS,
			"state":        playState,
			"current_song": currentSong,
		})
	}
}

func broadcastMusicCommand(eventType string, data map[string]interface{}) {
	roomName, _ := data["room_name"].(string)
	if roomName == "" {
		return
	}
	data["type"] = eventType
	data["server_time"] = float64(time.Now().UnixMilli()) / 1000.0
	ws.GetMusicRoomManager().BroadcastToRoom(roomName, data)
}

func authenticateFromEcho(c echo.Context, ssoClient *sso.Client) (*permission.UserInfo, error) {
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
