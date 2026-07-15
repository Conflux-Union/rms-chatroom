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
	"github.com/prometheus/client_golang/prometheus/collectors"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	dbm "github.com/RMS-Server/rms-discord-go/internal/db"
	"github.com/RMS-Server/rms-discord-go/internal/handler"
	"github.com/RMS-Server/rms-discord-go/internal/metrics"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
	"github.com/RMS-Server/rms-discord-go/internal/update"
	"github.com/RMS-Server/rms-discord-go/internal/ws"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("failed to load config: %v", err)
	}

	// Fail fast on a missing database_url instead of starting up and 500-ing
	// on the first request. sql.Open is lazy and never connects, so a Ping is
	// required to actually validate the DSN.
	if cfg.DatabaseURL == "" {
		log.Fatal("database_url is required (set it in config.json or the DATABASE_URL env var)")
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

	// Apply pending schema migrations before opening the main pool so the
	// server never serves requests against an outdated schema. Required for
	// unattended self-updates.
	if cfg.AutoMigrate {
		if err := dbm.Migrate(dsn); err != nil {
			log.Fatalf("database migration failed: %v", err)
		}
	}

	db, err := sql.Open("mysql", dsn)
	if err != nil {
		log.Fatalf("failed to open database: %v", err)
	}
	defer db.Close()
	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(5 * time.Minute)
	if err := db.Ping(); err != nil {
		log.Fatalf("failed to connect to database: %v", err)
	}

	ssoClient := sso.NewClient(cfg.SSOBaseURL)

	e := echo.New()
	e.HideBanner = true

	e.Use(middleware.Recover())
	e.Use(middleware.Logger())
	e.Use(metrics.Middleware())
	// Tauri desktop webview origins are not known at config time.
	// Always allow them so the desktop app can make API calls.
	allowOrigins := make([]string, 0, len(cfg.CORSOrigins)+3)
	allowOrigins = append(allowOrigins, cfg.CORSOrigins...)
	allowOrigins = append(allowOrigins, "https://tauri.localhost", "http://tauri.localhost", "tauri://localhost")

	e.Use(middleware.CORSWithConfig(middleware.CORSConfig{
		AllowOrigins:     allowOrigins,
		AllowMethods:     []string{http.MethodGet, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete, http.MethodOptions},
		AllowHeaders:     []string{echo.HeaderOrigin, echo.HeaderContentType, echo.HeaderAccept, echo.HeaderAuthorization},
		AllowCredentials: true,
	}))

	// Wire up BroadcastFunc so HTTP handlers can broadcast WS events.
	// Filter recipients by channel access permission; fall back to
	// unfiltered broadcast on transient DB errors to avoid silently
	// dropping events for messages already persisted. The permission rule
	// is memoized via ChannelPermCache so repeated edits/reactions to the
	// same channel don't re-query the DB on every broadcast.
	handler.BroadcastFunc = func(channelID int64, payload map[string]interface{}) {
		rule, err := ws.ChannelPermCache.Get(channelID, func() (permission.PermRule, error) {
			var minLevel, permMinLevel int
			var logicOp string
			err := db.QueryRow(
				"SELECT min_level, perm_min_level, logic_operator FROM channels WHERE id = ?", channelID,
			).Scan(&minLevel, &permMinLevel, &logicOp)
			if err != nil {
				return permission.PermRule{}, err
			}
			return permission.PermRule{PermMinLevel: permMinLevel, GroupMinLevel: minLevel, LogicOperator: logicOp}, nil
		})
		if err != nil {
			log.Printf("broadcast: channel %d permission query failed, falling back to unfiltered: %v", channelID, err)
			ws.ChatManager.BroadcastToAllUsers(payload)
			return
		}
		ws.ChatManager.BroadcastFiltered(payload, func(user *permission.UserInfo) bool {
			return permission.CanAccess(user, rule)
		})
	}

	// Pull-based self-updater: triggered by CI via /api/system/update/check,
	// optionally also on a timer as a fallback when CI cannot reach us.
	updater, err := update.New(cfg.UpdateRepo, cfg.UpdateMirrors, cfg.ServiceName)
	if err != nil {
		log.Printf("self-updater disabled: %v", err)
		updater = nil
	} else if cfg.UpdateCheckIntervalMinutes > 0 {
		updater.StartPeriodic(time.Duration(cfg.UpdateCheckIntervalMinutes) * time.Minute)
	}

	handler.Register(e, cfg, db, ssoClient, updater)
	ws.Register(e, cfg, ssoClient, db)

	// Prometheus scrape endpoint, enabled only when a scrape token is set.
	metrics.MustRegister(collectors.NewDBStatsCollector(db, "main"))
	if cfg.MetricsToken != "" {
		e.GET("/metrics", metrics.Handler(cfg.MetricsToken))
	}

	// Serve frontend static files
	distPath := cfg.FrontendDistPath
	if !filepath.IsAbs(distPath) {
		distPath = filepath.Join(filepath.Dir(os.Args[0]), distPath)
	}
	if info, err := os.Stat(distPath); err == nil && info.IsDir() {
		registerFrontend(e, distPath)
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

// registerFrontend serves the built web frontend: any path matching a real
// file under distPath (favicons, notification audio, hashed assets) is served
// as-is, and everything else falls back to index.html so SPA routes survive
// hard reloads. Registered API/WS routes are unaffected — the HTML5 fallback
// only fires for paths the router reports as unrouted 404 HTTPErrors, and
// handlers write their not-found responses via c.JSON directly.
func registerFrontend(e *echo.Echo, distPath string) {
	e.Use(middleware.StaticWithConfig(middleware.StaticConfig{
		Root:  distPath,
		HTML5: true,
	}))
}
