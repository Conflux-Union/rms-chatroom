package middleware

import (
	"net/http"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/jwtutil"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
)

const userContextKey = "user"

// Auth returns middleware that validates Bearer tokens via local JWT parsing.
func Auth(jwtSecret string) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			auth := c.Request().Header.Get("Authorization")
			if auth == "" || !strings.HasPrefix(auth, "Bearer ") {
				return c.JSON(http.StatusUnauthorized, map[string]string{"error": "missing or invalid authorization header"})
			}
			token := strings.TrimPrefix(auth, "Bearer ")

			user, err := jwtutil.ParseToken(token, jwtSecret)
			if err != nil {
				return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid token"})
			}

			c.Set(userContextKey, user)
			return next(c)
		}
	}
}

// GetUser extracts the authenticated user from the echo context.
func GetUser(c echo.Context) *permission.UserInfo {
	u, ok := c.Get(userContextKey).(*permission.UserInfo)
	if !ok {
		return nil
	}
	return u
}

// RequireAdmin returns middleware that rejects non-admin users.
func RequireAdmin() echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			u := GetUser(c)
			if u == nil || !permission.IsAdmin(u) {
				return c.JSON(http.StatusForbidden, map[string]string{"error": "admin privileges required"})
			}
			return next(c)
		}
	}
}
