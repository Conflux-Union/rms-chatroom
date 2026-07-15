package handler

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	sqlmock "github.com/DATA-DOG/go-sqlmock"
	"github.com/labstack/echo/v4"
)

func TestSubmitClientTelemetry_AcceptsAndSanitizes(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock: %v", err)
	}
	defer db.Close()

	// The stack contains a WS token and a JWT; both must be redacted before storage.
	mock.ExpectExec("INSERT INTO client_telemetry").
		WithArgs(nil, "web", "1.0.7", "vue_error",
			"boom ?token=[redacted]",
			"at ws connect wss://x/ws/chat?token=[redacted]\n[redacted-jwt]",
			nil,
		).
		WillReturnResult(sqlmock.NewResult(1, 1))

	body := `{"platform":"web","app_version":"1.0.7","events":[{"type":"vue_error",` +
		`"message":"boom ?token=eyAbc.def_123","stack":"at ws connect wss://x/ws/chat?token=secret123\n` +
		`eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c"}]}`

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/telemetry/client", strings.NewReader(body))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	if err := submitClientTelemetry("test-secret", db)(c); err != nil {
		t.Fatalf("handler error: %v", err)
	}
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body: %s)", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"accepted":1`) {
		t.Fatalf("body = %s, want accepted:1", rec.Body.String())
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("unmet sql expectations: %v", err)
	}
}

func TestSubmitClientTelemetry_RejectsInvalidInput(t *testing.T) {
	db, _, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock: %v", err)
	}
	defer db.Close()

	cases := []struct {
		name string
		body string
	}{
		{"unknown platform", `{"platform":"ios","events":[{"type":"crash"}]}`},
		{"no events", `{"platform":"web","events":[]}`},
		{"bad event type", `{"platform":"web","events":[{"type":"Crash!"}]}`},
		{"too many events", `{"platform":"web","events":[` +
			strings.Repeat(`{"type":"crash"},`, 20) + `{"type":"crash"}]}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := echo.New()
			req := httptest.NewRequest(http.MethodPost, "/api/telemetry/client", strings.NewReader(tc.body))
			req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
			rec := httptest.NewRecorder()
			c := e.NewContext(req, rec)

			if err := submitClientTelemetry("test-secret", db)(c); err != nil {
				t.Fatalf("handler error: %v", err)
			}
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400", rec.Code)
			}
		})
	}
}

func TestSanitizeTelemetryText(t *testing.T) {
	in := "GET /ws/chat?token=abc123&x=1 refresh_token=zzz " +
		"jwt eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c end"
	out := sanitizeTelemetryText(in)
	for _, leaked := range []string{"abc123", "zzz", "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c"} {
		if strings.Contains(out, leaked) {
			t.Fatalf("sanitized output still contains %q: %s", leaked, out)
		}
	}
	if !strings.Contains(out, "x=1") {
		t.Fatalf("sanitizer over-redacted non-secret params: %s", out)
	}
}

func TestTruncateKeepsValidUTF8(t *testing.T) {
	s := strings.Repeat("好", 100)
	got := truncate(s, 10)
	if len(got) > 10 {
		t.Fatalf("len = %d, want <= 10", len(got))
	}
	if !strings.HasPrefix(s, got) || len(got)%3 != 0 {
		t.Fatalf("truncate split a multi-byte rune: %q", got)
	}
}
