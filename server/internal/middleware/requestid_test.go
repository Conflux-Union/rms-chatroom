package middleware

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"
	echomw "github.com/labstack/echo/v4/middleware"
)

var requestIDPattern = regexp.MustCompile(`^cxu_chat_req_[0-9a-f]{24}$`)

func newRequestIDTestServer() *echo.Echo {
	e := echo.New()
	e.Logger.SetOutput(io.Discard)
	e.Use(RequestID())
	e.Use(echomw.Recover())
	// Logger sits between Recover and the routes in production and renders
	// returned errors itself, so include it to exercise the same path. The
	// ${header:X-Request-Id} access-log tag reads the request header.
	e.Use(echomw.LoggerWithConfig(echomw.LoggerConfig{
		Output: io.Discard,
		Format: "method=${method} uri=${uri} status=${status} req_id=${header:X-Request-Id}\n",
	}))

	e.GET("/ok", func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]any{"ok": true})
	})
	e.GET("/err", func(c echo.Context) error {
		return c.JSON(http.StatusBadRequest, map[string]any{"error": "boom"})
	})
	e.GET("/http-err", func(c echo.Context) error {
		return echo.NewHTTPError(http.StatusNotFound, "nope")
	})
	e.GET("/panic", func(c echo.Context) error {
		panic("kaboom")
	})
	e.GET("/text-err", func(c echo.Context) error {
		return c.String(http.StatusTeapot, "plain text error")
	})
	e.GET("/array-err", func(c echo.Context) error {
		return c.JSON(http.StatusUnprocessableEntity, []int{1, 2})
	})
	e.GET("/nums", func(c echo.Context) error {
		return c.JSON(http.StatusBadRequest, map[string]any{"n": 1234567890123456789})
	})
	e.GET("/echo-request-header", func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]any{
			"req_header_id": c.Request().Header.Get(echo.HeaderXRequestID),
		})
	})
	return e
}

func doRequest(e *echo.Echo, path string, headers map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, path, nil)
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)
	return rec
}

func assertRequestIDHeader(t *testing.T, rec *httptest.ResponseRecorder, path string) string {
	t.Helper()
	id := rec.Header().Get(echo.HeaderXRequestID)
	if !requestIDPattern.MatchString(id) {
		t.Fatalf("%s: X-Request-Id header = %q, want match for %s", path, id, requestIDPattern)
	}
	return id
}

func TestRequestIDHeaderOnAllResponses(t *testing.T) {
	e := newRequestIDTestServer()
	cases := []struct {
		name string
		path string
		code int
	}{
		{"success", "/ok", http.StatusOK},
		{"handler json error", "/err", http.StatusBadRequest},
		{"http error via default handler", "/http-err", http.StatusNotFound},
		{"panic recovered", "/panic", http.StatusInternalServerError},
		{"non-json error", "/text-err", http.StatusTeapot},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := doRequest(e, tc.path, nil)
			if rec.Code != tc.code {
				t.Fatalf("%s: code = %d, want %d (body %q)", tc.path, rec.Code, tc.code, rec.Body.String())
			}
			assertRequestIDHeader(t, rec, tc.path)
		})
	}
}

func TestRequestIDInjectedIntoJSONErrorBodies(t *testing.T) {
	e := newRequestIDTestServer()
	cases := []struct {
		name    string
		path    string
		wantKey string
		wantVal string
	}{
		{"handler json error", "/err", "error", "boom"},
		{"default handler error", "/http-err", "message", "nope"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := doRequest(e, tc.path, nil)
			id := assertRequestIDHeader(t, rec, tc.path)

			var body map[string]any
			if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
				t.Fatalf("%s: body not JSON: %v (%q)", tc.path, err, rec.Body.String())
			}
			if got, _ := body[tc.wantKey].(string); got != tc.wantVal {
				t.Fatalf("%s: body[%q] = %v, want %q", tc.path, tc.wantKey, body[tc.wantKey], tc.wantVal)
			}
			if got, _ := body["request_id"].(string); got != id {
				t.Fatalf("%s: body request_id = %q, want %q", tc.path, got, id)
			}
		})
	}
}

func TestRequestIDDoesNotTouchNonErrorPaths(t *testing.T) {
	e := newRequestIDTestServer()

	rec := doRequest(e, "/ok", nil)
	assertRequestIDHeader(t, rec, "/ok")
	if strings.Contains(rec.Body.String(), "request_id") {
		t.Fatalf("/ok: success body was modified: %q", rec.Body.String())
	}

	rec = doRequest(e, "/text-err", nil)
	assertRequestIDHeader(t, rec, "/text-err")
	if rec.Body.String() != "plain text error" {
		t.Fatalf("/text-err: non-JSON body modified: %q", rec.Body.String())
	}

	rec = doRequest(e, "/array-err", nil)
	assertRequestIDHeader(t, rec, "/array-err")
	if got := strings.TrimSpace(rec.Body.String()); got != "[1,2]" || strings.Contains(rec.Body.String(), "request_id") {
		t.Fatalf("/array-err: non-object JSON body modified: %q", rec.Body.String())
	}
}

func TestRequestIDInjectionPreservesNumbers(t *testing.T) {
	e := newRequestIDTestServer()
	rec := doRequest(e, "/nums", nil)
	assertRequestIDHeader(t, rec, "/nums")
	if !strings.Contains(rec.Body.String(), "1234567890123456789") {
		t.Fatalf("/nums: numeric literal changed by injection: %q", rec.Body.String())
	}
}

func TestRequestIDUniqueAndClientValueIgnored(t *testing.T) {
	e := newRequestIDTestServer()

	rec := doRequest(e, "/ok", map[string]string{echo.HeaderXRequestID: "cxu_chat_req_forged_by_client"})
	id := assertRequestIDHeader(t, rec, "/ok")
	if id == "cxu_chat_req_forged_by_client" {
		t.Fatal("client-supplied X-Request-Id was echoed instead of regenerated")
	}

	if id2 := assertRequestIDHeader(t, doRequest(e, "/ok", nil), "/ok"); id2 == id {
		t.Fatalf("two requests produced the same id %q", id)
	}
}

func TestRequestIDSetOnRequestHeaderForAccessLog(t *testing.T) {
	e := newRequestIDTestServer()
	rec := doRequest(e, "/echo-request-header", nil)
	respID := assertRequestIDHeader(t, rec, "/echo-request-header")

	var body struct {
		ReqHeaderID string `json:"req_header_id"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("body not JSON: %v", err)
	}
	if body.ReqHeaderID != respID {
		t.Fatalf("request header id = %q, want %q (access-log tag reads this)", body.ReqHeaderID, respID)
	}
}

func TestReqLogf(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	c := e.NewContext(req, httptest.NewRecorder())

	c.Set(requestIDContextKey, RequestIDPrefix+"abc")
	ReqLogf(c, "handler test: value %d", 7) // visible in test output, must not panic

	c.Set(requestIDContextKey, nil)
	ReqLogf(c, "handler test without id: value %d", 8)
}
