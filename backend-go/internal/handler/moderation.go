package handler

import (
	"database/sql"
	"net/http"
	"strconv"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/permission"
	"github.com/RMS-Server/rms-discord-go/internal/sso"
)

// RegisterModerationRoutes registers /api/mute routes.
func RegisterModerationRoutes(g *echo.Group, ssoClient *sso.Client, db *sql.DB) {
	g.POST("", createMute(ssoClient, db))
	g.DELETE("/:id", removeMute(ssoClient, db))
	g.GET("/user/:user_id", getUserMutes(ssoClient, db))
}

type muteCreateRequest struct {
	UserID          int64   `json:"user_id"`
	Scope           string  `json:"scope"`
	ServerID        *int64  `json:"server_id"`
	ChannelID       *int64  `json:"channel_id"`
	DurationMinutes *int    `json:"duration_minutes"`
	Reason          *string `json:"reason"`
}

func createMute(ssoClient *sso.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateFromEcho(c, ssoClient)
		if err != nil {
			return err
		}
		if !permission.IsAdmin(user) {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "admin required"})
		}

		var req muteCreateRequest
		if err := c.Bind(&req); err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid body"})
		}

		// Validate scope
		switch req.Scope {
		case "global":
			if req.ServerID != nil || req.ChannelID != nil {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "global mute should not have server_id or channel_id"})
			}
		case "server":
			if req.ServerID == nil || req.ChannelID != nil {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "server mute requires server_id only"})
			}
		case "channel":
			if req.ChannelID == nil || req.ServerID != nil {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "channel mute requires channel_id only"})
			}
		default:
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid scope"})
		}

		var mutedUntil sql.NullTime
		if req.DurationMinutes != nil {
			mutedUntil = sql.NullTime{
				Time:  time.Now().UTC().Add(time.Duration(*req.DurationMinutes) * time.Minute),
				Valid: true,
			}
		}

		result, err := db.Exec(
			`INSERT INTO mute_records (user_id, scope, server_id, channel_id, muted_until, muted_by, reason)
			 VALUES (?, ?, ?, ?, ?, ?, ?)`,
			req.UserID, req.Scope, req.ServerID, req.ChannelID, mutedUntil, user.ID, req.Reason,
		)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to create mute"})
		}

		id, _ := result.LastInsertId()
		return c.JSON(http.StatusCreated, map[string]interface{}{
			"id": id, "message": "User muted successfully",
		})
	}
}

func removeMute(ssoClient *sso.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateFromEcho(c, ssoClient)
		if err != nil {
			return err
		}
		if !permission.IsAdmin(user) {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "admin required"})
		}

		muteID := c.Param("id")
		result, err := db.Exec("DELETE FROM mute_records WHERE id = ?", muteID)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to delete"})
		}
		rows, _ := result.RowsAffected()
		if rows == 0 {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "mute record not found"})
		}
		return c.NoContent(http.StatusNoContent)
	}
}

func getUserMutes(ssoClient *sso.Client, db *sql.DB) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, ssoClient); err != nil {
			return err
		}

		userID, err := strconv.ParseInt(c.Param("user_id"), 10, 64)
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid user_id"})
		}

		rows, err := db.Query(
			`SELECT id, scope, server_id, channel_id, muted_until, reason
			 FROM mute_records
			 WHERE user_id = ? AND (muted_until IS NULL OR muted_until > UTC_TIMESTAMP())`,
			userID,
		)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "query failed"})
		}
		defer rows.Close()

		var mutes []map[string]interface{}
		for rows.Next() {
			var id int64
			var scope string
			var serverID, channelID sql.NullInt64
			var mutedUntil sql.NullTime
			var reason sql.NullString
			if err := rows.Scan(&id, &scope, &serverID, &channelID, &mutedUntil, &reason); err != nil {
				continue
			}
			m := map[string]interface{}{
				"id":          id,
				"scope":       scope,
				"server_id":   nil,
				"channel_id":  nil,
				"muted_until": nil,
				"reason":      nil,
			}
			if serverID.Valid {
				m["server_id"] = serverID.Int64
			}
			if channelID.Valid {
				m["channel_id"] = channelID.Int64
			}
			if mutedUntil.Valid {
				m["muted_until"] = mutedUntil.Time.UTC().Format("2006-01-02T15:04:05Z")
			}
			if reason.Valid {
				m["reason"] = reason.String
			}
			mutes = append(mutes, m)
		}
		if mutes == nil {
			mutes = []map[string]interface{}{}
		}
		return c.JSON(http.StatusOK, mutes)
	}
}
