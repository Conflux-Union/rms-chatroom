package handler

import (
	"database/sql"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/middleware"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

var mentionRegex = regexp.MustCompile(`@(\w+)`)

// BroadcastFunc is called to broadcast WS events. Set by the ws package at startup.
var BroadcastFunc func(channelID int64, payload map[string]interface{})

// MessageHandler handles message CRUD endpoints.
type MessageHandler struct {
	db  *sql.DB
	sso *sso.Client
}

func NewMessageHandler(db *sql.DB, sso *sso.Client) *MessageHandler {
	return &MessageHandler{db: db, sso: sso}
}

type messageCreateReq struct {
	Content       string  `json:"content"`
	AttachmentIDs []int64 `json:"attachment_ids"`
	ReplyToID     *int64  `json:"reply_to_id"`
}

type messageEditReq struct {
	Content string `json:"content"`
}

type attachmentResp struct {
	ID          int64  `json:"id"`
	Filename    string `json:"filename"`
	ContentType string `json:"content_type"`
	Size        int    `json:"size"`
	URL         string `json:"url"`
}

type replyToResp struct {
	ID       int64  `json:"id"`
	UserID   int64  `json:"user_id"`
	Username string `json:"username"`
	Content  string `json:"content"`
}

type mentionResp struct {
	ID       int64  `json:"id"`
	Username string `json:"username"`
}

type reactionGroupResp struct {
	Emoji string             `json:"emoji"`
	Count int                `json:"count"`
	Users []reactionUserResp `json:"users"`
}

type reactionUserResp struct {
	ID       int64  `json:"id"`
	Username string `json:"username"`
}

type messageResp struct {
	ID          int64               `json:"id"`
	ChannelID   int64               `json:"channel_id"`
	UserID      int64               `json:"user_id"`
	Username    string              `json:"username"`
	AvatarURL   *string             `json:"avatar_url"`
	Content     string              `json:"content"`
	CreatedAt   string              `json:"created_at"`
	Attachments []attachmentResp    `json:"attachments"`
	IsDeleted   bool                `json:"is_deleted"`
	DeletedBy   *int64              `json:"deleted_by"`
	EditedAt    *string             `json:"edited_at"`
	ReplyToID   *int64              `json:"reply_to_id"`
	ReplyTo     *replyToResp        `json:"reply_to"`
	Mentions    []mentionResp       `json:"mentions"`
	Reactions   []reactionGroupResp `json:"reactions"`
}

func extractMentions(content string) []string {
	matches := mentionRegex.FindAllStringSubmatch(content, -1)
	seen := map[string]bool{}
	var result []string
	for _, m := range matches {
		name := m[1]
		if !seen[name] {
			seen[name] = true
			result = append(result, name)
		}
	}
	return result
}

func truncateContent(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return s[:max-3] + "..."
}

func (h *MessageHandler) loadAttachments(messageID int64) []attachmentResp {
	rows, err := h.db.Query(
		"SELECT id, filename, content_type, size FROM attachments WHERE message_id = ?", messageID,
	)
	if err != nil {
		return []attachmentResp{}
	}
	defer rows.Close()
	var atts []attachmentResp
	for rows.Next() {
		var a attachmentResp
		rows.Scan(&a.ID, &a.Filename, &a.ContentType, &a.Size)
		a.URL = fmt.Sprintf("/api/files/%d", a.ID)
		atts = append(atts, a)
	}
	if atts == nil {
		return []attachmentResp{}
	}
	return atts
}

func (h *MessageHandler) loadReplyTo(replyToID *int64) *replyToResp {
	if replyToID == nil {
		return nil
	}
	var r replyToResp
	var isDeleted bool
	err := h.db.QueryRow(
		"SELECT id, user_id, username, content, is_deleted FROM messages WHERE id = ?", *replyToID,
	).Scan(&r.ID, &r.UserID, &r.Username, &r.Content, &isDeleted)
	if err != nil {
		return nil
	}
	if isDeleted {
		r.Content = "[Message deleted]"
	} else {
		r.Content = truncateContent(r.Content, 100)
	}
	return &r
}

func (h *MessageHandler) loadReactions(messageID int64) []reactionGroupResp {
	rows, err := h.db.Query(
		"SELECT emoji, user_id, username FROM reactions WHERE message_id = ? ORDER BY created_at", messageID,
	)
	if err != nil {
		return []reactionGroupResp{}
	}
	defer rows.Close()

	groupMap := map[string]*reactionGroupResp{}
	var order []string
	for rows.Next() {
		var emoji, username string
		var userID int64
		rows.Scan(&emoji, &userID, &username)
		if _, ok := groupMap[emoji]; !ok {
			groupMap[emoji] = &reactionGroupResp{Emoji: emoji}
			order = append(order, emoji)
		}
		g := groupMap[emoji]
		g.Count++
		g.Users = append(g.Users, reactionUserResp{ID: userID, Username: username})
	}
	result := make([]reactionGroupResp, 0, len(order))
	for _, e := range order {
		result = append(result, *groupMap[e])
	}
	return result
}

// GetMessages returns paginated messages for a channel.
// GET /api/channels/:channel_id/messages
func (h *MessageHandler) GetMessages(c echo.Context) error {
	user := middleware.GetUser(c)
	channelID, err := strconv.ParseInt(c.Param("channel_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel id"})
	}

	limit := 50
	if v := c.QueryParam("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 100 {
			limit = n
		}
	}

	// Verify channel
	var chType string
	var minLevel, permMinLevel int
	var logicOperator string
	err = h.db.QueryRow(
		"SELECT type, min_level, perm_min_level, logic_operator FROM channels WHERE id = ?", channelID,
	).Scan(&chType, &minLevel, &permMinLevel, &logicOperator)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	if chType != "TEXT" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a text channel"})
	}
	accessRule := permission.PermRule{PermMinLevel: permMinLevel, GroupMinLevel: minLevel, LogicOperator: logicOperator}
	if !permission.CanAccess(user, accessRule) {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "you do not have permission to view this channel"})
	}

	query := "SELECT id, channel_id, user_id, username, content, created_at, is_deleted, deleted_by, edited_at, reply_to_id FROM messages WHERE channel_id = ? AND is_deleted = FALSE"
	args := []interface{}{channelID}

	if before := c.QueryParam("before"); before != "" {
		if bid, err := strconv.ParseInt(before, 10, 64); err == nil {
			query += " AND id < ?"
			args = append(args, bid)
		}
	}
	query += " ORDER BY id DESC LIMIT ?"
	args = append(args, limit)

	rows, err := h.db.Query(query, args...)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer rows.Close()

	var msgs []messageResp
	var userIDs []int
	userIDSet := map[int]bool{}
	for rows.Next() {
		var m messageResp
		var createdAt time.Time
		var editedAt sql.NullTime
		var deletedBy sql.NullInt64
		var replyToID sql.NullInt64
		if err := rows.Scan(&m.ID, &m.ChannelID, &m.UserID, &m.Username, &m.Content,
			&createdAt, &m.IsDeleted, &deletedBy, &editedAt, &replyToID); err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		m.CreatedAt = createdAt.UTC().Format("2006-01-02T15:04:05Z")
		if editedAt.Valid {
			s := editedAt.Time.UTC().Format("2006-01-02T15:04:05Z")
			m.EditedAt = &s
		}
		if deletedBy.Valid {
			m.DeletedBy = &deletedBy.Int64
		}
		if replyToID.Valid {
			m.ReplyToID = &replyToID.Int64
		}

		m.Attachments = h.loadAttachments(m.ID)
		m.ReplyTo = h.loadReplyTo(m.ReplyToID)
		m.Mentions = parseMentions(m.Content)
		m.Reactions = h.loadReactions(m.ID)

		uid := int(m.UserID)
		if !userIDSet[uid] {
			userIDSet[uid] = true
			userIDs = append(userIDs, uid)
		}
		msgs = append(msgs, m)
	}

	// Batch fetch avatars
	avatarMap := h.sso.GetAvatarURLsBatch(userIDs)
	// Reverse for chronological order and apply avatars
	for i, j := 0, len(msgs)-1; i < j; i, j = i+1, j-1 {
		msgs[i], msgs[j] = msgs[j], msgs[i]
	}
	for i := range msgs {
		if url, ok := avatarMap[int(msgs[i].UserID)]; ok {
			msgs[i].AvatarURL = &url
		}
	}

	if msgs == nil {
		msgs = []messageResp{}
	}
	return c.JSON(http.StatusOK, msgs)
}

func parseMentions(content string) []mentionResp {
	names := extractMentions(content)
	result := make([]mentionResp, 0, len(names))
	for _, name := range names {
		result = append(result, mentionResp{ID: 0, Username: name})
	}
	return result
}

// CreateMessage creates a new message.
// POST /api/channels/:channel_id/messages
func (h *MessageHandler) CreateMessage(c echo.Context) error {
	user := middleware.GetUser(c)
	channelID, err := strconv.ParseInt(c.Param("channel_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel id"})
	}

	var req messageCreateReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}
	if strings.TrimSpace(req.Content) == "" && len(req.AttachmentIDs) == 0 {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "message must have content or attachments"})
	}

	// Verify channel
	var chType string
	var speakMinLevel, speakPermMinLevel int
	var speakLogicOperator string
	err = h.db.QueryRow(
		"SELECT type, speak_min_level, speak_perm_min_level, speak_logic_operator FROM channels WHERE id = ?", channelID,
	).Scan(&chType, &speakMinLevel, &speakPermMinLevel, &speakLogicOperator)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	if chType != "TEXT" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a text channel"})
	}
	speakRule := permission.PermRule{PermMinLevel: speakPermMinLevel, GroupMinLevel: speakMinLevel, LogicOperator: speakLogicOperator}
	if !permission.CanSpeak(user, speakRule) {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "you do not have permission to speak in this channel"})
	}

	// Validate reply_to_id
	if req.ReplyToID != nil {
		var exists int
		if err := h.db.QueryRow("SELECT 1 FROM messages WHERE id = ? AND channel_id = ?", *req.ReplyToID, channelID).Scan(&exists); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "reply target message not found in this channel"})
		}
	}

	username := user.Nickname
	if username == "" {
		username = user.Username
	}

	res, err := h.db.Exec(
		"INSERT INTO messages (channel_id, user_id, username, content, reply_to_id) VALUES (?, ?, ?, ?, ?)",
		channelID, user.ID, username, req.Content, req.ReplyToID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	msgID, _ := res.LastInsertId()

	// Link attachments
	for _, attID := range req.AttachmentIDs {
		h.db.Exec(
			"UPDATE attachments SET message_id = ? WHERE id = ? AND channel_id = ? AND user_id = ? AND message_id IS NULL",
			msgID, attID, channelID, user.ID,
		)
	}

	// Store mentions
	for _, name := range extractMentions(req.Content) {
		// Look up user_id by username in messages for this server
		var mentionedUID int64
		err := h.db.QueryRow("SELECT DISTINCT user_id FROM messages WHERE username = ? LIMIT 1", name).Scan(&mentionedUID)
		if err == nil {
			h.db.Exec("INSERT INTO message_mentions (message_id, user_id) VALUES (?, ?)", msgID, mentionedUID)
		}
	}

	// Build response
	var createdAt time.Time
	h.db.QueryRow("SELECT created_at FROM messages WHERE id = ?", msgID).Scan(&createdAt)

	resp := messageResp{
		ID:          msgID,
		ChannelID:   channelID,
		UserID:      int64(user.ID),
		Username:    username,
		Content:     req.Content,
		CreatedAt:   createdAt.UTC().Format("2006-01-02T15:04:05Z"),
		Attachments: h.loadAttachments(msgID),
		ReplyToID:   req.ReplyToID,
		ReplyTo:     h.loadReplyTo(req.ReplyToID),
		Mentions:    parseMentions(req.Content),
		Reactions:   []reactionGroupResp{},
	}

	return c.JSON(http.StatusCreated, resp)
}

// EditMessage edits a message (author only).
// PATCH /api/channels/:channel_id/messages/:id
func (h *MessageHandler) EditMessage(c echo.Context) error {
	user := middleware.GetUser(c)
	channelID, err := strconv.ParseInt(c.Param("channel_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel id"})
	}
	msgID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid message id"})
	}

	var req messageEditReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}
	if strings.TrimSpace(req.Content) == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "content cannot be empty"})
	}

	var msgUserID int64
	var isDeleted bool
	err = h.db.QueryRow("SELECT user_id, is_deleted FROM messages WHERE id = ? AND channel_id = ?", msgID, channelID).
		Scan(&msgUserID, &isDeleted)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "message not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	if isDeleted {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "cannot edit deleted message"})
	}
	if msgUserID != int64(user.ID) {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "can only edit own messages"})
	}

	content := strings.TrimSpace(req.Content)
	_, err = h.db.Exec("UPDATE messages SET content = ?, edited_at = UTC_TIMESTAMP() WHERE id = ?", content, msgID)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	// Broadcast edit via WS
	if BroadcastFunc != nil {
		var editedAt string
		h.db.QueryRow("SELECT edited_at FROM messages WHERE id = ?", msgID).Scan(&editedAt)
		BroadcastFunc(channelID, map[string]interface{}{
			"type":       "message_edited",
			"message_id": msgID,
			"channel_id": channelID,
			"content":    content,
			"edited_at":  editedAt,
		})
	}

	// Re-fetch full message for response
	var m messageResp
	var createdAt time.Time
	var editedAt sql.NullTime
	var deletedBy sql.NullInt64
	var replyToID sql.NullInt64
	h.db.QueryRow(
		"SELECT id, channel_id, user_id, username, content, created_at, is_deleted, deleted_by, edited_at, reply_to_id FROM messages WHERE id = ?", msgID,
	).Scan(&m.ID, &m.ChannelID, &m.UserID, &m.Username, &m.Content, &createdAt, &m.IsDeleted, &deletedBy, &editedAt, &replyToID)
	m.CreatedAt = createdAt.UTC().Format("2006-01-02T15:04:05Z")
	if editedAt.Valid {
		s := editedAt.Time.UTC().Format("2006-01-02T15:04:05Z")
		m.EditedAt = &s
	}
	if deletedBy.Valid {
		m.DeletedBy = &deletedBy.Int64
	}
	if replyToID.Valid {
		m.ReplyToID = &replyToID.Int64
	}
	m.Attachments = h.loadAttachments(m.ID)
	m.ReplyTo = h.loadReplyTo(m.ReplyToID)
	m.Mentions = parseMentions(m.Content)
	m.Reactions = h.loadReactions(m.ID)

	return c.JSON(http.StatusOK, m)
}

// DeleteMessage soft-deletes a message (2min limit for non-admin).
// DELETE /api/channels/:channel_id/messages/:id
func (h *MessageHandler) DeleteMessage(c echo.Context) error {
	user := middleware.GetUser(c)
	channelID, err := strconv.ParseInt(c.Param("channel_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel id"})
	}
	msgID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid message id"})
	}

	var msgUserID int64
	var isDeleted bool
	var createdAt time.Time
	err = h.db.QueryRow("SELECT user_id, is_deleted, created_at FROM messages WHERE id = ? AND channel_id = ?", msgID, channelID).
		Scan(&msgUserID, &isDeleted, &createdAt)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "message not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	if isDeleted {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "message already deleted"})
	}

	isAdmin := permission.IsAdmin(user)
	isOwner := msgUserID == int64(user.ID)

	if !isAdmin && !isOwner {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "cannot delete others' messages"})
	}
	if !isAdmin {
		elapsed := time.Since(createdAt).Seconds()
		if elapsed > 120 {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "can only delete messages within 2 minutes"})
		}
	}

	_, err = h.db.Exec("UPDATE messages SET is_deleted = TRUE, deleted_at = UTC_TIMESTAMP(), deleted_by = ? WHERE id = ?", user.ID, msgID)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	// Broadcast delete via WS
	if BroadcastFunc != nil {
		username := user.Nickname
		if username == "" {
			username = user.Username
		}
		BroadcastFunc(channelID, map[string]interface{}{
			"type":                "message_deleted",
			"message_id":          msgID,
			"channel_id":          channelID,
			"deleted_by":          user.ID,
			"deleted_by_username": username,
		})
	}

	return c.NoContent(http.StatusNoContent)
}

// GetChannelMembers returns distinct users for @mention autocomplete.
// GET /api/channels/:channel_id/messages/members
func (h *MessageHandler) GetChannelMembers(c echo.Context) error {
	channelID, err := strconv.ParseInt(c.Param("channel_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel id"})
	}

	// Verify channel
	var chType string
	err = h.db.QueryRow("SELECT type FROM channels WHERE id = ?", channelID).Scan(&chType)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	if chType != "TEXT" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a text channel"})
	}

	limitStr := c.QueryParam("limit")
	limit := 50
	if limitStr != "" {
		if v, err := strconv.Atoi(limitStr); err == nil && v > 0 && v <= 100 {
			limit = v
		}
	}

	rows, err := h.db.Query(
		`SELECT m.user_id, m.username FROM messages m
		 INNER JOIN (
		     SELECT user_id, MAX(id) as max_id FROM messages
		     WHERE channel_id = ? AND is_deleted = FALSE GROUP BY user_id
		 ) sub ON m.id = sub.max_id
		 ORDER BY sub.max_id DESC LIMIT ?`,
		channelID, limit,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer rows.Close()

	type memberResp struct {
		ID       int64  `json:"id"`
		Username string `json:"username"`
	}
	var members []memberResp
	for rows.Next() {
		var m memberResp
		rows.Scan(&m.ID, &m.Username)
		members = append(members, m)
	}
	if members == nil {
		members = []memberResp{}
	}
	return c.JSON(http.StatusOK, members)
}
