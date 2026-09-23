package ws

import (
	"database/sql"
	"encoding/json"
	"log"
	"net/http"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/jwtutil"
	"github.com/RMS-Server/rms-discord-go/internal/readstate"
)

// globalMessage represents an incoming message on /ws/global. Legacy clients
// also send has_mention / last_mention_message_id on read_position_update;
// those fields are ignored by the server-derived unread model and elided here
// (unknown JSON fields are dropped by the decoder).
type globalMessage struct {
	Type              string `json:"type"`
	Data              string `json:"data"`
	ChannelID         int64  `json:"channel_id"`
	LastReadMessageID int64  `json:"last_read_message_id"`
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

			// "channel_ack" was removed with the server-derived unread model;
			// legacy clients still send it and unknown types are ignored.
		})

		return nil
	}
}

func handleReadPositionUpdate(db *sql.DB, conn *Conn, msg *globalMessage) {
	if msg.ChannelID == 0 || msg.LastReadMessageID == 0 {
		return
	}

	// Unread counts and mention flags are server-derived; the client-supplied
	// has_mention / last_mention_message_id fields are legacy and ignored.
	state, err := readstate.AdvanceReadPosition(db, int64(conn.user.ID), msg.ChannelID, msg.LastReadMessageID)
	if err != nil {
		log.Printf("ws/global: failed to advance read position: %v", err)
		return
	}

	// Broadcast the recomputed state to the user's other connections.
	GlobalStateManager.SendToUserExclude(int64(conn.user.ID), map[string]interface{}{
		"type":                     "read_position_sync",
		"channel_id":              msg.ChannelID,
		"last_read_message_id":    state.LastReadMessageID,
		"unread_count":            state.UnreadCount,
		"has_mention":             state.HasMention,
		"last_mention_message_id": state.LastMentionMessageID,
	}, conn)
}

func handleReadPositionSync(db *sql.DB, conn *Conn) {
	rows, err := db.Query(
		"SELECT channel_id, last_read_message_id FROM read_positions WHERE user_id = ?",
		conn.user.ID,
	)
	if err != nil {
		return
	}
	defer rows.Close()

	for rows.Next() {
		var channelID, lastReadID int64
		if err := rows.Scan(&channelID, &lastReadID); err != nil {
			continue
		}

		// Derive fresh: messages may have arrived since the mention columns
		// were last written.
		state, err := readstate.DeriveFromPosition(db, int64(conn.user.ID), channelID, lastReadID)
		if err != nil {
			continue
		}

		msg := map[string]interface{}{
			"type":                     "read_position_sync",
			"channel_id":              channelID,
			"last_read_message_id":     state.LastReadMessageID,
			"unread_count":             state.UnreadCount,
			"has_mention":              state.HasMention,
			"last_mention_message_id":  nil,
		}
		if state.LastMentionMessageID != nil {
			msg["last_mention_message_id"] = *state.LastMentionMessageID
		}

		data, _ := json.Marshal(msg)
		conn.send <- data
	}
}
