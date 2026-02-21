package handler

import (
	"net/http"

	"github.com/labstack/echo/v4"
)

// Version info set at build time.
var (
	VersionName = "dev"
	VersionCode = "0"
	CommitHash  = "unknown"
)

// RegisterSystemRoutes registers /api/system routes.
func RegisterSystemRoutes(g *echo.Group) {
	g.GET("/health", systemHealth)
	g.GET("/version", systemVersion)
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
