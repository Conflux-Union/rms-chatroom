package handler

import (
	"archive/tar"
	"compress/gzip"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/config"
)

// Version info set at build time.
var (
	VersionName = "dev"
	VersionCode = "0"
	CommitHash  = "unknown"
)

// RegisterSystemRoutes registers /api/system routes.
func RegisterSystemRoutes(g *echo.Group, cfg *config.Config) {
	g.GET("/health", systemHealth)
	g.GET("/version", systemVersion)
	g.POST("/update", systemUpdate(cfg))
}

func systemHealth(c echo.Context) error {
	return c.JSON(http.StatusOK, map[string]interface{}{
		"status": "ok",
	})
}

func systemVersion(c echo.Context) error {
	return c.JSON(http.StatusOK, map[string]string{
		"version": "v" + VersionName + "(" + VersionCode + ")(commit:" + CommitHash + ")",
	})
}

// systemUpdate handles self-deployment via tar.gz upload.
// The archive should contain:
//   - rms-discord-server (Go binary)
//   - packages/web/dist/  (frontend files)
func systemUpdate(cfg *config.Config) echo.HandlerFunc {
	return func(c echo.Context) error {
		token := c.QueryParam("token")
		if cfg.DeployToken == "" || token != cfg.DeployToken {
			return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid deploy token"})
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

		// Determine deploy base directory (where the binary lives)
		exePath, err := os.Executable()
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "cannot determine executable path"})
		}
		baseDir := filepath.Dir(exePath)

		// Extract archive
		gz, err := gzip.NewReader(src)
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid gzip"})
		}
		defer gz.Close()

		tr := tar.NewReader(gz)
		extracted := 0
		var newBinaryPath string

		for {
			hdr, err := tr.Next()
			if err == io.EOF {
				break
			}
			if err != nil {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "corrupt archive: " + err.Error()})
			}

			// Sanitize path to prevent directory traversal
			clean := filepath.Clean(hdr.Name)
			if strings.Contains(clean, "..") {
				continue
			}

			target := filepath.Join(baseDir, clean)

			switch hdr.Typeflag {
			case tar.TypeDir:
				if err := os.MkdirAll(target, 0755); err != nil {
					return c.JSON(http.StatusInternalServerError, map[string]string{"error": "mkdir failed: " + err.Error()})
				}

			case tar.TypeReg:
				if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
					return c.JSON(http.StatusInternalServerError, map[string]string{"error": "mkdir failed: " + err.Error()})
				}

				// Write binary to a temp file first, then rename (atomic replace)
				if clean == "rms-discord-server" {
					newBinaryPath = target + ".new"
					target = newBinaryPath
				}

				out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, os.FileMode(hdr.Mode))
				if err != nil {
					return c.JSON(http.StatusInternalServerError, map[string]string{"error": "write failed: " + err.Error()})
				}
				if _, err := io.Copy(out, tr); err != nil {
					out.Close()
					return c.JSON(http.StatusInternalServerError, map[string]string{"error": "copy failed: " + err.Error()})
				}
				out.Close()
				extracted++
			}
		}

		// Replace binary atomically
		if newBinaryPath != "" {
			finalPath := strings.TrimSuffix(newBinaryPath, ".new")
			if err := os.Chmod(newBinaryPath, 0755); err != nil {
				return c.JSON(http.StatusInternalServerError, map[string]string{"error": "chmod failed: " + err.Error()})
			}
			if err := os.Rename(newBinaryPath, finalPath); err != nil {
				return c.JSON(http.StatusInternalServerError, map[string]string{"error": "rename failed: " + err.Error()})
			}
		}

		// Schedule restart via systemd (async, so we can return response first)
		restart := map[string]interface{}{"scheduled": false}
		go func() {
			time.Sleep(1 * time.Second)
			_ = exec.Command("systemctl", "restart", "rms-discord").Run()
		}()
		restart["scheduled"] = true
		restart["method"] = "systemctl restart rms-discord"

		return c.JSON(http.StatusOK, map[string]interface{}{
			"extracted_files": extracted,
			"binary_updated":  newBinaryPath != "",
			"restart":         restart,
			"message":         fmt.Sprintf("deployed %d files, restarting...", extracted),
		})
	}
}
