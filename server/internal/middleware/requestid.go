package middleware

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/labstack/echo/v4"
)

const (
	// RequestIDPrefix marks every generated request id so users can quote it
	// verbatim in bug reports and admins can grep the server logs for it.
	RequestIDPrefix = "cxu_chat_req_"

	// maxInjectableErrorBody caps buffered error bodies; anything larger
	// streams through untouched.
	maxInjectableErrorBody = 64 * 1024

	requestIDContextKey = "request_id"
)

// RequestID assigns a backend-generated id to every request and makes it
// available for correlation between the response, the server logs, and user
// bug reports. The id is set on the response header (read by clients) and on
// the request header (read by Echo's access log via ${header:X-Request-Id}).
// Responses with status >= 400 additionally get a request_id field injected
// into their JSON body: handlers write errors via c.JSON directly in several
// shapes, so buffering the small error body here is the single choke point
// that covers all of them (including panics rendered by the default error
// handler) without touching hundreds of call sites.
//
// Must be registered before middleware.Recover so panic-rendered 500s pass
// through it too.
func RequestID() echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			id := NewRequestID()
			c.Set(requestIDContextKey, id)
			c.Request().Header.Set(echo.HeaderXRequestID, id)
			c.Response().Header().Set(echo.HeaderXRequestID, id)

			w := &errorBodyBuffer{ResponseWriter: c.Response().Writer}
			c.Response().Writer = w

			// Echo's Logger middleware renders returned errors itself
			// (c.Error) before they reach us, so err with a committed
			// response is the normal path; err with an uncommitted response
			// only happens for panics recovered by middleware.Recover.
			err := next(c)
			if err != nil && !c.Response().Committed {
				log.Printf("%s request failed: %v", id, err)
				c.Error(err)
			}
			w.finalize(c, id)
			return nil
		}
	}
}

// NewRequestID returns a fresh id: the fixed cxu_chat_req_ prefix plus 12
// random bytes in hex. Client-supplied X-Request-Id values are ignored on
// purpose — the id must always come from this server.
func NewRequestID() string {
	var b [12]byte
	if _, err := rand.Read(b[:]); err != nil {
		// crypto/rand effectively never fails on Linux; fall back to a
		// timestamp-derived id rather than failing the request.
		return fmt.Sprintf("%s%024x", RequestIDPrefix, time.Now().UnixNano())
	}
	return RequestIDPrefix + hex.EncodeToString(b[:])
}

// FromContext returns the id assigned by RequestID, or "" on code paths
// without one (background goroutines, WS events after the handshake).
func FromContext(c echo.Context) string {
	if c == nil {
		return ""
	}
	id, _ := c.Get(requestIDContextKey).(string)
	return id
}

// ReqLogf logs with the request id prefixed so the line can be matched to
// the access log and to ids quoted in user bug reports.
func ReqLogf(c echo.Context, format string, args ...any) {
	if id := FromContext(c); id != "" {
		format = "[" + id + "] " + format
	}
	log.Printf(format, args...)
}

// errorBodyBuffer streams statuses < 400 through untouched and buffers error
// bodies so finalize can inject request_id before the bytes hit the wire.
type errorBodyBuffer struct {
	http.ResponseWriter

	status   int
	buffered []byte
	pending  bool // an error status was captured but not yet forwarded
	overflow bool // body exceeded the cap; forwarding raw since then
}

func (w *errorBodyBuffer) WriteHeader(code int) {
	if w.status != 0 {
		return // already committed through us; echo guards this too
	}
	w.status = code
	if code >= http.StatusBadRequest {
		w.pending = true
		return
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *errorBodyBuffer) Write(b []byte) (int, error) {
	if w.pending {
		if w.overflow {
			return w.ResponseWriter.Write(b)
		}
		if len(w.buffered)+len(b) > maxInjectableErrorBody {
			w.overflow = true
			w.flushRaw()
			return w.ResponseWriter.Write(b)
		}
		w.buffered = append(w.buffered, b...)
		return len(b), nil
	}
	return w.ResponseWriter.Write(b)
}

// Flush supports streaming handlers; while an error body is buffered there
// is nothing to flush yet, so it is deferred to finalize.
func (w *errorBodyBuffer) Flush() {
	if w.pending {
		return
	}
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

// Hijack must reach the underlying writer or gorilla/websocket upgrades
// fail on every route.
func (w *errorBodyBuffer) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	h, ok := w.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, fmt.Errorf("%T does not support hijacking", w.ResponseWriter)
	}
	return h.Hijack()
}

// flushRaw forwards the captured status and buffered bytes unmodified.
func (w *errorBodyBuffer) flushRaw() {
	w.pending = false
	w.ResponseWriter.WriteHeader(w.status)
	_, _ = w.ResponseWriter.Write(w.buffered)
}

// finalize injects the request id into the buffered JSON error object, or
// replays the body untouched when it is not one, and commits the response.
func (w *errorBodyBuffer) finalize(c echo.Context, id string) {
	if !w.pending || w.overflow {
		return
	}
	w.pending = false

	header := c.Response().Header()
	body := w.buffered
	if len(body) > 0 && isJSONContentType(header.Get("Content-Type")) {
		// UseNumber keeps the original numeric literals intact across the
		// decode/encode round trip (IDs, timestamps, snowflake-style ints).
		var payload map[string]any
		dec := json.NewDecoder(bytes.NewReader(body))
		dec.UseNumber()
		if err := dec.Decode(&payload); err == nil {
			payload["request_id"] = id
			if injected, err := json.Marshal(payload); err == nil {
				body = injected
				header.Set("Content-Length", strconv.Itoa(len(body)))
			}
		}
	}
	w.ResponseWriter.WriteHeader(w.status)
	_, _ = w.ResponseWriter.Write(body)
}

func isJSONContentType(contentType string) bool {
	if i := strings.IndexByte(contentType, ';'); i >= 0 {
		contentType = contentType[:i]
	}
	return strings.EqualFold(strings.TrimSpace(contentType), "application/json")
}
