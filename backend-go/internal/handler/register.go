package handler

import (
	"database/sql"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	mw "github.com/RMS-Server/rms-discord-go/internal/middleware"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

// Register registers all HTTP API routes.
func Register(e *echo.Echo, cfg *config.Config, db *sql.DB, ssoClient *sso.Client) {
	authH := NewAuthHandler(db, ssoClient, cfg)
	serverH := NewServerHandler(db, ssoClient)
	channelH := NewChannelHandler(db, ssoClient)
	groupH := NewChannelGroupHandler(db, ssoClient)
	msgH := NewMessageHandler(db, ssoClient)
	reactionH := NewReactionHandler(db, ssoClient)
	readPosH := NewReadPositionHandler(db, ssoClient)

	authMiddleware := mw.Auth(ssoClient)
	adminMiddleware := mw.RequireAdmin()

	// Auth routes (no auth required for login/callback/refresh/dev-login)
	auth := e.Group("/api/auth")
	auth.GET("/login", authH.Login)
	auth.GET("/callback", authH.Callback)
	auth.POST("/refresh", authH.Refresh)
	auth.GET("/dev-login", authH.DevLogin)
	auth.GET("/me", authH.Me, authMiddleware)
	auth.POST("/logout", authH.Logout, authMiddleware)
	auth.POST("/revoke", authH.Logout, authMiddleware)

	// Server routes
	servers := e.Group("/api/servers", authMiddleware)
	servers.GET("", serverH.ListServers)
	servers.POST("", serverH.CreateServer, adminMiddleware)
	servers.GET("/:id", serverH.GetServer)
	servers.PATCH("/:id", serverH.UpdateServer, adminMiddleware)
	servers.PUT("/:id", serverH.UpdateServer, adminMiddleware)
	servers.DELETE("/:id", serverH.DeleteServer, adminMiddleware)
	servers.POST("/:id/reorder", serverH.ReorderTopLevel, adminMiddleware)
	servers.GET("/:id/all-messages", serverH.GetAllMessages)

	// Channel routes (under servers)
	servers.GET("/:server_id/channels", channelH.ListChannels)
	servers.POST("/:server_id/channels", channelH.CreateChannel, adminMiddleware)

	// Channel routes (standalone for update/delete)
	channels := e.Group("/api/channels", authMiddleware)
	channels.PATCH("/:id", channelH.UpdateChannel, adminMiddleware)
	channels.DELETE("/:id", channelH.DeleteChannel, adminMiddleware)

	// Channel group routes
	servers.GET("/:server_id/channel-groups", groupH.ListChannelGroups)
	servers.POST("/:server_id/channel-groups", groupH.CreateChannelGroup, adminMiddleware)
	servers.PATCH("/:server_id/channel-groups/:id", groupH.UpdateChannelGroup, adminMiddleware)
	servers.POST("/:server_id/channel-groups/:id/reorder-channels", groupH.ReorderGroupChannels, adminMiddleware)
	servers.DELETE("/:server_id/channel-groups/:id", groupH.DeleteChannelGroup, adminMiddleware)

	// Message routes
	channels.GET("/:channel_id/messages", msgH.GetMessages)
	channels.POST("/:channel_id/messages", msgH.CreateMessage)
	channels.GET("/:channel_id/messages/members", msgH.GetChannelMembers)
	channels.PATCH("/:channel_id/messages/:id", msgH.EditMessage)
	channels.DELETE("/:channel_id/messages/:id", msgH.DeleteMessage)

	// Reaction routes
	messages := e.Group("/api/messages", authMiddleware)
	messages.POST("/:message_id/reactions", reactionH.AddReaction)
	messages.DELETE("/:message_id/reactions/:emoji", reactionH.RemoveReaction)
	messages.GET("/:message_id/reactions", reactionH.GetReactions)

	// Read position routes
	e.GET("/api/read-positions", readPosH.GetAllReadPositions, authMiddleware)

	// System routes
	systemGroup := e.Group("/api/system")
	RegisterSystemRoutes(systemGroup, cfg)

	// Music routes
	musicGroup := e.Group("/api/music")
	RegisterMusicRoutes(musicGroup, ssoClient)

	// Moderation routes
	muteGroup := e.Group("/api/mute")
	RegisterModerationRoutes(muteGroup, ssoClient, db)

	// File routes
	RegisterFileRoutes(e, ssoClient, db, "uploads")

	// Bug report routes
	bugGroup := e.Group("/api/bug")
	RegisterBugReportRoutes(bugGroup, "bug_reports")
}
