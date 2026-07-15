package handler

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
	"unicode/utf8"

	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"

	"github.com/RMS-Server/rms-discord-go/internal/jwtutil"
	"github.com/RMS-Server/rms-discord-go/internal/metrics"
)

const (
	telemetryMaxEvents     = 20
	telemetryMaxMessageLen = 2048
	telemetryMaxStackLen   = 16384
	telemetryMaxVersionLen = 64
	telemetryMaxMetaLen    = 4096
)

var (
	telemetryEventTypeRe = regexp.MustCompile(`^[a-z0-9_]{1,32}$`)

	// Client stacks and messages can embed URLs carrying credentials
	// (?token= on WS handshakes) or raw JWTs; strip both before storage.
	telemetryTokenParamRe = regexp.MustCompile(`(?i)\b(token|access_token|refresh_token)=[^&\s'"]+`)
	telemetryJWTRe        = regexp.MustCompile(`\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b`)
)

var telemetryPlatforms = map[string]bool{
	"web":     true,
	"desktop": true,
	"android": true,
	"fabric":  true,
}

type clientTelemetryEvent struct {
	Type    string                 `json:"type"`
	Message string                 `json:"message"`
	Stack   string                 `json:"stack"`
	Meta    map[string]interface{} `json:"meta"`
}

type clientTelemetryBatch struct {
	Platform   string                 `json:"platform"`
	AppVersion string                 `json:"app_version"`
	Events     []clientTelemetryEvent `json:"events"`
}

// RegisterTelemetryRoutes registers /api/telemetry routes.
//
// The ingest endpoint is intentionally reachable without authentication:
// crashes on the login screen and token-refresh failures are exactly the
// events that happen while no valid token exists. A per-IP rate limit and a
// small body cap bound abuse; a Bearer token is still parsed when present so
// events can be attributed to a user.
func RegisterTelemetryRoutes(g *echo.Group, jwtSecret string, db *sql.DB) {
	g.Use(middleware.BodyLimit("256K"))
	g.Use(middleware.RateLimiter(middleware.NewRateLimiterMemoryStore(5)))
	g.POST("/client", submitClientTelemetry(jwtSecret, db))
}

func submitClientTelemetry(jwtSecret string, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		var batch clientTelemetryBatch
		if err := c.Bind(&batch); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid json body"})
		}
		if !telemetryPlatforms[batch.Platform] {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid platform"})
		}
		if len(batch.Events) == 0 || len(batch.Events) > telemetryMaxEvents {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "events must contain 1-20 items"})
		}
		for _, ev := range batch.Events {
			if !telemetryEventTypeRe.MatchString(ev.Type) {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid event type"})
			}
		}

		// Optional attribution: a valid Bearer token links events to a user,
		// an absent or invalid one leaves them anonymous.
		var userID interface{}
		if auth := c.Request().Header.Get("Authorization"); strings.HasPrefix(auth, "Bearer ") {
			if user, err := jwtutil.ParseToken(strings.TrimPrefix(auth, "Bearer "), jwtSecret); err == nil {
				userID = user.ID
			}
		}

		appVersion := truncate(batch.AppVersion, telemetryMaxVersionLen)
		accepted := 0
		for _, ev := range batch.Events {
			message := sanitizeTelemetryText(truncate(ev.Message, telemetryMaxMessageLen))
			stack := sanitizeTelemetryText(truncate(ev.Stack, telemetryMaxStackLen))

			var meta interface{}
			if len(ev.Meta) > 0 {
				if raw, err := json.Marshal(ev.Meta); err == nil && len(raw) <= telemetryMaxMetaLen {
					meta = sanitizeTelemetryText(string(raw))
				}
			}

			_, err := db.Exec(
				`INSERT INTO client_telemetry (user_id, platform, app_version, event_type, message, stack, meta, created_at)
				 VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())`,
				userID, batch.Platform, appVersion, ev.Type, message, stack, meta,
			)
			if err != nil {
				c.Logger().Errorf("telemetry: insert failed: %v", err)
				continue
			}
			metrics.ClientEvents.WithLabelValues(batch.Platform, ev.Type).Inc()
			accepted++
		}

		return c.JSON(http.StatusOK, map[string]int{"accepted": accepted})
	}
}

// truncate cuts s to at most max bytes without splitting a UTF-8 sequence,
// which would produce invalid text MySQL rejects on insert.
func truncate(s string, max int) string {
	if len(s) <= max {
		return s
	}
	s = s[:max]
	for len(s) > 0 && !utf8.ValidString(s) {
		s = s[:len(s)-1]
	}
	return s
}

func sanitizeTelemetryText(s string) string {
	if s == "" {
		return s
	}
	s = telemetryTokenParamRe.ReplaceAllString(s, "${1}=[redacted]")
	return telemetryJWTRe.ReplaceAllString(s, "[redacted-jwt]")
}
