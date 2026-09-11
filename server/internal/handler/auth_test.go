package handler

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/config"
)

func TestFetchSilentSessionForwardsBrowserEnvironment(t *testing.T) {
	const browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0"
	const browserIP = "203.0.113.7"

	var gotUA, gotClientIP, gotCookie string
	sso := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotUA = r.Header.Get("User-Agent")
		gotClientIP = r.Header.Get("X-SSO-Client-IP")
		for _, c := range r.Cookies() {
			if c.Name == ssoSessionCookieName {
				gotCookie = c.Value
			}
		}
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"code":0,"data":{"authenticated":true}}`)
	}))
	defer sso.Close()

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/auth/silent-login", nil)
	req.Header.Set("User-Agent", browserUA)
	req.Header.Set("X-Forwarded-For", browserIP)
	req.AddCookie(&http.Cookie{Name: ssoSessionCookieName, Value: "sso_test"})
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	h := NewAuthHandler(nil, nil, testConfigWithSSOURL(sso.URL))
	session, err := h.fetchSilentSession(c)
	if err != nil {
		t.Fatalf("fetchSilentSession: %v", err)
	}
	if session == nil || !session.Data.Authenticated {
		t.Fatal("expected authenticated session")
	}
	if gotCookie != "sso_test" {
		t.Fatalf("cookie not forwarded, got %q", gotCookie)
	}
	if gotUA != browserUA {
		t.Fatalf("browser User-Agent not forwarded: got %q, want %q", gotUA, browserUA)
	}
	if gotClientIP != browserIP {
		t.Fatalf("browser X-SSO-Client-IP not forwarded: got %q, want %q", gotClientIP, browserIP)
	}
}

func TestFetchSilentSessionFallsBackToRemoteIP(t *testing.T) {
	var gotClientIP string
	sso := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotClientIP = r.Header.Get("X-SSO-Client-IP")
		io.WriteString(w, `{"code":0,"data":{"authenticated":false}}`)
	}))
	defer sso.Close()

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/auth/silent-login", nil)
	req.AddCookie(&http.Cookie{Name: ssoSessionCookieName, Value: "sso_test"})
	// httptest.NewRequest always uses example.com; emulate a direct client by
	// leaving RemoteAddr as-is and sending no incoming X-Forwarded-For.
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	h := NewAuthHandler(nil, nil, testConfigWithSSOURL(sso.URL))
	if _, err := h.fetchSilentSession(c); err != nil {
		t.Fatalf("fetchSilentSession: %v", err)
	}
	if !strings.Contains(gotClientIP, "192.0.2.1") && !strings.Contains(gotClientIP, "example.com") {
		t.Fatalf("expected fallback X-SSO-Client-IP with client address, got %q", gotClientIP)
	}
}

func testConfigWithSSOURL(baseURL string) *config.Config {
	return &config.Config{
		SSOBaseURL:      baseURL,
		OAuthBaseURL:    baseURL,
		OAuthClientID:   "test-client",
		OAuthClientSecret: "test-secret",
	}
}
