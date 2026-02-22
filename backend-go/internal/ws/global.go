package ws

import (
	"database/sql"
	"encoding/json"
	"log"
	"net/http"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/jwtutil"
)

// globalMessage represents an incoming message on /ws/global.
type globalMessage struct {
	Type                 string `json:"type"`
	Data                 string `json:"data"`
	ChannelID            int64  `json:"channel_id"`
	LastReadMessageID    int64  `json:"last_read_message_id"`
	HasMention           bool   `json:"has_mention"`
	LastMentionMessageID *int64 `json:"last_mention_message_id"`
}

// HandleGlobalWS handles the /ws/global WebSocket endpoint.
func HandleGlobalWS(jwtSecret string, db *sql.DB) echo.HandlerFunc {
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
		GlobalStateManager.ConnectGlobal(conn)
		defer GlobalStateManager.DisconnectGlobal(conn)

		connected, _ := json.Marshal(map[string]string{"type": "connected"})
		conn.send <- connected

		go conn.WritePump()

		conn.ReadPump(func(raw []byte) {
			var msg globalMessage
			if err := json.Unmarshal(raw, &msg); err != nil {
				return
			}

			if msg.Type == "ping" && msg.Data == "tribios" {
				pong, _ := json.Marshal(map[string]string{"type": "pong", "data": "cute"})
				conn.send <- pong
				return
			}

			if msg.Type == "read_position_update" {
				handleReadPositionUpdate(db, conn, &msg)
				return
			}

			if msg.Type == "read_position_sync" {
				handleReadPositionSync(db, conn)
				return
			}
		})

		return nil
	}
}

func handleReadPositionUpdate(db *sql.DB, conn *Conn, msg *globalMessage) {
	if msg.ChannelID == 0 || msg.LastReadMessageID == 0 {
		return
	}

	var lastMentionID sql.NullInt64
	if msg.LastMentionMessageID != nil {
		lastMentionID = sql.NullInt64{Int64: *msg.LastMentionMessageID, Valid: true}
	}

	_, err := db.Exec(
		`INSERT INTO read_positions (user_id, channel_id, last_read_message_id, has_mention, last_mention_message_id)
		 VALUES (?, ?, ?, ?, ?)
		 ON DUPLICATE KEY UPDATE
		   last_read_message_id = GREATEST(last_read_message_id, VALUES(last_read_message_id)),
		   has_mention = VALUES(has_mention),
		   last_mention_message_id = VALUES(last_mention_message_id),
		   updated_at = UTC_TIMESTAMP()`,
		conn.user.ID, msg.ChannelID, msg.LastReadMessageID, msg.HasMention, lastMentionID,
	)
	if err != nil {
		log.Printf("ws/global: failed to upsert read position: %v", err)
		return
	}

	// Broadcast to user's other connections
	update := map[string]interface{}{
		"type":                    "read_position_sync",
		"channel_id":             msg.ChannelID,
		"last_read_message_id":   msg.LastReadMessageID,
		"has_mention":            msg.HasMention,
		"last_mention_message_id": msg.LastMentionMessageID,
	}
	GlobalStateManager.SendToUserExclude(int64(conn.user.ID), update, conn)
}

func handleReadPositionSync(db *sql.DB, conn *Conn) {
	rows, err := db.Query(
		"SELECT channel_id, last_read_message_id, has_mention, last_mention_message_id FROM read_positions WHERE user_id = ?",
		conn.user.ID,
	)
	if err != nil {
		return
	}
	defer rows.Close()

	for rows.Next() {
		var channelID, lastReadID int64
		var hasMention bool
		var lastMentionID sql.NullInt64
		if err := rows.Scan(&channelID, &lastReadID, &hasMention, &lastMentionID); err != nil {
			continue
		}

		msg := map[string]interface{}{
			"type":                    "read_position_sync",
			"channel_id":             channelID,
			"last_read_message_id":   lastReadID,
			"has_mention":            hasMention,
			"last_mention_message_id": nil,
		}
		if lastMentionID.Valid {
			msg["last_mention_message_id"] = lastMentionID.Int64
		}

		data, _ := json.Marshal(msg)
		conn.send <- data
	}
}
