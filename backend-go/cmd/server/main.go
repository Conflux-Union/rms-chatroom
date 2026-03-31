package main

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	"github.com/RMS-Server/rms-discord-go/internal/handler"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
	"github.com/RMS-Server/rms-discord-go/internal/ws"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("failed to load config: %v", err)
	}

	// Parse DSN from database_url (strip mysql:// prefix if present)
	dsn := cfg.DatabaseURL
	dsn = strings.TrimPrefix(dsn, "mysql://")
	dsn = strings.TrimPrefix(dsn, "mysql+aiomysql://")
	// Ensure parseTime and loc=UTC for correct time.Time scanning
	if !strings.Contains(dsn, "parseTime") {
		if strings.Contains(dsn, "?") {
			dsn += "&parseTime=true"
		} else {
			dsn += "?parseTime=true"
		}
	}
	if !strings.Contains(dsn, "loc=") {
		dsn += "&loc=UTC"
	}

	// Set session timezone to UTC so CURRENT_TIMESTAMP returns UTC
	// (MySQL 5.7 doesn't support UTC_TIMESTAMP() as column DEFAULT)
	if !strings.Contains(dsn, "time_zone=") {
		dsn += "&time_zone=%27%2B00%3A00%27"
	}

	db, err := sql.Open("mysql", dsn)
	if err != nil {
		log.Fatalf("failed to open database: %v", err)
	}
	defer db.Close()
	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(5 * time.Minute)

	ssoClient := sso.NewClient(cfg.SSOBaseURL)

	e := echo.New()
	e.HideBanner = true

	e.Use(middleware.Recover())
	e.Use(middleware.Logger())
	e.Use(middleware.CORSWithConfig(middleware.CORSConfig{
		AllowOrigins:     cfg.CORSOrigins,
		AllowMethods:     []string{http.MethodGet, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete, http.MethodOptions},
		AllowHeaders:     []string{echo.HeaderOrigin, echo.HeaderContentType, echo.HeaderAccept, echo.HeaderAuthorization},
		AllowCredentials: true,
	}))

	// Wire up BroadcastFunc so HTTP handlers can broadcast WS events.
	// Filter recipients by channel access permission; fall back to
	// unfiltered broadcast on transient DB errors to avoid silently
	// dropping events for messages already persisted.
	handler.BroadcastFunc = func(channelID int64, payload map[string]interface{}) {
		var minLevel, permMinLevel int
		var logicOp string
		err := db.QueryRow(
			"SELECT min_level, perm_min_level, logic_operator FROM channels WHERE id = ?", channelID,
		).Scan(&minLevel, &permMinLevel, &logicOp)
		if err != nil {
			log.Printf("broadcast: channel %d permission query failed, falling back to unfiltered: %v", channelID, err)
			ws.ChatManager.BroadcastToAllUsers(payload)
			return
		}
		rule := permission.PermRule{PermMinLevel: permMinLevel, GroupMinLevel: minLevel, LogicOperator: logicOp}
		ws.ChatManager.BroadcastFiltered(payload, func(user *permission.UserInfo) bool {
			return permission.CanAccess(user, rule)
		})
	}

	handler.Register(e, cfg, db, ssoClient)
	ws.Register(e, cfg, ssoClient, db)

	// Serve frontend static files
	distPath := cfg.FrontendDistPath
	if !filepath.IsAbs(distPath) {
		distPath = filepath.Join(filepath.Dir(os.Args[0]), distPath)
	}
	if info, err := os.Stat(distPath); err == nil && info.IsDir() {
		e.Static("/assets", filepath.Join(distPath, "assets"))
		e.Static("/worklets", filepath.Join(distPath, "worklets"))
		e.File("/*", filepath.Join(distPath, "index.html"))
	}

	addr := fmt.Sprintf("%s:%d", cfg.Host, cfg.Port)
	go func() {
		if err := e.Start(addr); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt)
	<-quit

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := e.Shutdown(ctx); err != nil {
		log.Fatalf("shutdown error: %v", err)
	}
}
