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

// ChannelHandler handles channel CRUD endpoints.
type ChannelHandler struct {
	db  *sql.DB
	sso *sso.Client
}

func NewChannelHandler(db *sql.DB, sso *sso.Client) *ChannelHandler {
	return &ChannelHandler{db: db, sso: sso}
}

type channelCreateReq struct {
	Name               string `json:"name"`
	Type               string `json:"type"`
	GroupID            *int64 `json:"group_id"`
	MinLevel           int    `json:"min_level"`
	SpeakMinLevel      int    `json:"speak_min_level"`
	PermMinLevel       int    `json:"perm_min_level"`
	LogicOperator      string `json:"logic_operator"`
	SpeakPermMinLevel  int    `json:"speak_perm_min_level"`
	SpeakLogicOperator string `json:"speak_logic_operator"`
}

type channelUpdateReq struct {
	Name               *string `json:"name"`
	GroupID            *int64  `json:"group_id"`
	MinLevel           *int    `json:"min_level"`
	SpeakMinLevel      *int    `json:"speak_min_level"`
	PermMinLevel       *int    `json:"perm_min_level"`
	LogicOperator      *string `json:"logic_operator"`
	SpeakPermMinLevel  *int    `json:"speak_perm_min_level"`
	SpeakLogicOperator *string `json:"speak_logic_operator"`
}

type channelResponse struct {
	ID                 int64  `json:"id"`
	ServerID           int64  `json:"server_id"`
	GroupID            *int64 `json:"group_id"`
	Name               string `json:"name"`
	Type               string `json:"type"`
	Position           int    `json:"position"`
	TopPosition        int    `json:"top_position"`
	MinLevel           int    `json:"min_level"`
	SpeakMinLevel      int    `json:"speak_min_level"`
	PermMinLevel       int    `json:"perm_min_level"`
	LogicOperator      string `json:"logic_operator"`
	SpeakPermMinLevel  int    `json:"speak_perm_min_level"`
	SpeakLogicOperator string `json:"speak_logic_operator"`
}

// ListChannels returns channels filtered by user visibility.
// GET /api/servers/:server_id/channels
func (h *ChannelHandler) ListChannels(c echo.Context) error {
	user := middleware.GetUser(c)
	serverID, err := strconv.ParseInt(c.Param("server_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}

	rows, err := h.db.Query(
		`SELECT id, server_id, group_id, name, type, position, top_position,
		        min_level, speak_min_level,
		        perm_min_level, logic_operator, speak_perm_min_level, speak_logic_operator
		 FROM channels WHERE server_id = ? ORDER BY position`, serverID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	defer rows.Close()

	var channels []channelResponse
	for rows.Next() {
		var ch channelResponse
		if err := rows.Scan(&ch.ID, &ch.ServerID, &ch.GroupID, &ch.Name, &ch.Type,
			&ch.Position, &ch.TopPosition,
			&ch.MinLevel, &ch.SpeakMinLevel,
			&ch.PermMinLevel, &ch.LogicOperator, &ch.SpeakPermMinLevel, &ch.SpeakLogicOperator); err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
		}
		rule := permission.PermRule{PermMinLevel: ch.PermMinLevel, GroupMinLevel: ch.MinLevel, LogicOperator: ch.LogicOperator}
		if permission.CanAccess(user, rule) {
			channels = append(channels, ch)
		}
	}
	if channels == nil {
		channels = []channelResponse{}
	}
	return c.JSON(http.StatusOK, channels)
}

// CreateChannel creates a new channel in a server.
// POST /api/servers/:server_id/channels
func (h *ChannelHandler) CreateChannel(c echo.Context) error {
	serverID, err := strconv.ParseInt(c.Param("server_id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid server id"})
	}

	var req channelCreateReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}
	if req.Name == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "name is required"})
	}
	if req.Type == "" {
		req.Type = "text"
	}

	// Validate permission levels
	if req.MinLevel < 0 {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "min_level must be >= 0"})
	}
	if req.SpeakMinLevel < 0 {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "speak_min_level must be >= 0"})
	}
	if req.PermMinLevel < 0 {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "perm_min_level must be >= 0"})
	}
	if req.SpeakPermMinLevel < 0 {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "speak_perm_min_level must be >= 0"})
	}

	// Default logic operators
	if req.LogicOperator == "" {
		req.LogicOperator = "AND"
	}
	if req.SpeakLogicOperator == "" {
		req.SpeakLogicOperator = "AND"
	}
	if req.LogicOperator != "AND" && req.LogicOperator != "OR" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "logic_operator must be AND or OR"})
	}
	if req.SpeakLogicOperator != "AND" && req.SpeakLogicOperator != "OR" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "speak_logic_operator must be AND or OR"})
	}

	// Verify server exists
	var exists int
	if err := h.db.QueryRow("SELECT 1 FROM servers WHERE id = ?", serverID).Scan(&exists); err != nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "server not found"})
	}

	channelType := "TEXT"
	if req.Type == "voice" {
		channelType = "VOICE"
	}

	var position, topPosition int
	if req.GroupID != nil {
		// Verify group exists
		if err := h.db.QueryRow("SELECT 1 FROM channel_groups WHERE id = ? AND server_id = ?", *req.GroupID, serverID).Scan(&exists); err != nil {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "channel group not found"})
		}
		// Get max position within group
		var maxPos sql.NullInt64
		h.db.QueryRow("SELECT MAX(position) FROM channels WHERE server_id = ? AND group_id = ?", serverID, *req.GroupID).Scan(&maxPos)
		if maxPos.Valid {
			position = int(maxPos.Int64) + 1
		}
	} else {
		// Ungrouped: get max top-level position
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
		if gm > cm {
			topPosition = int(gm) + 1
		} else {
			topPosition = int(cm) + 1
		}
	}

	res, err := h.db.Exec(
		`INSERT INTO channels (server_id, group_id, name, type, position, top_position,
		    min_level, speak_min_level,
		    perm_min_level, logic_operator, speak_perm_min_level, speak_logic_operator)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		serverID, req.GroupID, req.Name, channelType, position, topPosition,
		req.MinLevel, req.SpeakMinLevel,
		req.PermMinLevel, req.LogicOperator, req.SpeakPermMinLevel, req.SpeakLogicOperator,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	chID, _ := res.LastInsertId()

	return c.JSON(http.StatusCreated, channelResponse{
		ID: chID, ServerID: serverID, GroupID: req.GroupID,
		Name: req.Name, Type: channelType,
		Position: position, TopPosition: topPosition,
		MinLevel: req.MinLevel, SpeakMinLevel: req.SpeakMinLevel,
		PermMinLevel: req.PermMinLevel, LogicOperator: req.LogicOperator,
		SpeakPermMinLevel: req.SpeakPermMinLevel, SpeakLogicOperator: req.SpeakLogicOperator,
	})
}

// UpdateChannel updates channel properties.
// PATCH /api/channels/:id
func (h *ChannelHandler) UpdateChannel(c echo.Context) error {
	chID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel id"})
	}

	// Fetch current channel
	var ch channelResponse
	err = h.db.QueryRow(
		`SELECT id, server_id, group_id, name, type, position, top_position,
		        min_level, speak_min_level,
		        perm_min_level, logic_operator, speak_perm_min_level, speak_logic_operator
		 FROM channels WHERE id = ?`, chID,
	).Scan(&ch.ID, &ch.ServerID, &ch.GroupID, &ch.Name, &ch.Type,
		&ch.Position, &ch.TopPosition,
		&ch.MinLevel, &ch.SpeakMinLevel,
		&ch.PermMinLevel, &ch.LogicOperator, &ch.SpeakPermMinLevel, &ch.SpeakLogicOperator)
	if err == sql.ErrNoRows {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
	}
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	var req channelUpdateReq
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}

	if req.Name != nil {
		ch.Name = *req.Name
	}
	if req.MinLevel != nil {
		if *req.MinLevel < 0 {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "min_level must be >= 0"})
		}
		ch.MinLevel = *req.MinLevel
	}
	if req.SpeakMinLevel != nil {
		if *req.SpeakMinLevel < 0 {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "speak_min_level must be >= 0"})
		}
		ch.SpeakMinLevel = *req.SpeakMinLevel
	}
	if req.PermMinLevel != nil {
		if *req.PermMinLevel < 0 {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "perm_min_level must be >= 0"})
		}
		ch.PermMinLevel = *req.PermMinLevel
	}
	if req.LogicOperator != nil {
		if *req.LogicOperator != "AND" && *req.LogicOperator != "OR" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "logic_operator must be AND or OR"})
		}
		ch.LogicOperator = *req.LogicOperator
	}
	if req.SpeakPermMinLevel != nil {
		if *req.SpeakPermMinLevel < 0 {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "speak_perm_min_level must be >= 0"})
		}
		ch.SpeakPermMinLevel = *req.SpeakPermMinLevel
	}
	if req.SpeakLogicOperator != nil {
		if *req.SpeakLogicOperator != "AND" && *req.SpeakLogicOperator != "OR" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "speak_logic_operator must be AND or OR"})
		}
		ch.SpeakLogicOperator = *req.SpeakLogicOperator
	}

	// Handle group_id change: -1 means ungroup
	if req.GroupID != nil {
		if *req.GroupID == -1 {
			// Ungroup: assign top_position
			if ch.GroupID != nil {
				var groupMax, chMax sql.NullInt64
				h.db.QueryRow("SELECT MAX(position) FROM channel_groups WHERE server_id = ?", ch.ServerID).Scan(&groupMax)
				h.db.QueryRow("SELECT MAX(top_position) FROM channels WHERE server_id = ? AND group_id IS NULL", ch.ServerID).Scan(&chMax)
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
				ch.GroupID = nil
				ch.TopPosition = int(maxPos) + 1
				ch.Position = 0
			}
		} else {
			// Move to group
			var exists int
			if err := h.db.QueryRow("SELECT 1 FROM channel_groups WHERE id = ? AND server_id = ?", *req.GroupID, ch.ServerID).Scan(&exists); err != nil {
				return c.JSON(http.StatusNotFound, map[string]string{"error": "channel group not found"})
			}
			var maxPos sql.NullInt64
			h.db.QueryRow("SELECT MAX(position) FROM channels WHERE server_id = ? AND group_id = ?", ch.ServerID, *req.GroupID).Scan(&maxPos)
			pos := 0
			if maxPos.Valid {
				pos = int(maxPos.Int64) + 1
			}
			ch.GroupID = req.GroupID
			ch.Position = pos
			ch.TopPosition = 0
		}
	}

	_, err = h.db.Exec(
		`UPDATE channels SET name = ?, group_id = ?, position = ?, top_position = ?,
		    min_level = ?, speak_min_level = ?,
		    perm_min_level = ?, logic_operator = ?, speak_perm_min_level = ?, speak_logic_operator = ?
		 WHERE id = ?`,
		ch.Name, ch.GroupID, ch.Position, ch.TopPosition,
		ch.MinLevel, ch.SpeakMinLevel,
		ch.PermMinLevel, ch.LogicOperator, ch.SpeakPermMinLevel, ch.SpeakLogicOperator, chID,
	)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	return c.JSON(http.StatusOK, ch)
}

// DeleteChannel deletes a channel.
// DELETE /api/channels/:id
func (h *ChannelHandler) DeleteChannel(c echo.Context) error {
	chID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel id"})
	}

	res, err := h.db.Exec("DELETE FROM channels WHERE id = ?", chID)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
	}
	return c.NoContent(http.StatusNoContent)
}
