package handler

import (
	"database/sql"
	"net/http"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/middleware"
)

// ReadPositionHandler handles read position endpoints.
type ReadPositionHandler struct {
	db *sql.DB
}

func NewReadPositionHandler(db *sql.DB) *ReadPositionHandler {
	return &ReadPositionHandler{db: db}
}

type readPositionResp struct {
	ChannelID            int64  `json:"channel_id"`
	LastReadMessageID    int64  `json:"last_read_message_id"`
	HasMention           bool   `json:"has_mention"`
	LastMentionMessageID *int64 `json:"last_mention_message_id"`
}

// GetAllReadPositions returns all read positions for the current user.
// GET /api/read-positions
func (h *ReadPositionHandler) GetAllReadPositions(c echo.Context) error {
	user := middleware.GetUser(c)

	rows, err := h.db.Query(
		"SELECT channel_id, last_read_message_id, has_mention, last_mention_message_id FROM read_positions WHERE user_id = ?",
		user.ID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer rows.Close()

	var positions []readPositionResp
	for rows.Next() {
		var p readPositionResp
		var lastMention sql.NullInt64
		if err := rows.Scan(&p.ChannelID, &p.LastReadMessageID, &p.HasMention, &lastMention); err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		if lastMention.Valid {
			p.LastMentionMessageID = &lastMention.Int64
		}
		positions = append(positions, p)
	}
	if positions == nil {
		positions = []readPositionResp{}
	}

	return c.JSON(http.StatusOK, map[string]interface{}{
		"positions": positions,
	})
}
