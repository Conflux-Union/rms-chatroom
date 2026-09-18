package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/DATA-DOG/go-sqlmock"
	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

// newForwardTestEnv builds a ForwardHandler backed by sqlmock and an SSO stub.
// The stub maps 10001@qq.com -> user 42 and 10002@qq.com -> user 43; every
// other email misses, exercising the unmatched-mention path.
func newForwardTestEnv(t *testing.T) (*ForwardHandler, sqlmock.Sqlmock, *map[string]interface{}) {
	t.Helper()

	ssoSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/account_info" {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Query().Get("email") {
		case "10001@qq.com":
			w.Write([]byte(`{"success":true,"user":{"id":42,"username":"alice","nickname":"小张","email":"10001@qq.com"}}`))
		case "10002@qq.com":
			w.Write([]byte(`{"success":true,"user":{"id":43,"username":"bob","nickname":"Bobby","email":"10002@qq.com"}}`))
		default:
			http.Error(w, "not found", http.StatusNotFound)
		}
	}))
	t.Cleanup(ssoSrv.Close)

	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New: %v", err)
	}
	t.Cleanup(func() { db.Close() })

	broadcasts := &map[string]interface{}{}
	saved := BroadcastFunc
	BroadcastFunc = func(channelID int64, payload map[string]interface{}) {
		*broadcasts = payload
	}
	t.Cleanup(func() { BroadcastFunc = saved })

	h := NewForwardHandler(db, sso.NewClient(ssoSrv.URL, ""), "")
	return h, mock, broadcasts
}

func postForwardMessage(t *testing.T, h *ForwardHandler, body string) (*httptest.ResponseRecorder, map[string]interface{}) {
	t.Helper()

	req := httptest.NewRequest(http.MethodPost, "/api/forward/channels/1/messages", strings.NewReader(body))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	c := echo.New().NewContext(req, rec)
	c.SetParamNames("channel_id")
	c.SetParamValues("1")
	if err := h.PostMessage(c); err != nil {
		t.Fatalf("PostMessage: %v", err)
	}

	var resp map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("response is not JSON: %v\nbody: %s", err, rec.Body.String())
	}
	return rec, resp
}

// expectChannelAndMessageInsert covers channel verification and the messages
// row. wantContent is asserted on the stored content.
func expectChannelAndMessageInsert(t *testing.T, mock sqlmock.Sqlmock, wantContent string) {
	t.Helper()

	mock.ExpectQuery(regexp.QuoteMeta("SELECT type FROM channels WHERE id = ?")).
		WithArgs(int64(1)).
		WillReturnRows(sqlmock.NewRows([]string{"type"}).AddRow("FORWARD"))
	mock.ExpectExec(regexp.QuoteMeta(
		"INSERT INTO messages (channel_id, user_id, username, content, reply_to_id, source_platform, source_message_id, forward_meta) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")).
		WithArgs(int64(1), sqlmock.AnyArg(), sqlmock.AnyArg(), wantContent, sqlmock.AnyArg(), "qq", sqlmock.AnyArg(), sqlmock.AnyArg()).
		WillReturnResult(sqlmock.NewResult(77, 1))
}

// expectPostMessageQueries covers the follow-up queries after the message row
// exists (created_at read and attachment load).
func expectPostMessageQueries(t *testing.T, mock sqlmock.Sqlmock) {
	t.Helper()

	createdAt := time.Date(2026, 9, 18, 12, 0, 0, 0, time.UTC)
	mock.ExpectQuery(regexp.QuoteMeta("SELECT created_at FROM messages WHERE id = ?")).
		WithArgs(int64(77)).
		WillReturnRows(sqlmock.NewRows([]string{"created_at"}).AddRow(createdAt))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT id, filename, content_type, size FROM attachments WHERE message_id = ?")).
		WithArgs(int64(77)).
		WillReturnRows(sqlmock.NewRows([]string{"id", "filename", "content_type", "size"}))
}

func assertMentions(t *testing.T, got interface{}, wantID int64, wantUsername string) {
	t.Helper()

	// Normalize both the JSON-decoded REST response ([]interface{}) and the
	// native broadcast payload ([]mentionResp) through one representation.
	raw, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("mentions not marshalable: %v", err)
	}
	var list []map[string]interface{}
	if err := json.Unmarshal(raw, &list); err != nil || len(list) != 1 {
		t.Fatalf("mentions = %s, want one entry", raw)
	}
	id, _ := list[0]["id"].(float64)
	if int64(id) != wantID || list[0]["username"] != wantUsername {
		t.Fatalf("mention = %s, want {id:%d username:%q}", raw, wantID, wantUsername)
	}
}

func TestPostMessageResolvesQQMentions(t *testing.T) {
	h, mock, broadcasts := newForwardTestEnv(t)
	expectChannelAndMessageInsert(t, mock, "你好 @Bobby 看看这个")
	mock.ExpectExec(regexp.QuoteMeta("INSERT IGNORE INTO message_mentions (message_id, user_id) VALUES (?, ?)")).
		WithArgs(int64(77), int64(43)).
		WillReturnResult(sqlmock.NewResult(0, 1))
	expectPostMessageQueries(t, mock)

	rec, resp := postForwardMessage(t, h,
		`{"source":"qq","sender":{"qq":10001,"nickname":"小张"},"content":"你好 @Bob 看看这个","source_message_id":"qqmsg-1",`+
			`"mentions":[{"qq":10002,"name":"Bob"},{"qq":99999,"name":"查无此人"}]}`)

	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body: %s", rec.Code, rec.Body.String())
	}
	// The matched mention is rewritten to the platform identity; the unmatched
	// one stays as-is. Stored content, response, and broadcast must all agree.
	if resp["content"] != "你好 @Bobby 看看这个" {
		t.Fatalf("content = %v, want rewritten mention", resp["content"])
	}
	assertMentions(t, resp["mentions"], 43, "Bobby")

	payload := *broadcasts
	if payload == nil {
		t.Fatal("no broadcast captured")
	}
	if payload["content"] != "你好 @Bobby 看看这个" {
		t.Fatalf("broadcast content = %v, want rewritten mention", payload["content"])
	}
	assertMentions(t, payload["mentions"], 43, "Bobby")

	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("unfulfilled SQL expectations: %v", err)
	}
}

func TestPostMessageUnmatchedMentionStaysUntouched(t *testing.T) {
	h, mock, broadcasts := newForwardTestEnv(t)
	expectChannelAndMessageInsert(t, mock, "在吗 @路人")
	expectPostMessageQueries(t, mock)
	// No message_mentions expectation: the unmatched mention must not record one.

	rec, resp := postForwardMessage(t, h,
		`{"source":"qq","sender":{"qq":10001,"nickname":"小张"},"content":"在吗 @路人","source_message_id":"qqmsg-2",`+
			`"mentions":[{"qq":99999,"name":"路人"}]}`)

	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body: %s", rec.Code, rec.Body.String())
	}
	if resp["content"] != "在吗 @路人" {
		t.Fatalf("content = %v, want unchanged", resp["content"])
	}
	if mentions, ok := resp["mentions"].([]interface{}); !ok || len(mentions) != 0 {
		t.Fatalf("mentions = %#v, want empty", resp["mentions"])
	}
	if payload := *broadcasts; payload != nil {
		if mentions, ok := payload["mentions"].([]mentionResp); !ok || len(mentions) != 0 {
			t.Fatalf("broadcast mentions = %#v, want empty", payload["mentions"])
		}
	}

	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("unfulfilled SQL expectations: %v", err)
	}
}
