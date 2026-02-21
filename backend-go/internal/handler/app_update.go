package handler

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"

	"github.com/labstack/echo/v4"
)

// RegisterAppUpdateRoutes registers /api/app/android routes.
func RegisterAppUpdateRoutes(g *echo.Group, appDir string) {
	g.GET("/checkupdate", checkAndroidUpdate(appDir))
	g.GET("/download", downloadAPK(appDir))
}

func checkAndroidUpdate(appDir string) echo.HandlerFunc {
	return func(c echo.Context) error {
		versionFile := filepath.Join(appDir, "version.json")
		data, err := os.ReadFile(versionFile)
		if err != nil {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "version info not found"})
		}

		var info map[string]interface{}
		if err := json.Unmarshal(data, &info); err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "invalid version info"})
		}

		return c.JSON(http.StatusOK, map[string]interface{}{
			"version_code": info["version_code"],
			"version_name": info["version_name"],
			"changelog":    info["changelog"],
			"force_update": info["force_update"],
			"download_url": "/api/app/android/download",
		})
	}
}

func downloadAPK(appDir string) echo.HandlerFunc {
	return func(c echo.Context) error {
		apkPath := filepath.Join(appDir, "rms-chatroom.apk")
		if _, err := os.Stat(apkPath); os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "APK file not found"})
		}
		return c.Attachment(apkPath, "rms-chatroom.apk")
	}
}
