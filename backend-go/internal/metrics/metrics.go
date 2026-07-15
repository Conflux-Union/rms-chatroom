// Package metrics defines the process-wide Prometheus instrumentation.
//
// All metrics live on the default registry so promhttp.Handler() picks them
// up together with the built-in Go runtime and process collectors. Packages
// increment these vars directly; there is no indirection layer because the
// backend is a single process and the default registry is already global.
package metrics

import (
	"net/http"
	"strconv"
	"time"

	"github.com/labstack/echo/v4"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

var (
	HTTPRequestsTotal = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "rms_http_requests_total",
		Help: "HTTP requests by method, registered route and status code.",
	}, []string{"method", "path", "status"})

	HTTPRequestDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "rms_http_request_duration_seconds",
		Help:    "HTTP request latency by method and registered route.",
		Buckets: prometheus.DefBuckets,
	}, []string{"method", "path"})

	WSDeadConnectionsClosed = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "rms_ws_dead_connections_closed_total",
		Help: "WebSocket connections force-closed by the health monitor.",
	}, []string{"hub"})

	WSBroadcastDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "rms_ws_broadcast_duration_seconds",
		Help:    "Time spent fanning out one broadcast to all recipients.",
		Buckets: []float64{.0001, .0005, .001, .005, .01, .05, .1, .5, 1},
	}, []string{"kind"})

	PermCacheRequests = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "rms_perm_cache_requests_total",
		Help: "Channel permission cache lookups by result (hit/miss).",
	}, []string{"result"})

	MessagesCreated = promauto.NewCounter(prometheus.CounterOpts{
		Name: "rms_messages_created_total",
		Help: "Chat messages successfully persisted.",
	})

	AuthLogins = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "rms_auth_logins_total",
		Help: "Successful logins by method (oauth/silent/dev).",
	}, []string{"method"})

	AuthRefresh = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "rms_auth_refresh_total",
		Help: "Refresh token exchanges by outcome (ok/invalid/expired/error).",
	}, []string{"status"})

	MusicCommands = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "rms_music_commands_total",
		Help: "Music playback commands broadcast to rooms, by command type.",
	}, []string{"command"})

	MusicProviderErrors = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "rms_music_provider_errors_total",
		Help: "Music provider request failures by provider and operation.",
	}, []string{"provider", "op"})

	MusicCredentialRefresh = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "rms_music_credential_refresh_total",
		Help: "Music provider credential refresh attempts by outcome.",
	}, []string{"provider", "status"})

	LivekitTokensIssued = promauto.NewCounter(prometheus.CounterOpts{
		Name: "rms_livekit_tokens_issued_total",
		Help: "LiveKit room tokens successfully minted.",
	})

	LivekitTokenErrors = promauto.NewCounter(prometheus.CounterOpts{
		Name: "rms_livekit_token_errors_total",
		Help: "LiveKit room token minting failures.",
	})

	LivekitWebhookEvents = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "rms_livekit_webhook_events_total",
		Help: "Verified LiveKit webhook events by event type.",
	}, []string{"event"})

	ClientEvents = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "rms_client_events_total",
		Help: "Client telemetry events accepted by platform and event type.",
	}, []string{"platform", "type"})
)

// RegisterGaugeFunc registers a live-read gauge (e.g. current WS connection
// counts) with optional constant labels.
func RegisterGaugeFunc(name, help string, constLabels prometheus.Labels, fn func() float64) {
	promauto.NewGaugeFunc(prometheus.GaugeOpts{
		Name:        name,
		Help:        help,
		ConstLabels: constLabels,
	}, fn)
}

// MustRegister exposes the default registry for collectors that need explicit
// registration (e.g. collectors.NewDBStatsCollector).
func MustRegister(cs ...prometheus.Collector) {
	prometheus.MustRegister(cs...)
}

// Middleware records request count and latency for every HTTP request. The
// path label uses the registered route pattern (not the raw URL) so label
// cardinality stays bounded; unrouted requests (static files, SPA fallback)
// are collapsed into a single "unrouted" bucket.
func Middleware() echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			start := time.Now()
			err := next(c)
			elapsed := time.Since(start)

			path := c.Path()
			if path == "" || path == "/*" {
				path = "unrouted"
			}
			status := c.Response().Status
			if err != nil {
				if he, ok := err.(*echo.HTTPError); ok {
					status = he.Code
				}
			}
			method := c.Request().Method
			HTTPRequestsTotal.WithLabelValues(method, path, strconv.Itoa(status)).Inc()
			HTTPRequestDuration.WithLabelValues(method, path).Observe(elapsed.Seconds())
			return err
		}
	}
}

// Handler returns the /metrics endpoint handler. The endpoint requires the
// configured bearer token; serving operational metrics unauthenticated would
// leak usage data on a public host.
func Handler(token string) echo.HandlerFunc {
	h := promhttp.Handler()
	return func(c echo.Context) error {
		auth := c.Request().Header.Get("Authorization")
		if auth != "Bearer "+token {
			return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid metrics token"})
		}
		h.ServeHTTP(c.Response(), c.Request())
		return nil
	}
}
