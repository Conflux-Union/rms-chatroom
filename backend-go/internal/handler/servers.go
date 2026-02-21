package handler

import (
	"database/sql"
	"net/http"
	"strconv"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/middleware"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

// ServerHandler handles server CRUD and reorder endpoints.
type ServerHandler struct {
	db  *sql.DB
	sso *sso.Client
}

func NewServerHandler(db *sql.DB, sso *sso.Client) *ServerHandler {
	return &ServerHandler{db: db, sso: sso}
}

type serverCreateReq struct {
	Name string  `json:"name"`
	Icon *string `json:"icon"`
}

type serverUpdateReq struct {
	Name             *string `json:"name"`
	Icon             *string `json:"icon"`
	MinServerLevel   *int    `json:"min_server_level"`
	MinInternalLevel *int    `json:"min_internal_level"`
}

type serverResponse struct {
	ID               int64   `json:"id"`
	Name             string  `json:"name"`
	Icon             *string `json:"icon"`
	OwnerID          int64   `json:"owner_id"`
	MinServerLevel   int     `json:"min_server_level"`
	MinInternalLevel int     `json:"min_internal_level"`
}

type channelInServer struct {
	ID                         int64  `json:"id"`
	Name                       string `json:"name"`
	Type                       string `json:"type"`
	Position                   int    `json:"position"`
	TopPosition                int    `json:"top_position"`
	GroupID                    *int64 `json:"group_id"`
	VisibilityMinServerLevel   int    `json:"visibility_min_server_level"`
	VisibilityMinInternalLevel int    `json:"visibility_min_internal_level"`
	SpeakMinServerLevel        int    `json:"speak_min_server_level"`
	SpeakMinInternalLevel      int    `json:"speak_min_internal_level"`
}

type serverDetailResponse struct {
	serverResponse
	Channels []channelInServer `json:"channels"`
}

// ListServers returns servers filtered by user permission.
// GET /api/servers
func (h *ServerHandler) ListServers(c echo.Context) error {
	user := middleware.GetUser(c)

	rows, err := h.db.Query("SELECT id, name, icon, owner_id, min_server_level, min_internal_level FROM servers ORDER BY id")
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer rows.Close()

	var servers []serverResponse
	for rows.Next() {
		var s serverResponse
		if err := rows.Scan(&s.ID, &s.Name, &s.Icon, &s.OwnerID, &s.MinServerLevel, &s.MinInternalLevel); err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		if permission.CanAccessServer(user, s.MinServerLevel, s.MinInternalLevel) {
			servers = append(servers, s)
		}
	}
	if servers == nil {
		servers = []serverResponse{}
	}
	return c.JSON(http.StatusOK, servers)
}

// CreateServer creates a server with default channels.
// POST /api/servers
func (h *ServerHandler) CreateServer(c echo.Context) error {
	user := middleware.GetUser(c)
	var req serverCreateReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}
	if req.Name == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "name is required"})
	}

	tx, err := h.db.Begin()
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer tx.Rollback()

	res, err := tx.Exec(
		"INSERT INTO servers (name, icon, owner_id, min_server_level, min_internal_level) VALUES (?, ?, ?, 1, 1)",
		req.Name, req.Icon, user.ID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	serverID, _ := res.LastInsertId()

	// Create default text and voice channels
	_, err = tx.Exec(
		"INSERT INTO channels (server_id, name, type, position, top_position, visibility_min_server_level, visibility_min_internal_level, speak_min_server_level, speak_min_internal_level) VALUES (?, 'general', 'TEXT', 0, 0, 1, 1, 1, 1)",
		serverID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	_, err = tx.Exec(
		"INSERT INTO channels (server_id, name, type, position, top_position, visibility_min_server_level, visibility_min_internal_level, speak_min_server_level, speak_min_internal_level) VALUES (?, 'General', 'VOICE', 0, 1, 1, 1, 1, 1)",
		serverID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	if err := tx.Commit(); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	return c.JSON(http.StatusCreated, serverResponse{
		ID: serverID, Name: req.Name, Icon: req.Icon,
		OwnerID: int64(user.ID), MinServerLevel: 1, MinInternalLevel: 1,
	})
}

// GetServer returns server detail with filtered channels.
// GET /api/servers/:id
func (h *ServerHandler) GetServer(c echo.Context) error {
	user := middleware.GetUser(c)
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}

	var s serverResponse
	err = h.db.QueryRow("SELECT id, name, icon, owner_id, min_server_level, min_internal_level FROM servers WHERE id = ?", id).
		Scan(&s.ID, &s.Name, &s.Icon, &s.OwnerID, &s.MinServerLevel, &s.MinInternalLevel)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "server not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	if !permission.CanAccessServer(user, s.MinServerLevel, s.MinInternalLevel) {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "you do not have permission to access this server"})
	}

	rows, err := h.db.Query(
		"SELECT id, name, type, position, top_position, group_id, visibility_min_server_level, visibility_min_internal_level, speak_min_server_level, speak_min_internal_level FROM channels WHERE server_id = ? ORDER BY top_position, position",
		id,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer rows.Close()

	var channels []channelInServer
	for rows.Next() {
		var ch channelInServer
		if err := rows.Scan(&ch.ID, &ch.Name, &ch.Type, &ch.Position, &ch.TopPosition, &ch.GroupID,
			&ch.VisibilityMinServerLevel, &ch.VisibilityMinInternalLevel,
			&ch.SpeakMinServerLevel, &ch.SpeakMinInternalLevel); err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		if permission.CanSeeChannel(user, ch.VisibilityMinServerLevel, ch.VisibilityMinInternalLevel) {
			channels = append(channels, ch)
		}
	}
	if channels == nil {
		channels = []channelInServer{}
	}

	return c.JSON(http.StatusOK, serverDetailResponse{serverResponse: s, Channels: channels})
}

// UpdateServer updates server properties.
// PATCH /api/servers/:id  (also PUT for backward compat)
func (h *ServerHandler) UpdateServer(c echo.Context) error {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}

	var s serverResponse
	err = h.db.QueryRow("SELECT id, name, icon, owner_id, min_server_level, min_internal_level FROM servers WHERE id = ?", id).
		Scan(&s.ID, &s.Name, &s.Icon, &s.OwnerID, &s.MinServerLevel, &s.MinInternalLevel)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "server not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	var req serverUpdateReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}

	if req.Name != nil {
		s.Name = *req.Name
	}
	if req.Icon != nil {
		s.Icon = req.Icon
	}
	if req.MinServerLevel != nil {
		if *req.MinServerLevel < 1 || *req.MinServerLevel > 4 {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "min_server_level must be between 1 and 4"})
		}
		s.MinServerLevel = *req.MinServerLevel
	}
	if req.MinInternalLevel != nil {
		if *req.MinInternalLevel < 1 || *req.MinInternalLevel > 2 {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "min_internal_level must be 1 or 2"})
		}
		s.MinInternalLevel = *req.MinInternalLevel
	}

	_, err = h.db.Exec(
		"UPDATE servers SET name = ?, icon = ?, min_server_level = ?, min_internal_level = ? WHERE id = ?",
		s.Name, s.Icon, s.MinServerLevel, s.MinInternalLevel, id,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	return c.JSON(http.StatusOK, s)
}

// DeleteServer deletes a server.
// DELETE /api/servers/:id
func (h *ServerHandler) DeleteServer(c echo.Context) error {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}

	res, err := h.db.Exec("DELETE FROM servers WHERE id = ?", id)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "server not found"})
	}
	return c.NoContent(http.StatusNoContent)
}

type reorderItem struct {
	Type string `json:"type"` // "group" or "channel"
	ID   int64  `json:"id"`
}

type reorderTopLevelReq struct {
	Items []reorderItem `json:"items"`
}

// ReorderTopLevel reorders groups and ungrouped channels.
// POST /api/servers/:id/reorder
func (h *ServerHandler) ReorderTopLevel(c echo.Context) error {
	serverID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}

	var req reorderTopLevelReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}

	// Verify server exists
	var exists int
	if err := h.db.QueryRow("SELECT 1 FROM servers WHERE id = ?", serverID).Scan(&exists); err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "server not found"})
	}

	// Fetch existing groups
	groupRows, err := h.db.Query("SELECT id FROM channel_groups WHERE server_id = ?", serverID)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	groupIDs := map[int64]bool{}
	for groupRows.Next() {
		var gid int64
		groupRows.Scan(&gid)
		groupIDs[gid] = true
	}
	groupRows.Close()

	// Fetch ungrouped channels
	chRows, err := h.db.Query("SELECT id FROM channels WHERE server_id = ? AND group_id IS NULL", serverID)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	chIDs := map[int64]bool{}
	for chRows.Next() {
		var cid int64
		chRows.Scan(&cid)
		chIDs[cid] = true
	}
	chRows.Close()

	// Validate items
	seenGroups := map[int64]bool{}
	seenChannels := map[int64]bool{}
	for _, item := range req.Items {
		switch item.Type {
		case "group":
			if !groupIDs[item.ID] {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "group not found in server"})
			}
			if seenGroups[item.ID] {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "duplicate group in items"})
			}
			seenGroups[item.ID] = true
		case "channel":
			if !chIDs[item.ID] {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "ungrouped channel not found in server"})
			}
			if seenChannels[item.ID] {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "duplicate channel in items"})
			}
			seenChannels[item.ID] = true
		default:
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "item type must be 'group' or 'channel'"})
		}
	}

	if len(seenGroups) != len(groupIDs) {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "all groups must be included in items"})
	}
	if len(seenChannels) != len(chIDs) {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "all ungrouped channels must be included in items"})
	}

	tx, err := h.db.Begin()
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer tx.Rollback()

	for idx, item := range req.Items {
		if item.Type == "group" {
			tx.Exec("UPDATE channel_groups SET position = ? WHERE id = ?", idx, item.ID)
		} else {
			tx.Exec("UPDATE channels SET top_position = ? WHERE id = ?", idx, item.ID)
		}
	}

	if err := tx.Commit(); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return c.JSON(http.StatusOK, map[string]bool{"success": true})
}

// GetAllMessages returns messages from all text channels in a server.
// GET /api/servers/:id/all-messages
func (h *ServerHandler) GetAllMessages(c echo.Context) error {
	user := middleware.GetUser(c)
	serverID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}

	limitStr := c.QueryParam("limit")
	limit := 50
	if limitStr != "" {
		if v, err := strconv.Atoi(limitStr); err == nil && v > 0 && v <= 200 {
			limit = v
		}
	}

	// Verify server
	var exists int
	if err := h.db.QueryRow("SELECT 1 FROM servers WHERE id = ?", serverID).Scan(&exists); err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "server not found"})
	}

	// Get text channels
	chRows, err := h.db.Query(
		"SELECT id, name, visibility_min_server_level, visibility_min_internal_level FROM channels WHERE server_id = ? AND type = 'TEXT' ORDER BY position",
		serverID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer chRows.Close()

	type channelInfo struct {
		ID                         int64
		Name                       string
		VisibilityMinServerLevel   int
		VisibilityMinInternalLevel int
	}
	var textChannels []channelInfo
	for chRows.Next() {
		var ch channelInfo
		chRows.Scan(&ch.ID, &ch.Name, &ch.VisibilityMinServerLevel, &ch.VisibilityMinInternalLevel)
		if permission.CanSeeChannel(user, ch.VisibilityMinServerLevel, ch.VisibilityMinInternalLevel) {
			textChannels = append(textChannels, ch)
		}
	}

	type msgResp struct {
		ID        int64   `json:"id"`
		ChannelID int64   `json:"channel_id"`
		UserID    int64   `json:"user_id"`
		Username  string  `json:"username"`
		AvatarURL *string `json:"avatar_url"`
		Content   string  `json:"content"`
		CreatedAt string  `json:"created_at"`
		IsDeleted bool    `json:"is_deleted"`
		EditedAt  *string `json:"edited_at"`
	}

	type channelMsgsResp struct {
		ChannelID   int64     `json:"channel_id"`
		ChannelName string    `json:"channel_name"`
		Messages    []msgResp `json:"messages"`
	}

	result := make([]channelMsgsResp, 0, len(textChannels))
	for _, ch := range textChannels {
		rows, err := h.db.Query(
			"SELECT id, channel_id, user_id, username, content, created_at, is_deleted, edited_at FROM messages WHERE channel_id = ? AND is_deleted = FALSE ORDER BY id DESC LIMIT ?",
			ch.ID, limit,
		)
		if err != nil {
			continue
		}

		var msgs []msgResp
		for rows.Next() {
			var m msgResp
			var createdAt time.Time
			var editedAt sql.NullTime
			rows.Scan(&m.ID, &m.ChannelID, &m.UserID, &m.Username, &m.Content, &createdAt, &m.IsDeleted, &editedAt)
			m.CreatedAt = createdAt.UTC().Format("2006-01-02T15:04:05Z")
			if editedAt.Valid {
				s := editedAt.Time.UTC().Format("2006-01-02T15:04:05Z")
				m.EditedAt = &s
			}
			msgs = append(msgs, m)
		}
		rows.Close()

		// Reverse for chronological order
		for i, j := 0, len(msgs)-1; i < j; i, j = i+1, j-1 {
			msgs[i], msgs[j] = msgs[j], msgs[i]
		}
		if msgs == nil {
			msgs = []msgResp{}
		}

		result = append(result, channelMsgsResp{ChannelID: ch.ID, ChannelName: ch.Name, Messages: msgs})
	}

	return c.JSON(http.StatusOK, result)
}
