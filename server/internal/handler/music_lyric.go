package handler

import (
	"net/http"
	"sync"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/metrics"
	"github.com/RMS-Server/rms-discord-go/internal/music"
)

// Lyrics are immutable per song, so a bounded FIFO cache spares the provider
// round trips on every song change and client join.
const lyricCacheMax = 1000

var (
	lyricCache      = make(map[string]*music.LyricResult)
	lyricCacheOrder []string
	lyricCacheMu    sync.Mutex
)

func cachedLyric(key string) (*music.LyricResult, bool) {
	lyricCacheMu.Lock()
	defer lyricCacheMu.Unlock()
	res, ok := lyricCache[key]
	return res, ok
}

func cacheLyric(key string, res *music.LyricResult) {
	lyricCacheMu.Lock()
	defer lyricCacheMu.Unlock()
	if _, exists := lyricCache[key]; exists {
		return
	}
	lyricCache[key] = res
	lyricCacheOrder = append(lyricCacheOrder, key)
	if len(lyricCacheOrder) > lyricCacheMax {
		evict := lyricCacheOrder[0]
		lyricCacheOrder = lyricCacheOrder[1:]
		delete(lyricCache, evict)
	}
}

// getSongLyric fetches lyrics for a song through the platform client, using
// and filling the cache. Empty results are cached too ("no lyrics" is stable).
func getSongLyric(platform, mid string) (*music.LyricResult, error) {
	key := platform + "/" + mid
	if res, ok := cachedLyric(key); ok {
		return res, nil
	}

	var res *music.LyricResult
	var err error
	switch platform {
	case "netease":
		res, err = neteaseClient.GetLyric(mid)
	default:
		res, err = qqClient.GetLyric(mid)
	}
	if err != nil {
		metrics.MusicProviderErrors.WithLabelValues(platform, "lyric").Inc()
		return nil, err
	}
	cacheLyric(key, res)
	return res, nil
}

func musicSongLyric(jwtSecret string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}
		mid := c.Param("mid")
		platform := c.QueryParam("platform")
		if platform == "" {
			platform = "qq"
		}
		res, err := getSongLyric(platform, mid)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		return c.JSON(http.StatusOK, res)
	}
}
