package handler

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/metrics"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

// ForwardHandler serves the bot-only message-sync API for FORWARD channels.
// The bot authenticates with a static Bearer token (config forward_bot_token)
// instead of a user JWT.
type ForwardHandler struct {
	db        *sql.DB
	sso       *sso.Client
	botUserID int64
	uploadDir string
}

func NewForwardHandler(db *sql.DB, ssoClient *sso.Client, botUserID int64, uploadDir string) *ForwardHandler {
	return &ForwardHandler{db: db, sso: ssoClient, botUserID: botUserID, uploadDir: uploadDir}
}

type forwardSenderReq struct {
	QQ       int64  `json:"qq"`
	Username string `json:"username"`
	Nickname string `json:"nickname"`
}

type forwardQuoteReq struct {
	SourceMessageID string `json:"source_message_id"`
	Nickname        string `json:"nickname"`
	Content         string `json:"content"`
}

type forwardMessageReq struct {
	Source          string           `json:"source"` // "qq" or "game"
	Sender          forwardSenderReq `json:"sender"`
	Content         string           `json:"content"`
	SourceMessageID string           `json:"source_message_id"`
	Reply           *forwardQuoteReq `json:"reply"`
	AttachmentIDs   []int64          `json:"attachment_ids"`
}

// forwardQuoteMeta is the degraded quote block shown when the quoted source
// message has no matching forwarded message in this channel.
type forwardQuoteMeta struct {
	Nickname string `json:"nickname"`
	Content  string `json:"content"`
}

type forwardMeta struct {
	SenderNickname string            `json:"sender_nickname,omitempty"`
	Quote          *forwardQuoteMeta `json:"quote,omitempty"`
}

// resolveAuthor maps the external sender to a platform account. QQ senders are
// matched by the SSO account email (<qq>@qq.com), game events by username.
// Unmatched senders fall back to the bot proxy account with
// "昵称(未知用户)" as the display name.
func (h *ForwardHandler) resolveAuthor(source string, sender forwardSenderReq) (userID int64, username string, avatarURL string) {
	nickname := sender.Nickname
	if nickname == "" {
		nickname = sender.Username
	}

	var user *permission.UserInfo
	if source == "qq" && sender.QQ != 0 {
		user, _ = h.sso.GetUserByEmail(fmt.Sprintf("%d@qq.com", sender.QQ))
	} else if source == "game" && sender.Username != "" {
		user, _ = h.sso.GetUserByUsername(sender.Username)
	}

	if user != nil {
		name := user.Nickname
		if name == "" {
			name = user.Username
		}
		return int64(user.ID), name, user.AvatarURL
	}

	// Fallback: post as the bot account under the original nickname. The
	// messages.username column carries "昵称(未知用户)" so the original
	// sender stays visible even though user_id is the bot.
	return h.botUserID, nickname + "(未知用户)", ""
}

// PostMessage inserts a forwarded message into a FORWARD channel.
// POST /api/forward/channels/:channel_id/messages
func (h *ForwardHandler) PostMessage(c echo.Context) error {
	channelID, err := strconv.ParseInt(c.Param("channel_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel id"})
	}

	var req forwardMessageReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}
	if req.Source != "qq" && req.Source != "game" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "source must be qq or game"})
	}
	if strings.TrimSpace(req.Content) == "" && len(req.AttachmentIDs) == 0 {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "message must have content or attachments"})
	}

	// Verify channel exists and is a FORWARD channel
	var chType string
	err = h.db.QueryRow("SELECT type FROM channels WHERE id = ?", channelID).Scan(&chType)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	if chType != "FORWARD" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a forward channel"})
	}

	authorID, username, avatarURL := h.resolveAuthor(req.Source, req.Sender)
	nickname := req.Sender.Nickname
	if nickname == "" {
		nickname = req.Sender.Username
	}

	meta := forwardMeta{SenderNickname: nickname}

	// Resolve the quote: a matching already-forwarded message becomes a real
	// reply; anything else degrades to a quote block in forward_meta.
	var replyToID sql.NullInt64
	if req.Reply != nil && req.Reply.SourceMessageID != "" {
		var targetID int64
		err := h.db.QueryRow(
			"SELECT id FROM messages WHERE channel_id = ? AND source_platform = ? AND source_message_id = ? AND is_deleted = FALSE",
			channelID, req.Source, req.Reply.SourceMessageID,
		).Scan(&targetID)
		if err == nil {
			replyToID = sql.NullInt64{Int64: targetID, Valid: true}
		} else {
			meta.Quote = &forwardQuoteMeta{Nickname: req.Reply.Nickname, Content: req.Reply.Content}
		}
	}

	var metaJSON []byte
	if meta.SenderNickname != "" || meta.Quote != nil {
		metaJSON, _ = json.Marshal(meta)
	}

	res, err := h.db.Exec(
		`INSERT INTO messages (channel_id, user_id, username, content, reply_to_id, source_platform, source_message_id, forward_meta)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		channelID, authorID, username, req.Content, replyToID, req.Source, req.SourceMessageID, metaJSON,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	msgID, _ := res.LastInsertId()

	// Attachments were uploaded through the bot route, so they belong to the
	// bot account regardless of which user the message is posted as.
	for _, attID := range req.AttachmentIDs {
		h.db.Exec(
			"UPDATE attachments SET message_id = ? WHERE id = ? AND channel_id = ? AND user_id = ? AND message_id IS NULL",
			msgID, attID, channelID, h.botUserID,
		)
	}

	msgH := &MessageHandler{db: h.db, sso: h.sso}
	var createdAt time.Time
	h.db.QueryRow("SELECT created_at FROM messages WHERE id = ?", msgID).Scan(&createdAt)

	var replyToPtr *int64
	if replyToID.Valid {
		id := replyToID.Int64
		replyToPtr = &id
	}

	resp := messageResp{
		ID:             msgID,
		ChannelID:      channelID,
		UserID:         authorID,
		Username:       username,
		AvatarURL:      nil,
		Content:        req.Content,
		CreatedAt:      createdAt.UTC().Format("2006-01-02T15:04:05Z"),
		Attachments:    msgH.loadAttachments(msgID),
		ReplyToID:      replyToPtr,
		ReplyTo:        msgH.loadReplyTo(replyToPtr),
		Mentions:       []mentionResp{},
		Reactions:      []reactionGroupResp{},
		SourcePlatform: req.Source,
	}
	if avatarURL != "" {
		resp.AvatarURL = &avatarURL
	}
	if metaJSON != nil {
		resp.ForwardMeta = json.RawMessage(metaJSON)
	}

	// REST CreateMessage doesn't broadcast, but forwarded messages must appear
	// live: the bot has no WS connection. Payload matches the ws chatBroadcast
	// shape plus the forward-specific fields.
	if BroadcastFunc != nil {
		payload := map[string]interface{}{
			"type":           "message",
			"id":             msgID,
			"channel_id":     channelID,
			"user_id":        authorID,
			"username":       username,
			"content":        req.Content,
			"created_at":     resp.CreatedAt,
			"attachments":    resp.Attachments,
			"mentions":       []string{},
			"source_platform": req.Source,
		}
		if resp.ReplyTo != nil {
			payload["reply_to_id"] = *resp.ReplyToID
			payload["reply_to"] = resp.ReplyTo
		}
		if avatarURL != "" {
			payload["avatar_url"] = avatarURL
		}
		if metaJSON != nil {
			payload["forward_meta"] = json.RawMessage(metaJSON)
		}
		BroadcastFunc(channelID, payload)
	}

	metrics.MessagesCreated.Inc()
	return c.JSON(http.StatusCreated, resp)
}

// Upload stores a media file for a forwarded message. The attachment is owned
// by the bot account and linked to the message afterwards via attachment_ids.
// POST /api/forward/channels/:channel_id/upload
func (h *ForwardHandler) Upload(c echo.Context) error {
	channelID, err := strconv.ParseInt(c.Param("channel_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel_id"})
	}

	var chType string
	err = h.db.QueryRow("SELECT type FROM channels WHERE id = ?", channelID).Scan(&chType)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	if chType != "FORWARD" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a forward channel"})
	}

	fh, err := c.FormFile("file")
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "no file provided"})
	}

	safeName := sanitizeFilename(fh.Filename)
	ext := strings.ToLower(filepath.Ext(safeName))
	if blockedExtensions[ext] {
		return c.JSON(http.StatusBadRequest, map[string]string{
			"error": fmt.Sprintf("file type %s is not allowed", ext),
		})
	}
	if fh.Size == 0 {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "empty file"})
	}
	if fh.Size > maxFileSize {
		return c.JSON(http.StatusRequestEntityTooLarge, map[string]string{
			"error": fmt.Sprintf("file too large, max %dMB", maxFileSize/1024/1024),
		})
	}

	src, err := fh.Open()
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to read file"})
	}
	defer src.Close()

	content, err := io.ReadAll(src)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to read file"})
	}

	prepared := prepareUpload(c.Request().Context(), safeName, fh.Header.Get("Content-Type"), content)
	id, _, err := saveAttachment(h.db, h.uploadDir, channelID, h.botUserID, prepared)
	if err != nil {
		log.Printf("handler/forward: failed to save attachment: %v", err)
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to create record"})
	}

	return c.JSON(http.StatusOK, map[string]interface{}{
		"id":           id,
		"filename":     prepared.filename,
		"content_type": prepared.contentType,
		"size":         len(prepared.content),
		"url":          fmt.Sprintf("/api/files/%d", id),
	})
}
