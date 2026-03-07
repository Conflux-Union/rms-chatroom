package handler

import (
	"encoding/base64"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/jwtutil"
	"github.com/RMS-Server/rms-discord-go/internal/music"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/ws"
)

// Music API clients
var (
	qqClient      *music.QQMusicClient
	neteaseClient *music.NeteaseClient
	neteaseUnikey   string // stored unikey for QR login flow
	neteaseUnikeyMu sync.Mutex
)

// Login poller cancellation (one per platform)
var (
	loginPollers   = make(map[string]chan struct{})
	loginPollersMu sync.Mutex
)

// Progress timer management
var (
	progressTimers   = make(map[string]chan struct{})
	progressTimersMu sync.Mutex
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
	// Initialize music clients
	qqClient = music.NewQQMusicClient("qq_credential.json")
	neteaseClient = music.NewNeteaseClient("netease_credential.json")

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

// ensureHTTPS converts http:// URLs to https://
func ensureHTTPS(u string) string {
	if strings.HasPrefix(u, "http://") {
		return "https://" + u[7:]
	}
	return u
}

// getSongURL fetches a playable URL from the appropriate platform client.
func getSongURL(mid, platform string) (string, error) {
	var u string
	var err error
	switch platform {
	case "netease":
		u, err = neteaseClient.GetSongURL(mid)
	default:
		u, err = qqClient.GetSongURL(mid)
	}
	if err != nil {
		return "", err
	}
	return ensureHTTPS(u), nil
}

// songFetchResult holds the result of async song URL fetch.
type songFetchResult struct {
	songURL string
	err     error
	cur     queueItem
	index   int
}

// playCurrentSongAsync fetches song URL and starts playback asynchronously.
// This avoids blocking the state mutex during HTTP requests.
func playCurrentSongAsync(state *RoomMusicState, roomName string) {
	state.mu.Lock()
	if len(state.PlayQueue) == 0 || state.CurrentIndex >= len(state.PlayQueue) {
		state.mu.Unlock()
		return
	}
	cur := state.PlayQueue[state.CurrentIndex]
	index := state.CurrentIndex
	state.mu.Unlock()

	// Fetch URL outside the lock to avoid blocking
	go func() {
		songURL, err := getSongURL(cur.Song.Mid, cur.Song.Platform)

		state.mu.Lock()

		// Check if queue changed during fetch
		if len(state.PlayQueue) == 0 || state.CurrentIndex >= len(state.PlayQueue) {
			state.mu.Unlock()
			return
		}
		// Check if we're still on the same song
		if state.CurrentIndex != index {
			state.mu.Unlock()
			return
		}

		if err != nil || songURL == "" {
			log.Printf("music: failed to get URL for %s/%s: %v", cur.Song.Platform, cur.Song.Mid, err)
			broadcastMusicCommand("song_unavailable", map[string]interface{}{
				"room_name": state.RoomName,
				"song":      cur.Song,
				"error":     "failed to get song URL",
			})
			// Try next song
			if state.CurrentIndex < len(state.PlayQueue)-1 {
				state.CurrentIndex++
				state.mu.Unlock()
				playCurrentSongAsync(state, roomName)
				return
			}
			state.IsPlaying = false
			state.CurrentSongURL = ""
			stopProgressTimer(state.RoomName)
			state.mu.Unlock()
			return
		}

		state.IsPlaying = true
		state.CurrentSongURL = songURL
		state.PlayStartTime = float64(time.Now().UnixMilli()) / 1000.0
		state.PlayStartPosition = 0
		state.PositionMS = 0

		broadcastMusicCommand("play", map[string]interface{}{
			"room_name":   state.RoomName,
			"song":        cur.Song,
			"url":         songURL,
			"position_ms": int64(0),
			"is_playing":  true,
		})

		state.mu.Unlock()
		startProgressTimer(state.RoomName)
	}()
}

// playNextSong advances to the next song and plays it.
// Must NOT hold state.mu when calling this (HTTP request involved).
func playNextSong(state *RoomMusicState, roomName string) {
	stopProgressTimer(roomName)
	state.mu.Lock()
	if state.CurrentIndex < len(state.PlayQueue)-1 {
		state.CurrentIndex++
		state.mu.Unlock()
		playCurrentSongAsync(state, roomName)
	} else {
		state.IsPlaying = false
		state.CurrentSongURL = ""
		state.mu.Unlock()
		broadcastMusicCommand("queue_finished", map[string]interface{}{
			"room_name": roomName,
		})
	}
}

func startProgressTimer(roomName string) {
	stopProgressTimer(roomName)
	progressTimersMu.Lock()
	stop := make(chan struct{})
	progressTimers[roomName] = stop
	progressTimersMu.Unlock()

	go func() {
		ticker := time.NewTicker(1 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				roomStatesMu.RLock()
				state, ok := roomStates[roomName]
				roomStatesMu.RUnlock()
				if !ok {
					return
				}
				state.mu.Lock()
				if !state.IsPlaying || len(state.PlayQueue) == 0 || state.CurrentIndex >= len(state.PlayQueue) {
					state.mu.Unlock()
					return
				}
				now := float64(time.Now().UnixMilli()) / 1000.0
				elapsed := int64((now - state.PlayStartTime) * 1000)
				pos := state.PlayStartPosition + elapsed
				cur := state.PlayQueue[state.CurrentIndex]
				durationMS := int64(cur.Song.Duration) * 1000

				if durationMS > 0 && pos >= durationMS {
					state.mu.Unlock()
					// Call playNextSong without lock (it does HTTP request)
					playNextSong(state, roomName)
					return
				}
				state.mu.Unlock()
			}
		}
	}()
}

func stopProgressTimer(roomName string) {
	progressTimersMu.Lock()
	defer progressTimersMu.Unlock()
	if ch, ok := progressTimers[roomName]; ok {
		close(ch)
		delete(progressTimers, roomName)
	}
}

// RegisterMusicRoutes registers all /api/music routes.
func RegisterMusicRoutes(g *echo.Group, jwtSecret string) {
	g.GET("/login/qrcode", musicLoginQRCode())
	g.GET("/login/status", musicLoginStatus())
	g.GET("/login/check", musicLoginCheck())
	g.GET("/login/check/all", musicLoginCheckAll())
	g.POST("/login/logout", musicLogout())
	g.POST("/search", musicSearch(jwtSecret))
	g.GET("/song/:mid/url", musicSongURL())
	g.POST("/queue/add", musicQueueAdd(jwtSecret))
	g.DELETE("/queue/:room_name/:index", musicQueueRemove(jwtSecret))
	g.GET("/queue/:room_name", musicQueueGet(jwtSecret))
	g.POST("/queue/clear", musicQueueClear(jwtSecret))
	g.POST("/bot/start", musicBotStart(jwtSecret))
	g.POST("/bot/stop", musicBotStop(jwtSecret))
	g.GET("/bot/status/:room_name", musicBotStatus(jwtSecret))
	g.POST("/bot/play", musicBotPlay(jwtSecret))
	g.POST("/bot/pause", musicBotPause(jwtSecret))
	g.POST("/bot/resume", musicBotResume(jwtSecret))
	g.POST("/bot/skip", musicBotSkip(jwtSecret))
	g.POST("/bot/previous", musicBotPrevious(jwtSecret))
	g.POST("/bot/seek", musicBotSeek(jwtSecret))
	g.GET("/bot/progress/:room_name", musicBotProgress(jwtSecret))
}

func musicLoginQRCode() echo.HandlerFunc {
	return func(c echo.Context) error {
		platform := c.QueryParam("platform")
		if platform == "netease" {
			unikey, err := neteaseClient.GetQRKey()
			if err != nil {
				return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
			}
			neteaseUnikeyMu.Lock()
			neteaseUnikey = unikey
			neteaseUnikeyMu.Unlock()
			qrURL := neteaseClient.GetQRCodeURL(unikey)
			png, err := qrcode.Encode(qrURL, qrcode.Medium, 256)
			if err != nil {
				return c.JSON(http.StatusInternalServerError, map[string]string{"error": "qr encode: " + err.Error()})
			}
			encoded := "data:image/png;base64," + base64.StdEncoding.EncodeToString(png)
			startLoginPoller("netease")
			return c.JSON(http.StatusOK, map[string]interface{}{
				"qrcode":   encoded,
				"platform": "netease",
			})
		}
		// QQ Music QR login
		loginType := music.QQLoginType(c.QueryParam("login_type"))
		if loginType != music.QQLoginTypeWX {
			loginType = music.QQLoginTypeQQ
		}
		data, mimetype, err := qqClient.GetQRCode(loginType)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		encoded := "data:" + mimetype + ";base64," + base64.StdEncoding.EncodeToString(data)
		startLoginPoller("qq")
		return c.JSON(http.StatusOK, map[string]interface{}{
			"qrcode":   encoded,
			"platform": "qq",
		})
	}
}

// startLoginPoller cancels any existing poller for the platform and starts a new one.
func startLoginPoller(platform string) {
	loginPollersMu.Lock()
	if ch, ok := loginPollers[platform]; ok {
		close(ch)
	}
	stop := make(chan struct{})
	loginPollers[platform] = stop
	loginPollersMu.Unlock()
	go pollLoginAndBroadcast(platform, stop)
}

// pollLoginAndBroadcast polls QR login status and broadcasts via music WebSocket.
func pollLoginAndBroadcast(platform string, stop <-chan struct{}) {
	for i := 0; i < 90; i++ { // max ~3 minutes
		select {
		case <-stop:
			return
		case <-time.After(2 * time.Second):
		}
		var status string
		var err error
		if platform == "netease" {
			neteaseUnikeyMu.Lock()
			key := neteaseUnikey
			neteaseUnikeyMu.Unlock()
			if key == "" {
				return
			}
			status, err = neteaseClient.CheckQR(key)
		} else {
			status, err = qqClient.CheckQRStatus()
		}
		if err != nil {
			log.Printf("music login poll error (%s): %v", platform, err)
			broadcastLoginStatusWithError(platform, err)
			return
		}
		broadcastLoginStatus(platform, status)
		if status == "success" || status == "expired" || status == "refused" {
			if platform == "netease" {
				neteaseUnikeyMu.Lock()
				neteaseUnikey = ""
				neteaseUnikeyMu.Unlock()
			}
			return
		}
	}
	// Timed out
	broadcastLoginStatus(platform, "expired")
}

func broadcastLoginStatus(platform, status string) {
	ws.GetMusicRoomManager().BroadcastToAll(map[string]interface{}{
		"type":     "music_login_status",
		"platform": platform,
		"status":   status,
	})
}

func broadcastLoginStatusWithError(platform string, err error) {
	ws.GetMusicRoomManager().BroadcastToAll(map[string]interface{}{
		"type":     "music_login_status",
		"platform": platform,
		"status":   "error",
		"message":  err.Error(),
	})
}

func musicLoginStatus() echo.HandlerFunc {
	return func(c echo.Context) error {
		platform := c.QueryParam("platform")
		if platform == "netease" {
			neteaseUnikeyMu.Lock()
			key := neteaseUnikey
			neteaseUnikeyMu.Unlock()
			if key == "" {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "no active QR login session"})
			}
			status, err := neteaseClient.CheckQR(key)
			if err != nil {
				return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
			}
			if status == "success" || status == "expired" || status == "refused" {
				neteaseUnikeyMu.Lock()
				neteaseUnikey = ""
				neteaseUnikeyMu.Unlock()
			}
			return c.JSON(http.StatusOK, map[string]interface{}{
				"status":   status,
				"platform": "netease",
			})
		}
		// QQ Music
		status, err := qqClient.CheckQRStatus()
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		resp := map[string]interface{}{
			"status":   status,
			"platform": "qq",
		}
		if status == "success" {
			resp["logged_in"] = true
		}
		return c.JSON(http.StatusOK, resp)
	}
}

func musicLoginCheck() echo.HandlerFunc {
	return func(c echo.Context) error {
		platform := c.QueryParam("platform")
		if platform == "netease" {
			loggedIn, _ := neteaseClient.GetLoginStatus()
			return c.JSON(http.StatusOK, map[string]interface{}{
				"logged_in": loggedIn,
				"platform":  "netease",
			})
		}
		// Default to QQ
		return c.JSON(http.StatusOK, map[string]interface{}{
			"logged_in": qqClient.IsLoggedIn(),
			"platform":  "qq",
		})
	}
}

func musicLoginCheckAll() echo.HandlerFunc {
	return func(c echo.Context) error {
		neteaseLoggedIn, _ := neteaseClient.GetLoginStatus()
		return c.JSON(http.StatusOK, map[string]interface{}{
			"qq":      map[string]interface{}{"logged_in": qqClient.IsLoggedIn()},
			"netease": map[string]interface{}{"logged_in": neteaseLoggedIn},
		})
	}
}

func musicLogout() echo.HandlerFunc {
	return func(c echo.Context) error {
		platform := c.QueryParam("platform")
		switch platform {
		case "qq":
			os.Remove("qq_credential.json")
			qqClient = music.NewQQMusicClient("")
		case "netease":
			os.Remove("netease_credential.json")
			neteaseClient = music.NewNeteaseClient("")
		default:
			os.Remove("qq_credential.json")
			os.Remove("netease_credential.json")
			qqClient = music.NewQQMusicClient("")
			neteaseClient = music.NewNeteaseClient("")
		}
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true})
	}
}

func musicSearch(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		var req searchRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		if req.Keyword == "" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "keyword required"})
		}
		if req.Num <= 0 {
			req.Num = 10
		}

		var allResults []music.SongResult
		platform := req.Platform

		if platform == "" || platform == "all" || platform == "qq" {
			results, err := qqClient.SearchSongs(req.Keyword, req.Num)
			if err != nil {
				log.Printf("music: qq search error: %v", err)
			} else {
				allResults = append(allResults, results...)
			}
		}
		if platform == "" || platform == "all" || platform == "netease" {
			results, err := neteaseClient.SearchSongs(req.Keyword, req.Num)
			if err != nil {
				log.Printf("music: netease search error: %v", err)
			} else {
				allResults = append(allResults, results...)
			}
		}

		return c.JSON(http.StatusOK, map[string]interface{}{
			"songs": allResults,
		})
	}
}

func musicSongURL() echo.HandlerFunc {
	return func(c echo.Context) error {
		mid := c.Param("mid")
		platform := c.QueryParam("platform")
		if platform == "" {
			platform = "qq"
		}
		songURL, err := getSongURL(mid, platform)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		if songURL == "" {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "no URL found"})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{"url": songURL})
	}
}

func musicQueueAdd(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateFromEcho(c, jwtSecret)
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

func musicQueueRemove(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
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

func musicQueueGet(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
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

func musicQueueClear(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		stopProgressTimer(req.RoomName)
		state.mu.Lock()
		state.IsPlaying = false
		state.PlayQueue = nil
		state.CurrentIndex = 0
		state.CurrentSongURL = ""
		state.mu.Unlock()
		broadcastMusicCommand("stop", map[string]interface{}{"room_name": req.RoomName})
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true, "room_name": req.RoomName})
	}
}

func musicBotStart(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
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

func musicBotStop(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		stopProgressTimer(req.RoomName)
		roomStatesMu.Lock()
		delete(roomStates, req.RoomName)
		roomStatesMu.Unlock()
		return c.JSON(http.StatusOK, map[string]interface{}{"success": true, "room_name": req.RoomName})
	}
}

func musicBotStatus(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
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

func musicBotPlay(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		if len(state.PlayQueue) == 0 || state.CurrentIndex >= len(state.PlayQueue) {
			state.mu.Unlock()
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "no song in queue"})
		}
		roomName := state.RoomName
		state.mu.Unlock()
		// Use async version to avoid blocking on HTTP request
		playCurrentSongAsync(state, roomName)
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true, "message": "playback started", "room_name": req.RoomName,
		})
	}
}

func musicBotPause(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		stopProgressTimer(req.RoomName)
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

func musicBotResume(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
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
			posMS := state.PositionMS
			state.mu.Unlock()
			startProgressTimer(req.RoomName)
			broadcastMusicCommand("resume", map[string]interface{}{
				"room_name":   req.RoomName,
				"position_ms": posMS,
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

func musicBotSkip(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		if state.CurrentIndex < len(state.PlayQueue)-1 {
			stopProgressTimer(req.RoomName)
			state.CurrentIndex++
			newIndex := state.CurrentIndex
			roomName := state.RoomName
			state.mu.Unlock()
			// Use async version to avoid blocking on HTTP request
			playCurrentSongAsync(state, roomName)
			return c.JSON(http.StatusOK, map[string]interface{}{
				"success": true, "current_index": newIndex, "room_name": req.RoomName,
			})
		}
		state.mu.Unlock()
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": false, "message": "no more songs in queue", "room_name": req.RoomName,
		})
	}
}

func musicBotPrevious(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		var req roomRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		if state.CurrentIndex > 0 {
			stopProgressTimer(req.RoomName)
			state.CurrentIndex--
			newIndex := state.CurrentIndex
			roomName := state.RoomName
			state.mu.Unlock()
			// Use async version to avoid blocking on HTTP request
			playCurrentSongAsync(state, roomName)
			return c.JSON(http.StatusOK, map[string]interface{}{
				"success": true, "current_index": newIndex, "room_name": req.RoomName,
			})
		}
		state.mu.Unlock()
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": false, "message": "already at first song", "room_name": req.RoomName,
		})
	}
}

func musicBotSeek(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
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

func musicBotProgress(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
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

func authenticateFromEcho(c echo.Context, jwtSecret string) (*permission.UserInfo, error) {
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
	user, err := jwtutil.ParseToken(token, jwtSecret)
	if err != nil {
		return nil, c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid token"})
	}
	return user, nil
}
