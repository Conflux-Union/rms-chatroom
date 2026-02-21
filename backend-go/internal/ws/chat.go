package ws

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/gorilla/websocket"
	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/jwtutil"
)

var mentionRe = regexp.MustCompile(`@(\w+)`)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin:     func(r *http.Request) bool { return true },
}

// chatMessage is the incoming message from a chat client.
type chatMessage struct {
	Type          string  `json:"type"`
	Data          string  `json:"data"`
	ChannelID     int64   `json:"channel_id"`
	Content       string  `json:"content"`
	AttachmentIDs []int64 `json:"attachment_ids"`
	ReplyToID     *int64  `json:"reply_to_id"`
}

// chatBroadcast is the outgoing broadcast message.
type chatBroadcast struct {
	Type        string              `json:"type"`
	ID          int64               `json:"id"`
	ChannelID   int64               `json:"channel_id"`
	UserID      int                 `json:"user_id"`
	Username    string              `json:"username"`
	Content     string              `json:"content"`
	CreatedAt   string              `json:"created_at"`
	Attachments []attachmentPayload `json:"attachments"`
	Mentions    []string            `json:"mentions"`
	ReplyTo     *replyPayload       `json:"reply_to,omitempty"`
}

type attachmentPayload struct {
	ID          int64  `json:"id"`
	Filename    string `json:"filename"`
	ContentType string `json:"content_type"`
	Size        int    `json:"size"`
	URL         string `json:"url"`
}

type replyPayload struct {
	ID       int64  `json:"id"`
	UserID   int64  `json:"user_id"`
	Username string `json:"username"`
	Content  string `json:"content"`
}

// HandleChatWS handles the /ws/chat WebSocket endpoint.
func HandleChatWS(jwtSecret string, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		token := c.QueryParam("token")
		if token == "" {
			return c.JSON(http.StatusUnauthorized, map[string]string{"error": "missing token"})
		}

		user, err := jwtutil.ParseToken(token, jwtSecret)
		if err != nil {
			return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid token"})
		}

		ws, err := upgrader.Upgrade(c.Response(), c.Request(), nil)
		if err != nil {
			return err
		}

		conn := newConn(ws, user)
		ChatManager.ConnectGlobal(conn)
		defer ChatManager.DisconnectGlobal(conn)

		// Send connected confirmation
		connected, _ := json.Marshal(map[string]string{"type": "connected"})
		conn.send <- connected

		go conn.WritePump()

		conn.ReadPump(func(raw []byte) {
			var msg chatMessage
			if err := json.Unmarshal(raw, &msg); err != nil {
				return
			}

			// Handle heartbeat
			if msg.Type == "ping" && msg.Data == "tribios" {
				pong, _ := json.Marshal(map[string]string{"type": "pong", "data": "cute"})
				conn.send <- pong
				return
			}

			if msg.Type != "message" {
				return
			}

			handleChatMessage(db, conn, &msg)
		})

		return nil
	}
}

func handleChatMessage(db *sql.DB, conn *Conn, msg *chatMessage) {
	if msg.ChannelID == 0 || strings.TrimSpace(msg.Content) == "" {
		return
	}

	// Verify channel exists and is TEXT type
	var channelType string
	err := db.QueryRow("SELECT type FROM channels WHERE id = ?", msg.ChannelID).Scan(&channelType)
	if err != nil || channelType != "TEXT" {
		return
	}

	// Check mute status
	var muteCount int
	err = db.QueryRow(
		`SELECT COUNT(*) FROM mute_records
		 WHERE user_id = ? AND (muted_until IS NULL OR muted_until > UTC_TIMESTAMP())
		   AND (scope = 'global' OR (scope = 'channel' AND channel_id = ?))`,
		conn.user.ID, msg.ChannelID,
	).Scan(&muteCount)
	if err == nil && muteCount > 0 {
		errMsg, _ := json.Marshal(map[string]string{"type": "error", "message": "you are muted"})
		conn.send <- errMsg
		return
	}

	// Insert message
	var replyToID sql.NullInt64
	if msg.ReplyToID != nil {
		replyToID = sql.NullInt64{Int64: *msg.ReplyToID, Valid: true}
	}

	result, err := db.Exec(
		`INSERT INTO messages (channel_id, user_id, username, content, reply_to_id)
		 VALUES (?, ?, ?, ?, ?)`,
		msg.ChannelID, conn.user.ID, conn.user.Username, msg.Content, replyToID,
	)
	if err != nil {
		log.Printf("ws/chat: failed to insert message: %v", err)
		return
	}

	messageID, _ := result.LastInsertId()
	now := time.Now().UTC().Format("2006-01-02T15:04:05Z")

	// Link attachments
	var attachments []attachmentPayload
	for _, aid := range msg.AttachmentIDs {
		db.Exec("UPDATE attachments SET message_id = ? WHERE id = ? AND user_id = ? AND message_id IS NULL",
			messageID, aid, conn.user.ID)

		var a attachmentPayload
		err := db.QueryRow("SELECT id, filename, content_type, size FROM attachments WHERE id = ?", aid).
			Scan(&a.ID, &a.Filename, &a.ContentType, &a.Size)
		if err == nil {
			a.URL = fmt.Sprintf("/api/files/%d", a.ID)
			attachments = append(attachments, a)
		}
	}

	// Parse @mentions
	mentions := mentionRe.FindAllStringSubmatch(msg.Content, -1)
	var mentionNames []string
	seen := make(map[string]bool)
	for _, m := range mentions {
		name := m[1]
		if !seen[name] {
			seen[name] = true
			mentionNames = append(mentionNames, name)
		}
	}

	// Insert mention records (look up user IDs from messages table)
	for _, name := range mentionNames {
		var uid int64
		err := db.QueryRow(
			"SELECT DISTINCT user_id FROM messages WHERE username = ? LIMIT 1", name,
		).Scan(&uid)
		if err == nil {
			db.Exec("INSERT IGNORE INTO message_mentions (message_id, user_id) VALUES (?, ?)",
				messageID, uid)
		}
	}

	// Build reply_to payload
	var reply *replyPayload
	if msg.ReplyToID != nil {
		var rp replyPayload
		err := db.QueryRow(
			"SELECT id, user_id, username, content FROM messages WHERE id = ?", *msg.ReplyToID,
		).Scan(&rp.ID, &rp.UserID, &rp.Username, &rp.Content)
		if err == nil {
			if len(rp.Content) > 100 {
				rp.Content = rp.Content[:97] + "..."
			}
			reply = &rp
		}
	}

	broadcast := chatBroadcast{
		Type:        "message",
		ID:          messageID,
		ChannelID:   msg.ChannelID,
		UserID:      conn.user.ID,
		Username:    conn.user.Username,
		Content:     msg.Content,
		CreatedAt:   now,
		Attachments: attachments,
		Mentions:    mentionNames,
		ReplyTo:     reply,
	}

	ChatManager.BroadcastToAllUsers(broadcast)
}
