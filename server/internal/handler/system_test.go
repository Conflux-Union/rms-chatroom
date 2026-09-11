package handler

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/config"
	"github.com/RMS-Server/rms-discord-go/internal/update"
)

// The deploy endpoints must stop at the token check: a rejected request may
// not fall through into the handler body (that would let anyone trigger a
// self-update or push a binary).
func TestDeployEndpointsRejectBadToken(t *testing.T) {
	cfg := &config.Config{DeployToken: "right-token", ServiceName: "svc"}
	updater, err := update.New("o/r", nil, "svc")
	if err != nil {
		t.Fatal(err)
	}

	e := echo.New()

	cases := []struct {
		name    string
		method  string
		handler echo.HandlerFunc
	}{
		{"update check", http.MethodPost, systemUpdateCheck(cfg, updater)},
		{"update status", http.MethodGet, systemUpdateStatus(cfg, updater)},
		{"push deploy", http.MethodPost, systemUpdate(cfg)},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(tc.method, "/?token=wrong-token", nil)
			rec := httptest.NewRecorder()
			if err := tc.handler(e.NewContext(req, rec)); err != nil {
				t.Fatalf("handler error: %v", err)
			}
			if rec.Code != http.StatusUnauthorized {
				t.Errorf("status = %d, want 401", rec.Code)
			}
			if !strings.Contains(rec.Body.String(), "invalid deploy token") {
				t.Errorf("body = %q, want the 401 error only", rec.Body.String())
			}
		})
	}

	if s := updater.Status(); s.State != update.StateIdle {
		t.Errorf("updater state = %q after rejected requests, want %q (auth bypass!)", s.State, update.StateIdle)
	}
}

// An empty configured deploy token must disable the endpoints outright.
func TestDeployEndpointsDisabledWithoutConfiguredToken(t *testing.T) {
	cfg := &config.Config{DeployToken: ""}
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/?token=", nil)
	rec := httptest.NewRecorder()
	if err := systemUpdateCheck(cfg, nil)(e.NewContext(req, rec)); err != nil {
		t.Fatalf("handler error: %v", err)
	}
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

// With a valid token but no updater wired up the endpoint reports 503, which
// also proves the token check passes valid requests through.
func TestUpdateCheckValidTokenNilUpdater(t *testing.T) {
	cfg := &config.Config{DeployToken: "right-token"}
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/?token=right-token", nil)
	rec := httptest.NewRecorder()
	if err := systemUpdateCheck(cfg, nil)(e.NewContext(req, rec)); err != nil {
		t.Fatalf("handler error: %v", err)
	}
	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503", rec.Code)
	}
}
