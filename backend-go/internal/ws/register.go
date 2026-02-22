package ws

import (
	"database/sql"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	"github.com/RMS-Server/rms-discord-go/internal/lk"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

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
}

// Shutdown stops all heartbeat monitors. Call on server shutdown.
func Shutdown() {
	ChatManager.StopHeartbeat()
	VoiceManager.StopHeartbeat()
	GlobalStateManager.StopHeartbeat()
}
