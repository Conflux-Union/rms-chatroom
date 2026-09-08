package middleware

import (
	"net/http"
	"strings"

	"github.com/labstack/echo/v4"
)

// BotAuth returns middleware that validates the static forward-bot token.
// Routes using it are only registered when the token is configured non-empty,
// so an empty match here always means rejection.
func BotAuth(token string) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			auth := c.Request().Header.Get("Authorization")
			if auth == "" || !strings.HasPrefix(auth, "Bearer ") {
				return c.JSON(http.StatusUnauthorized, map[string]string{"error": "missing or invalid authorization header"})
			}
			if strings.TrimPrefix(auth, "Bearer ") != token {
				return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid token"})
			}
			return next(c)
		}
	}
}
