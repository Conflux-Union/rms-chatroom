package ws

import (
	"database/sql"
	"sync"

	"github.com/labstack/echo/v4"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	"github.com/RMS-Server/rms-discord-go/internal/lk"
	"github.com/RMS-Server/rms-discord-go/internal/metrics"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

var registerGaugesOnce sync.Once

// registerConnectionGauges exposes live connection counts. Guarded by a Once
// because gauge registration panics on duplicates if Register runs twice.
func registerConnectionGauges() {
	registerGaugesOnce.Do(func() {
		for _, m := range []*ConnectionManager{ChatManager, VoiceManager, GlobalStateManager} {
			mgr := m
			metrics.RegisterGaugeFunc("rms_ws_connections", "Open WebSocket connections per hub.",
				prometheus.Labels{"hub": mgr.name}, func() float64 { return float64(mgr.ConnCount()) })
		}
		metrics.RegisterGaugeFunc("rms_ws_music_rooms", "Active music sync rooms.",
			nil, func() float64 { return float64(musicRooms.RoomCount()) })
		metrics.RegisterGaugeFunc("rms_ws_connections", "Open WebSocket connections per hub.",
			prometheus.Labels{"hub": "music"}, func() float64 { return float64(musicRooms.ConnCount()) })
	})
}

// Register registers all WebSocket routes and voice HTTP routes.
func Register(e *echo.Echo, cfg *config.Config, ssoClient *sso.Client, db *sql.DB) {
	e.GET("/ws/chat", HandleChatWS(cfg.JWTSecret, db))
	e.GET("/ws/global", HandleGlobalWS(cfg.JWTSecret, db))
	e.GET("/ws/voice", HandleVoiceWS(cfg.JWTSecret))
	e.GET("/ws/music", HandleMusicWS(cfg.JWTSecret))

	voiceGroup := e.Group("/api/voice")
	RegisterVoiceHTTP(voiceGroup, cfg.JWTSecret, ssoClient, db, cfg)

	// LiveKit webhook
	e.POST("/api/livekit/webhook", lk.WebhookHandler(cfg.LivekitAPIKey, cfg.LivekitAPISecret, func(eventType string) {
		lkc := lk.New(cfg)
		go broadcastVoiceUsersUpdate(lkc, ssoClient, db)
	}))

	// Start heartbeat monitors
	ChatManager.StartHeartbeat()
	VoiceManager.StartHeartbeat()
	GlobalStateManager.StartHeartbeat()

	registerConnectionGauges()
}

// Shutdown stops all heartbeat monitors. Call on server shutdown.
func Shutdown() {
	ChatManager.StopHeartbeat()
	VoiceManager.StopHeartbeat()
	GlobalStateManager.StopHeartbeat()
}
