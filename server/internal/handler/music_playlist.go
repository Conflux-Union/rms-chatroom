package handler

import (
	"log"
	"net/http"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/music"
	mw "github.com/RMS-Server/rms-discord-go/internal/middleware"
)

// Playlist / recommendation sources. Clients only send an identifier (a
// playlist id or a feed kind); the backend resolves it into songs so the
// queue protocol stays list-free on the client side.

const (
	defaultPlaylistImport = 50
	maxPlaylistImport     = 300
	defaultRadioCount     = 10
	maxRadioCount         = 100
	playlistPreviewCount  = 50
	// Backoff after a failed radio fetch so the extension hook (which can
	// fire on every song start) does not hammer a failing provider.
	radioRetryBackoff = 30 * time.Second
)

type playlistSearchRequest struct {
	Keyword  string `json:"keyword"`
	Platform string `json:"platform"`
	Num      int    `json:"num"`
}

type queueAddSourceRequest struct {
	RoomName string `json:"room_name"`
	Platform string `json:"platform"` // "qq" or "netease"
	Kind     string `json:"kind"`     // "playlist", "daily", "radio"
	ID       string `json:"id"`       // playlist id; empty for feed kinds
	Limit    int    `json:"limit"`    // song cap for playlist/radio imports
}

type radioRequest struct {
	RoomName string `json:"room_name"`
	Platform string `json:"platform"`
}

// broadcastQueueUpdated nudges every room member to refetch the queue after a
// mutation performed outside their own client (batch import, radio batch).
func broadcastQueueUpdated(roomName string) {
	broadcastMusicCommand("queue_updated", map[string]interface{}{"room_name": roomName})
}

func appendSongsToQueue(state *RoomMusicState, songs []music.SongResult, requestedBy string) {
	state.mu.Lock()
	for _, sr := range songs {
		state.PlayQueue = append(state.PlayQueue, queueItem{
			Song:        songInfoFromResult(sr),
			RequestedBy: requestedBy,
		})
	}
	state.mu.Unlock()

	// Loudness pre-analysis is deduped and semaphore-bounded internally.
	for _, sr := range songs {
		go analyzeSongLoudness(sr.Platform, sr.Mid)
	}
}

// extendRadioQueue appends a fresh radio batch when the room's radio is on
// and the current song is the last unplayed one. Only sequence mode extends:
// in loop modes the queue never runs out, so appending would grow it forever.
func extendRadioQueue(state *RoomMusicState) {
	state.mu.Lock()
	platform := state.RadioPlatform
	requester := state.RadioRequester
	remaining := len(state.PlayQueue) - state.CurrentIndex
	need := platform != "" && remaining <= 1 &&
		(state.LoopMode == "" || state.LoopMode == loopModeSequence) &&
		time.Now().After(state.RadioRetryAt)
	state.mu.Unlock()
	if !need {
		return
	}

	songs, err := resolveSourceSongs(platform, "radio", "", defaultRadioCount)
	if err != nil {
		log.Printf("music: radio fetch failed for room %s: %v", state.RoomName, err)
		state.mu.Lock()
		state.RadioRetryAt = time.Now().Add(radioRetryBackoff)
		state.mu.Unlock()
		return
	}

	state.mu.Lock()
	// Re-check under the lock: radio may have been stopped or the queue
	// extended while the fetch was in flight.
	platform = state.RadioPlatform
	remaining = len(state.PlayQueue) - state.CurrentIndex
	if platform == "" || remaining > 1 {
		state.mu.Unlock()
		return
	}
	// Radio batches re-roll per call and can repeat tracks the room already
	// queued; drop those instead of replaying them.
	existing := make(map[string]bool, len(state.PlayQueue))
	for _, item := range state.PlayQueue {
		existing[item.Song.Platform+"/"+item.Song.Mid] = true
	}
	fresh := songs[:0]
	for _, sr := range songs {
		if !existing[sr.Platform+"/"+sr.Mid] {
			fresh = append(fresh, sr)
		}
	}
	state.mu.Unlock()

	if len(fresh) == 0 {
		return
	}
	appendSongsToQueue(state, fresh, requester)
	broadcastQueueUpdated(state.RoomName)
}

// musicRadioStart enables radio auto-extension for a room and queues the
// first batch immediately.
func musicRadioStart(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateFromEcho(c, jwtSecret)
		if err != nil {
			return err
		}
		var req radioRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		if req.RoomName == "" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "room_name required"})
		}
		if req.Platform != "qq" && req.Platform != "netease" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid platform"})
		}
		loggedIn := qqClient.IsLoggedIn()
		if req.Platform == "netease" {
			loggedIn = neteaseClient.HasSession()
		}
		if !loggedIn {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "login required"})
		}

		songs, err := resolveSourceSongs(req.Platform, "radio", "", defaultRadioCount)
		if err != nil {
			return c.JSON(http.StatusBadGateway, map[string]string{"error": err.Error()})
		}
		if len(songs) == 0 {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "no playable songs found"})
		}

		state := getRoomState(req.RoomName)
		state.mu.Lock()
		// Radio only extends in sequence mode (loop modes never run out of
		// songs); switch the room so the radio behaves as the user expects.
		prevLoopMode := state.LoopMode
		if prevLoopMode != "" && prevLoopMode != loopModeSequence {
			state.LoopMode = loopModeSequence
		}
		state.RadioPlatform = req.Platform
		state.RadioRequester = user.Username
		state.RadioRetryAt = time.Time{}
		state.mu.Unlock()
		if prevLoopMode != "" && prevLoopMode != loopModeSequence {
			broadcastMusicCommand("loop_mode", map[string]interface{}{
				"room_name": req.RoomName, "mode": loopModeSequence,
			})
		}
		appendSongsToQueue(state, songs, user.Username)
		broadcastQueueUpdated(req.RoomName)

		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true, "added": len(songs), "room_name": req.RoomName,
		})
	}
}

func musicRadioStop(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		var req radioRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		if req.RoomName == "" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "room_name required"})
		}
		state := getRoomState(req.RoomName)
		state.mu.Lock()
		state.RadioPlatform = ""
		state.RadioRequester = ""
		state.RadioRetryAt = time.Time{}
		state.mu.Unlock()
		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true, "room_name": req.RoomName,
		})
	}
}

func songInfoFromResult(sr music.SongResult) songInfo {
	return songInfo{
		Mid:      sr.Mid,
		Name:     sr.Name,
		Artist:   sr.Artist,
		Album:    sr.Album,
		Duration: sr.Duration,
		Cover:    sr.Cover,
		Platform: sr.Platform,
	}
}

// resolveSourceSongs turns a source identifier into queueable songs. Feed
// kinds (daily/radio) are re-resolved at queue time: the snapshot semantics
// match the rest of the queue, which is append-only.
func resolveSourceSongs(platform, kind, id string, limit int) ([]music.SongResult, error) {
	switch kind {
	case "playlist":
		if platform == "qq" {
			_, songs, err := qqClient.GetPlaylistDetail(id, limit)
			return songs, err
		}
		_, songs, err := neteaseClient.GetPlaylistDetail(id, limit)
		return songs, err
	case "daily":
		if platform == "qq" {
			return qqClient.GetDailyRecommendSongs()
		}
		return neteaseClient.GetDailyRecommendSongs()
	case "radio":
		if platform == "qq" {
			return qqClient.GetRadioSongs(limit)
		}
		return neteaseClient.GetRadioSongs(limit)
	default:
		return nil, echo.NewHTTPError(http.StatusBadRequest, "unsupported kind: "+kind)
	}
}

// musicRecommend returns a feed of recommended songs (daily mix or
// personalized radio). Both feeds belong to the server's bot account and
// require that platform's login.
func musicRecommend(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		platform := c.QueryParam("platform")
		kind := c.QueryParam("kind")
		if kind == "" {
			kind = "daily"
		}
		if kind != "daily" && kind != "radio" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid kind"})
		}
		if platform == "" {
			platform = "all"
		}

		count := defaultRadioCount
		if kind == "daily" {
			count = 0 // the daily feed has a fixed size
		}

		platforms := []string{"qq", "netease"}
		if platform != "all" {
			platforms = []string{platform}
		}
		results := make([]music.SongResult, 0)
		anyAvailable := false
		for _, p := range platforms {
			loggedIn := qqClient.IsLoggedIn()
			if p == "netease" {
				loggedIn = neteaseClient.HasSession()
			}
			if !loggedIn {
				continue
			}
			anyAvailable = true
			songs, err := resolveSourceSongs(p, kind, "", count)
			if err != nil {
				mw.ReqLogf(c, "music: %s %s recommend error: %v", p, kind, err)
				continue
			}
			results = append(results, songs...)
		}
		if !anyAvailable {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "login required"})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{"songs": results})
	}
}

func musicPlaylistSearch(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		var req playlistSearchRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		if req.Keyword == "" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "keyword required"})
		}
		if req.Num <= 0 {
			req.Num = 20
		}
		if req.Platform == "" {
			req.Platform = "all"
		}

		var playlists []music.PlaylistResult
		if req.Platform == "all" || req.Platform == "qq" {
			results, err := qqClient.SearchPlaylists(req.Keyword, req.Num)
			if err != nil {
				mw.ReqLogf(c, "music: qq playlist search error: %v", err)
			} else {
				playlists = append(playlists, results...)
			}
		}
		if req.Platform == "all" || req.Platform == "netease" {
			results, err := neteaseClient.SearchPlaylists(req.Keyword, req.Num)
			if err != nil {
				mw.ReqLogf(c, "music: netease playlist search error: %v", err)
			} else {
				playlists = append(playlists, results...)
			}
		}
		return c.JSON(http.StatusOK, map[string]interface{}{"playlists": playlists})
	}
}

// musicPlaylistDetail returns playlist metadata plus a preview of the first
// playlistPreviewCount songs. Full imports go through queue/add/source, which
// resolves tracks server-side without shipping the whole list to the client.
func musicPlaylistDetail(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		platform := c.Param("platform")
		id := c.Param("id")
		var playlist *music.PlaylistResult
		var songs []music.SongResult
		var err error
		if platform == "qq" {
			playlist, songs, err = qqClient.GetPlaylistDetail(id, playlistPreviewCount)
		} else if platform == "netease" {
			playlist, songs, err = neteaseClient.GetPlaylistDetail(id, playlistPreviewCount)
		} else {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid platform"})
		}
		if err != nil {
			return c.JSON(http.StatusBadGateway, map[string]string{"error": err.Error()})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"playlist": playlist,
			"songs":    songs,
			"total":    playlist.TrackCount,
		})
	}
}

func musicQueueAddSource(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateFromEcho(c, jwtSecret)
		if err != nil {
			return err
		}
		var req queueAddSourceRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}
		if req.RoomName == "" || req.Platform == "" || req.Kind == "" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "room_name, platform and kind required"})
		}
		if req.Platform != "qq" && req.Platform != "netease" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid platform"})
		}
		if req.Kind == "playlist" && req.ID == "" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "id required for playlist"})
		}

		limit := defaultPlaylistImport
		if req.Kind == "radio" {
			limit = defaultRadioCount
		}
		if req.Limit > 0 {
			limit = req.Limit
		}
		if limit > maxPlaylistImport {
			limit = maxPlaylistImport
		}
		if req.Kind == "radio" && limit > maxRadioCount {
			limit = maxRadioCount
		}

		songs, err := resolveSourceSongs(req.Platform, req.Kind, req.ID, limit)
		if err != nil {
			return c.JSON(http.StatusBadGateway, map[string]string{"error": err.Error()})
		}
		if len(songs) == 0 {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "no playable songs found"})
		}

		state := getRoomState(req.RoomName)
		appendSongsToQueue(state, songs, user.Username)
		broadcastQueueUpdated(req.RoomName)

		return c.JSON(http.StatusOK, map[string]interface{}{
			"success": true, "added": len(songs), "room_name": req.RoomName,
		})
	}
}
