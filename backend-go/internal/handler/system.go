package handler

import (
	"context"
	"database/sql"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	"github.com/RMS-Server/rms-discord-go/internal/update"
	"github.com/RMS-Server/rms-discord-go/internal/version"
)

// RegisterSystemRoutes registers /api/system routes. updater may be nil
// (e.g. in tests), in which case the self-update routes report 503.
func RegisterSystemRoutes(g *echo.Group, cfg *config.Config, db *sql.DB, updater *update.Updater) {
	g.GET("/health", systemHealth(db))
	g.GET("/version", systemVersion)
	g.POST("/update", systemUpdate(cfg))
	g.POST("/update/check", systemUpdateCheck(cfg, updater))
	g.GET("/update/status", systemUpdateStatus(cfg, updater))
}

// systemHealth reports process liveness plus database reachability so uptime
// monitors catch a wedged DB, not just a dead process.
func systemHealth(db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		ctx, cancel := context.WithTimeout(c.Request().Context(), 2*time.Second)
		defer cancel()
		if err := db.PingContext(ctx); err != nil {
			return c.JSON(http.StatusServiceUnavailable, map[string]interface{}{
				"status":   "degraded",
				"database": "unreachable",
			})
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"status": "ok",
		})
	}
}

func systemVersion(c echo.Context) error {
	return c.JSON(http.StatusOK, map[string]string{
		"version": version.Full(),
	})
}

// deployTokenValid reports whether the request carries the configured deploy
// token; an empty configured token disables the deploy endpoints entirely.
// Callers must stop and write the 401 themselves when this returns false.
func deployTokenValid(cfg *config.Config, c echo.Context) bool {
	token := c.QueryParam("token")
	return cfg.DeployToken != "" && token == cfg.DeployToken
}

func deployUnauthorized(c echo.Context) error {
	return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid deploy token"})
}

// systemUpdateCheck triggers a pull-based self-update. CI calls it after
// publishing a release, optionally posting the release manifest so the
// server can skip the (mirror-dependent) manifest lookup; with an empty body
// the latest manifest is fetched from GitHub Releases.
func systemUpdateCheck(cfg *config.Config, updater *update.Updater) echo.HandlerFunc {
	return func(c echo.Context) error {
		if !deployTokenValid(cfg, c) {
			return deployUnauthorized(c)
		}
		if updater == nil {
			return c.JSON(http.StatusServiceUnavailable, map[string]string{"error": "updater unavailable"})
		}

		var manifest *update.Manifest
		if c.Request().ContentLength > 0 {
			var m update.Manifest
			if err := c.Bind(&m); err != nil {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid manifest body"})
			}
			manifest = &m
		}

		started := updater.Trigger(manifest)
		status := updater.Status()
		return c.JSON(http.StatusAccepted, map[string]interface{}{
			"started": started,
			"status":  status,
		})
	}
}

func systemUpdateStatus(cfg *config.Config, updater *update.Updater) echo.HandlerFunc {
	return func(c echo.Context) error {
		if !deployTokenValid(cfg, c) {
			return deployUnauthorized(c)
		}
		if updater == nil {
			return c.JSON(http.StatusServiceUnavailable, map[string]string{"error": "updater unavailable"})
		}
		return c.JSON(http.StatusOK, updater.Status())
	}
}

// systemUpdate handles push-based self-deployment via tar.gz upload; kept for
// the legacy deploy flow. The archive should contain:
//   - rms-discord-server (Go binary)
//   - packages/web/dist/  (frontend files)
func systemUpdate(cfg *config.Config) echo.HandlerFunc {
	return func(c echo.Context) error {
		if !deployTokenValid(cfg, c) {
			return deployUnauthorized(c)
		}

		file, err := c.FormFile("file")
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "file required"})
		}

		src, err := file.Open()
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to open upload"})
		}
		defer src.Close()

		// Deploy into the directory the binary lives in
		exePath, err := os.Executable()
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "cannot determine executable path"})
		}

		res, err := update.ExtractArchive(src, filepath.Dir(exePath))
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
		}

		update.ScheduleRestart(cfg.ServiceName)

		return c.JSON(http.StatusOK, map[string]interface{}{
			"extracted_files": res.Files,
			"binary_updated":  res.BinaryUpdated,
			"restart": map[string]interface{}{
				"scheduled": true,
				"method":    "systemctl restart " + cfg.ServiceName,
			},
			"message": fmt.Sprintf("deployed %d files, restarting...", res.Files),
		})
	}
}
