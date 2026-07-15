package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"
)

func newFrontendTestServer(t *testing.T) *echo.Echo {
	t.Helper()
	dist := t.TempDir()
	write := func(rel, content string) {
		t.Helper()
		path := filepath.Join(dist, rel)
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("index.html", "<html>spa</html>")
	write("favicon.ico", "icon-bytes")
	write("mention-notification.wav", "wav-bytes")
	write("assets/app.js", "console.log(1)")

	e := echo.New()
	e.GET("/api/ping", func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]string{"pong": "1"})
	})
	e.GET("/api/missing", func(c echo.Context) error {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "nope"})
	})
	registerFrontend(e, dist)
	return e
}

func TestRegisterFrontend(t *testing.T) {
	e := newFrontendTestServer(t)

	cases := []struct {
		name     string
		path     string
		wantCode int
		wantBody string
	}{
		{"root serves index", "/", http.StatusOK, "<html>spa</html>"},
		{"public file served as-is", "/favicon.ico", http.StatusOK, "icon-bytes"},
		{"audio file served as-is", "/mention-notification.wav", http.StatusOK, "wav-bytes"},
		{"nested asset served as-is", "/assets/app.js", http.StatusOK, "console.log(1)"},
		{"spa route falls back to index", "/channels/42", http.StatusOK, "<html>spa</html>"},
		{"registered api route wins", "/api/ping", http.StatusOK, `"pong"`},
		{"api 404 json is not swallowed", "/api/missing", http.StatusNotFound, `"nope"`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, tc.path, nil)
			rec := httptest.NewRecorder()
			e.ServeHTTP(rec, req)
			if rec.Code != tc.wantCode {
				t.Fatalf("GET %s: code = %d, want %d (body %q)", tc.path, rec.Code, tc.wantCode, rec.Body.String())
			}
			if !strings.Contains(rec.Body.String(), tc.wantBody) {
				t.Fatalf("GET %s: body = %q, want it to contain %q", tc.path, rec.Body.String(), tc.wantBody)
			}
		})
	}
}
