package handler

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/metrics"
	mw "github.com/RMS-Server/rms-discord-go/internal/middleware"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

// ForwardHandler serves the message-sync API for FORWARD channels: the QQ
// bot's HTTP routes (static Bearer token, config forward_bot_token) and the
// ChatBridge game-network inbound path.
type ForwardHandler struct {
	db        *sql.DB
	sso       *sso.Client
	uploadDir string
}

func NewForwardHandler(db *sql.DB, ssoClient *sso.Client, uploadDir string) *ForwardHandler {
	return &ForwardHandler{db: db, sso: ssoClient, uploadDir: uploadDir}
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

// forwardMentionReq is one @-mention in a forwarded QQ message. QQ is the
// mentioned user's QQ number; Name is the @text as it appears in Content
// (e.g. the QQ nickname), used to locate the mention for rewriting.
type forwardMentionReq struct {
	QQ   int64  `json:"qq"`
	Name string `json:"name"`
}

type forwardMessageReq struct {
	Source          string              `json:"source"` // "qq" (game arrives via ChatBridge)
	Sender          forwardSenderReq    `json:"sender"`
	Content         string              `json:"content"`
	SourceMessageID string              `json:"source_message_id"`
	Reply           *forwardQuoteReq    `json:"reply"`
	AttachmentIDs   []int64             `json:"attachment_ids"`
	Mentions        []forwardMentionReq `json:"mentions"`
}

// forwardQuoteMeta is the degraded quote block shown when the quoted source
// message has no matching forwarded message in this channel.
type forwardQuoteMeta struct {
	Nickname string `json:"nickname"`
	Content  string `json:"content"`
}

type forwardMeta struct {
	SenderNickname string `json:"sender_nickname,omitempty"`
	// Server is the ChatBridge origin server for game-sourced messages.
	Server string            `json:"server,omitempty"`
	Quote  *forwardQuoteMeta `json:"quote,omitempty"`
}

// forwardedMessage is one message entering the platform from an external
// chat source: the QQ sync bot (HTTP API) or the ChatBridge game network.
type forwardedMessage struct {
	Source          string // "qq" or "game"
	Sender          forwardSenderReq
	Content         string
	SourceMessageID string
	Reply           *forwardQuoteReq
	AttachmentIDs   []int64
	Mentions        []forwardMentionReq
	Server          string // ChatBridge origin server name (game only)
}

// ghostUserID marks forwarded messages whose sender has no platform account
// (unmatched QQ/game sender, or a game system event). There is no FK on
// messages.user_id — accounts live in SSO, not this database — so 0 can never
// collide with a real account and clients render the username with a
// first-letter avatar fallback.
const ghostUserID int64 = 0

// resolveAuthor maps the external sender to a platform account. QQ senders are
// matched by the SSO account email (<qq>@qq.com), game events by username.
// Unmatched senders post under the ghost user with "昵称(未知用户)" as the
// display name; game system events (no author, e.g. "Steve joined smp") post
// under the plain server name instead.
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

	// System events have no player to resolve; keep the server name clean.
	if source == "game" && sender.Username == "" {
		return ghostUserID, nickname, ""
	}

	// Fallback: post under the ghost user with the original nickname. The
	// messages.username column carries "昵称(未知用户)" so the original
	// sender stays visible even though the message belongs to no account.
	return ghostUserID, nickname + "(未知用户)", ""
}

var errNotForwardChannel = errors.New("not a forward channel")

// resolveMentions maps @-mentions of a forwarded message to platform accounts
// the same way resolveAuthor maps the sender: the SSO account email is
// <qq>@qq.com. Matched mentions are rewritten in the content (@QQ昵称 ->
// @platform name) so the highlighted text carries the platform identity, and
// the returned entries carry real user IDs for the message_mentions records
// and the live broadcast. Mentions whose QQ number has no SSO account are
// left untouched — same treatment as unmatched senders.
func (h *ForwardHandler) resolveMentions(content string, mentions []forwardMentionReq) (string, []mentionResp) {
	resolved := make([]mentionResp, 0, len(mentions))
	seen := map[int64]bool{}
	for _, m := range mentions {
		if m.QQ == 0 {
			continue
		}
		user, _ := h.sso.GetUserByEmail(fmt.Sprintf("%d@qq.com", m.QQ))
		if user == nil {
			continue
		}
		display := user.Nickname
		if display == "" {
			display = user.Username
		}
		if m.Name != "" && m.Name != display {
			content = strings.Replace(content, "@"+m.Name, "@"+display, 1)
		}
		if !seen[int64(user.ID)] {
			seen[int64(user.ID)] = true
			resolved = append(resolved, mentionResp{ID: int64(user.ID), Username: display})
		}
	}
	return content, resolved
}

// verifyForwardChannel checks that channelID exists and is a FORWARD channel.
// Returns sql.ErrNoRows when the channel does not exist.
func verifyForwardChannel(db *sql.DB, channelID int64) error {
	var chType string
	err := db.QueryRow("SELECT type FROM channels WHERE id = ?", channelID).Scan(&chType)
	if err != nil {
		return err
	}
	if chType != "FORWARD" {
		return errNotForwardChannel
	}
	return nil
}

// PostMessage inserts a forwarded QQ message into a FORWARD channel.
// POST /api/forward/channels/:channel_id/messages
//
// Game-sourced messages used to arrive here too; they now come in over
// ChatBridge (see PostGameMessage).
func (h *ForwardHandler) PostMessage(c echo.Context) error {
	channelID, err := strconv.ParseInt(c.Param("channel_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel id"})
	}

	var req forwardMessageReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}
	if req.Source != "qq" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "source must be qq"})
	}
	if strings.TrimSpace(req.Content) == "" && len(req.AttachmentIDs) == 0 {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "message must have content or attachments"})
	}

	resp, err := h.postForwarded(channelID, forwardedMessage{
		Source:          req.Source,
		Sender:          req.Sender,
		Content:         req.Content,
		SourceMessageID: req.SourceMessageID,
		Reply:           req.Reply,
		AttachmentIDs:   req.AttachmentIDs,
		Mentions:        req.Mentions,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
	}
	if errors.Is(err, errNotForwardChannel) {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return c.JSON(http.StatusCreated, resp)
}

// PostGameMessage inserts one chat line received over ChatBridge into a
// FORWARD channel. author is the in-game player name; an empty author marks
// a system event (player joined/left, server start/stop), which posts under
// the originating server's name. The message is broadcast live like any
// other forwarded message.
func (h *ForwardHandler) PostGameMessage(channelID int64, server, author, message string) error {
	sender := forwardSenderReq{Username: author, Nickname: author}
	if author == "" {
		sender = forwardSenderReq{Nickname: server}
	}
	_, err := h.postForwarded(channelID, forwardedMessage{
		Source:  "game",
		Sender:  sender,
		Content: message,
		Server:  server,
	})
	if err != nil && !errors.Is(err, sql.ErrNoRows) && !errors.Is(err, errNotForwardChannel) {
		return err
	}
	return nil
}

// postForwarded persists a forwarded message and broadcasts it live. It is
// the single insertion path shared by the QQ bot API and ChatBridge.
func (h *ForwardHandler) postForwarded(channelID int64, m forwardedMessage) (*messageResp, error) {
	if err := verifyForwardChannel(h.db, channelID); err != nil {
		return nil, err
	}

	authorID, username, avatarURL := h.resolveAuthor(m.Source, m.Sender)
	content, mentions := h.resolveMentions(m.Content, m.Mentions)
	nickname := m.Sender.Nickname
	if nickname == "" {
		nickname = m.Sender.Username
	}

	meta := forwardMeta{SenderNickname: nickname, Server: m.Server}

	// Resolve the quote: a matching already-forwarded message becomes a real
	// reply; anything else degrades to a quote block in forward_meta.
	var replyToID sql.NullInt64
	if m.Reply != nil && m.Reply.SourceMessageID != "" {
		var targetID int64
		err := h.db.QueryRow(
			"SELECT id FROM messages WHERE channel_id = ? AND source_platform = ? AND source_message_id = ? AND is_deleted = FALSE",
			channelID, m.Source, m.Reply.SourceMessageID,
		).Scan(&targetID)
		if err == nil {
			replyToID = sql.NullInt64{Int64: targetID, Valid: true}
		} else {
			meta.Quote = &forwardQuoteMeta{Nickname: m.Reply.Nickname, Content: m.Reply.Content}
		}
	}

	var metaJSON []byte
	if meta.SenderNickname != "" || meta.Quote != nil || meta.Server != "" {
		metaJSON, _ = json.Marshal(meta)
	}

	res, err := h.db.Exec(
		`INSERT INTO messages (channel_id, user_id, username, content, reply_to_id, source_platform, source_message_id, forward_meta)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		channelID, authorID, username, content, replyToID, m.Source, m.SourceMessageID, metaJSON,
	)
	if err != nil {
		return nil, err
	}
	msgID, _ := res.LastInsertId()

	// Record resolved mentions for the mentioned platform users. Same table
	// and id semantics as the text-channel mention paths.
	for _, mt := range mentions {
		h.db.Exec("INSERT IGNORE INTO message_mentions (message_id, user_id) VALUES (?, ?)", msgID, mt.ID)
	}

	// Attachments were uploaded through the bot route, so they belong to the
	// ghost user regardless of which user the message is posted as.
	for _, attID := range m.AttachmentIDs {
		h.db.Exec(
			"UPDATE attachments SET message_id = ? WHERE id = ? AND channel_id = ? AND user_id = ? AND message_id IS NULL",
			msgID, attID, channelID, ghostUserID,
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
		Content:        content,
		CreatedAt:      createdAt.UTC().Format("2006-01-02T15:04:05Z"),
		Attachments:    msgH.loadAttachments(msgID),
		ReplyToID:      replyToPtr,
		ReplyTo:        msgH.loadReplyTo(replyToPtr),
		Mentions:       mentions,
		Reactions:      []reactionGroupResp{},
		SourcePlatform: m.Source,
	}
	if avatarURL != "" {
		resp.AvatarURL = &avatarURL
	}
	if metaJSON != nil {
		resp.ForwardMeta = json.RawMessage(metaJSON)
	}

	// REST CreateMessage doesn't broadcast, but forwarded messages must appear
	// live: neither the QQ bot nor ChatBridge holds a WS connection. Payload
	// matches the ws chatBroadcast shape plus the forward-specific fields.
	// Mentions go out as {id, username} objects with real user IDs — both
	// clients detect "I was mentioned" by comparing those IDs.
	if BroadcastFunc != nil {
		payload := map[string]interface{}{
			"type":            "message",
			"id":              msgID,
			"channel_id":      channelID,
			"user_id":         authorID,
			"username":        username,
			"content":         content,
			"created_at":      resp.CreatedAt,
			"attachments":     resp.Attachments,
			"mentions":        mentions,
			"source_platform": m.Source,
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
	return &resp, nil
}

// Upload stores a media file for a forwarded message. The attachment is owned
// by the ghost user and linked to the message afterwards via attachment_ids.
// POST /api/forward/channels/:channel_id/upload
func (h *ForwardHandler) Upload(c echo.Context) error {
	channelID, err := strconv.ParseInt(c.Param("channel_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel_id"})
	}

	if err := verifyForwardChannel(h.db, channelID); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
		}
		if errors.Is(err, errNotForwardChannel) {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
		}
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
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
	id, _, err := saveAttachment(h.db, h.uploadDir, channelID, ghostUserID, prepared)
	if err != nil {
		mw.ReqLogf(c, "handler/forward: failed to save attachment: %v", err)
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
