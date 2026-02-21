package handler

import (
	"database/sql"
	"net/http"
	"strconv"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/middleware"
	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

// ChannelGroupHandler handles channel group CRUD endpoints.
type ChannelGroupHandler struct {
	db  *sql.DB
	sso *sso.Client
}

func NewChannelGroupHandler(db *sql.DB, sso *sso.Client) *ChannelGroupHandler {
	return &ChannelGroupHandler{db: db, sso: sso}
}

type channelGroupCreateReq struct {
	Name     string `json:"name"`
	MinLevel int    `json:"min_level"`
}

type channelGroupUpdateReq struct {
	Name     *string `json:"name"`
	MinLevel *int    `json:"min_level"`
}

type channelGroupResponse struct {
	ID       int64  `json:"id"`
	ServerID int64  `json:"server_id"`
	Name     string `json:"name"`
	Position int    `json:"position"`
	MinLevel int    `json:"min_level"`
}

// ListChannelGroups returns groups filtered by user permission.
// GET /api/servers/:server_id/channel-groups
func (h *ChannelGroupHandler) ListChannelGroups(c echo.Context) error {
	user := middleware.GetUser(c)
	serverID, err := strconv.ParseInt(c.Param("server_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}

	rows, err := h.db.Query(
		"SELECT id, server_id, name, position, min_level FROM channel_groups WHERE server_id = ? ORDER BY position",
		serverID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer rows.Close()

	var groups []channelGroupResponse
	for rows.Next() {
		var g channelGroupResponse
		if err := rows.Scan(&g.ID, &g.ServerID, &g.Name, &g.Position, &g.MinLevel); err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		if permission.CanAccess(user, g.MinLevel) {
			groups = append(groups, g)
		}
	}
	if groups == nil {
		groups = []channelGroupResponse{}
	}
	return c.JSON(http.StatusOK, groups)
}

// CreateChannelGroup creates a new channel group.
// POST /api/servers/:server_id/channel-groups
func (h *ChannelGroupHandler) CreateChannelGroup(c echo.Context) error {
	serverID, err := strconv.ParseInt(c.Param("server_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}

	var req channelGroupCreateReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}
	if req.Name == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "name is required"})
	}

	// Verify server exists
	var exists int
	if err := h.db.QueryRow("SELECT 1 FROM servers WHERE id = ?", serverID).Scan(&exists); err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "server not found"})
	}

	// Get max top-level position
	var groupMax, chMax sql.NullInt64
	h.db.QueryRow("SELECT MAX(position) FROM channel_groups WHERE server_id = ?", serverID).Scan(&groupMax)
	h.db.QueryRow("SELECT MAX(top_position) FROM channels WHERE server_id = ? AND group_id IS NULL", serverID).Scan(&chMax)
	gm, cm := int64(-1), int64(-1)
	if groupMax.Valid {
		gm = groupMax.Int64
	}
	if chMax.Valid {
		cm = chMax.Int64
	}
	maxPos := gm
	if cm > maxPos {
		maxPos = cm
	}
	position := int(maxPos) + 1

	res, err := h.db.Exec(
		"INSERT INTO channel_groups (server_id, name, position, min_level) VALUES (?, ?, ?, ?)",
		serverID, req.Name, position, req.MinLevel,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	gID, _ := res.LastInsertId()

	return c.JSON(http.StatusCreated, channelGroupResponse{
		ID: gID, ServerID: serverID, Name: req.Name,
		Position: position, MinLevel: req.MinLevel,
	})
}

// UpdateChannelGroup updates group properties.
// PATCH /api/servers/:server_id/channel-groups/:id
func (h *ChannelGroupHandler) UpdateChannelGroup(c echo.Context) error {
	serverID, err := strconv.ParseInt(c.Param("server_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}
	groupID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid group id"})
	}

	var g channelGroupResponse
	err = h.db.QueryRow(
		"SELECT id, server_id, name, position, min_level FROM channel_groups WHERE id = ? AND server_id = ?",
		groupID, serverID,
	).Scan(&g.ID, &g.ServerID, &g.Name, &g.Position, &g.MinLevel)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel group not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	var req channelGroupUpdateReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}

	if req.Name != nil {
		g.Name = *req.Name
	}
	if req.MinLevel != nil {
		if *req.MinLevel < 0 {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "min_level must be >= 0"})
		}
		g.MinLevel = *req.MinLevel
	}

	_, err = h.db.Exec(
		"UPDATE channel_groups SET name = ?, min_level = ? WHERE id = ?",
		g.Name, g.MinLevel, groupID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	return c.JSON(http.StatusOK, g)
}

type reorderGroupChannelsReq struct {
	ChannelIDs []int64 `json:"channel_ids"`
}

// ReorderGroupChannels reorders channels within a group.
// POST /api/servers/:server_id/channel-groups/:id/reorder-channels
func (h *ChannelGroupHandler) ReorderGroupChannels(c echo.Context) error {
	serverID, err := strconv.ParseInt(c.Param("server_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}
	groupID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid group id"})
	}

	// Verify group exists
	var exists int
	if err := h.db.QueryRow("SELECT 1 FROM channel_groups WHERE id = ? AND server_id = ?", groupID, serverID).Scan(&exists); err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel group not found"})
	}

	var req reorderGroupChannelsReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}

	// Fetch channels in group
	rows, err := h.db.Query("SELECT id FROM channels WHERE server_id = ? AND group_id = ?", serverID, groupID)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	existingIDs := map[int64]bool{}
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		existingIDs[id] = true
	}
	rows.Close()

	providedSet := map[int64]bool{}
	for _, id := range req.ChannelIDs {
		if !existingIDs[id] {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "channel_ids must refer to channels in this group"})
		}
		if providedSet[id] {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "duplicate channel id"})
		}
		providedSet[id] = true
	}
	if len(providedSet) != len(existingIDs) {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "all channel IDs in the group must be provided"})
	}

	tx, err := h.db.Begin()
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer tx.Rollback()

	for idx, id := range req.ChannelIDs {
		tx.Exec("UPDATE channels SET position = ? WHERE id = ?", idx, id)
	}
	if err := tx.Commit(); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	return c.JSON(http.StatusOK, map[string]bool{"success": true})
}

// DeleteChannelGroup deletes a group, ungrouping its channels.
// DELETE /api/servers/:server_id/channel-groups/:id
func (h *ChannelGroupHandler) DeleteChannelGroup(c echo.Context) error {
	serverID, err := strconv.ParseInt(c.Param("server_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}
	groupID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid group id"})
	}

	var exists int
	if err := h.db.QueryRow("SELECT 1 FROM channel_groups WHERE id = ? AND server_id = ?", groupID, serverID).Scan(&exists); err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel group not found"})
	}

	// Get max top-level position for ungrouping channels
	var groupMax, chMax sql.NullInt64
	h.db.QueryRow("SELECT MAX(position) FROM channel_groups WHERE server_id = ?", serverID).Scan(&groupMax)
	h.db.QueryRow("SELECT MAX(top_position) FROM channels WHERE server_id = ? AND group_id IS NULL", serverID).Scan(&chMax)
	gm, cm := int64(-1), int64(-1)
	if groupMax.Valid {
		gm = groupMax.Int64
	}
	if chMax.Valid {
		cm = chMax.Int64
	}
	maxPos := gm
	if cm > maxPos {
		maxPos = cm
	}

	tx, err := h.db.Begin()
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer tx.Rollback()

	// Ungroup channels
	rows, err := tx.Query("SELECT id FROM channels WHERE group_id = ? ORDER BY position", groupID)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	var channelIDs []int64
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		channelIDs = append(channelIDs, id)
	}
	rows.Close()

	for i, id := range channelIDs {
		tx.Exec("UPDATE channels SET group_id = NULL, top_position = ?, position = 0 WHERE id = ?", int(maxPos)+1+i, id)
	}

	tx.Exec("DELETE FROM channel_groups WHERE id = ?", groupID)

	if err := tx.Commit(); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	return c.NoContent(http.StatusNoContent)
}
