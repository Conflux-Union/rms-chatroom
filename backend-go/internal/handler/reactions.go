package handler

import (
	"database/sql"
	"net/http"
	"net/url"
	"strconv"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/middleware"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

// ReactionHandler handles message reaction endpoints.
type ReactionHandler struct {
	db  *sql.DB
	sso *sso.Client
}

func NewReactionHandler(db *sql.DB, sso *sso.Client) *ReactionHandler {
	return &ReactionHandler{db: db, sso: sso}
}

type reactionCreateReq struct {
	Emoji string `json:"emoji"`
}

type reactionResp struct {
	ID        int64  `json:"id"`
	MessageID int64  `json:"message_id"`
	UserID    int64  `json:"user_id"`
	Username  string `json:"username"`
	Emoji     string `json:"emoji"`
	CreatedAt string `json:"created_at"`
}

type reactionGroupWithReacted struct {
	Emoji   string             `json:"emoji"`
	Count   int                `json:"count"`
	Users   []reactionUserResp `json:"users"`
	Reacted bool               `json:"reacted"`
}

// AddReaction adds a reaction to a message (idempotent).
// POST /api/messages/:message_id/reactions
func (h *ReactionHandler) AddReaction(c echo.Context) error {
	user := middleware.GetUser(c)
	messageID, err := strconv.ParseInt(c.Param("message_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid message id"})
	}

	var req reactionCreateReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}
	emoji := req.Emoji
	if emoji == "" || len(emoji) > 32 {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid emoji"})
	}

	// Verify message exists and not deleted
	var isDeleted bool
	var channelID int64
	err = h.db.QueryRow("SELECT channel_id, is_deleted FROM messages WHERE id = ?", messageID).Scan(&channelID, &isDeleted)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "message not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	if isDeleted {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "cannot react to deleted message"})
	}

	// Check existing
	var existing reactionResp
	err = h.db.QueryRow(
		"SELECT id, message_id, user_id, username, emoji, created_at FROM reactions WHERE message_id = ? AND user_id = ? AND emoji = ?",
		messageID, user.ID, emoji,
	).Scan(&existing.ID, &existing.MessageID, &existing.UserID, &existing.Username, &existing.Emoji, &existing.CreatedAt)
	if err == nil {
		return c.JSON(http.StatusCreated, existing)
	}

	username := user.Nickname
	if username == "" {
		username = user.Username
	}

	// INSERT IGNORE for idempotency
	h.db.Exec(
		"INSERT IGNORE INTO reactions (message_id, user_id, username, emoji) VALUES (?, ?, ?, ?)",
		messageID, user.ID, username, emoji,
	)

	// Fetch the created reaction
	var resp reactionResp
	h.db.QueryRow(
		"SELECT id, message_id, user_id, username, emoji, created_at FROM reactions WHERE message_id = ? AND user_id = ? AND emoji = ?",
		messageID, user.ID, emoji,
	).Scan(&resp.ID, &resp.MessageID, &resp.UserID, &resp.Username, &resp.Emoji, &resp.CreatedAt)

	// Broadcast via WS
	if BroadcastFunc != nil {
		BroadcastFunc(channelID, map[string]interface{}{
			"type":       "reaction_added",
			"message_id": messageID,
			"channel_id": channelID,
			"emoji":      emoji,
			"user_id":    user.ID,
			"username":   username,
		})
	}

	return c.JSON(http.StatusCreated, resp)
}

// RemoveReaction removes a reaction (URL-decode emoji).
// DELETE /api/messages/:message_id/reactions/:emoji
func (h *ReactionHandler) RemoveReaction(c echo.Context) error {
	user := middleware.GetUser(c)
	messageID, err := strconv.ParseInt(c.Param("message_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid message id"})
	}

	emoji, err := url.PathUnescape(c.Param("emoji"))
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid emoji"})
	}

	// Verify message
	var channelID int64
	err = h.db.QueryRow("SELECT channel_id FROM messages WHERE id = ?", messageID).Scan(&channelID)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "message not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	res, err := h.db.Exec(
		"DELETE FROM reactions WHERE message_id = ? AND user_id = ? AND emoji = ?",
		messageID, user.ID, emoji,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "reaction not found"})
	}

	if BroadcastFunc != nil {
		BroadcastFunc(channelID, map[string]interface{}{
			"type":       "reaction_removed",
			"message_id": messageID,
			"channel_id": channelID,
			"emoji":      emoji,
			"user_id":    user.ID,
		})
	}

	return c.NoContent(http.StatusNoContent)
}

// GetReactions returns reactions grouped by emoji.
// GET /api/messages/:message_id/reactions
func (h *ReactionHandler) GetReactions(c echo.Context) error {
	user := middleware.GetUser(c)
	messageID, err := strconv.ParseInt(c.Param("message_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid message id"})
	}

	var exists int
	if err := h.db.QueryRow("SELECT 1 FROM messages WHERE id = ?", messageID).Scan(&exists); err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "message not found"})
	}

	rows, err := h.db.Query(
		"SELECT emoji, user_id, username FROM reactions WHERE message_id = ? ORDER BY created_at", messageID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer rows.Close()

	groupMap := map[string]*reactionGroupWithReacted{}
	var order []string
	for rows.Next() {
		var emoji, username string
		var userID int64
		rows.Scan(&emoji, &userID, &username)
		if _, ok := groupMap[emoji]; !ok {
			groupMap[emoji] = &reactionGroupWithReacted{Emoji: emoji}
			order = append(order, emoji)
		}
		g := groupMap[emoji]
		g.Count++
		g.Users = append(g.Users, reactionUserResp{ID: userID, Username: username})
		if userID == int64(user.ID) {
			g.Reacted = true
		}
	}

	result := make([]reactionGroupWithReacted, 0, len(order))
	for _, e := range order {
		result = append(result, *groupMap[e])
	}
	return c.JSON(http.StatusOK, result)
}
