package handler

import (
	"database/sql"
	"fmt"
	"io"
	"log"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"github.com/google/uuid"
	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/permission"
)

const maxFileSize = 10 * 1024 * 1024 // 10MB

var (
	blockedExtensions = map[string]bool{
		".exe": true, ".bat": true, ".sh": true, ".cmd": true, ".ps1": true,
		".vbs": true, ".js": true, ".msi": true, ".dll": true, ".sys": true,
	}
	unsafeCharsRe = regexp.MustCompile(`[<>:"/\\|?*]`)
)

// RegisterFileRoutes registers file upload/download/delete routes.
func RegisterFileRoutes(e *echo.Echo, jwtSecret string, db *sql.DB, uploadDir string) {
	e.POST("/api/channels/:channel_id/upload", uploadFile(jwtSecret, db, uploadDir))
	e.GET("/api/files/:id", downloadFile(jwtSecret, db, uploadDir))
	e.DELETE("/api/files/:id", deleteFile(jwtSecret, db, uploadDir))
}

func sanitizeFilename(name string) string {
	name = filepath.Base(name)
	name = strings.ReplaceAll(name, "\x00", "")
	name = unsafeCharsRe.ReplaceAllString(name, "_")
	if len(name) > 200 {
		name = name[:200]
	}
	if name == "" {
		name = "unnamed"
	}
	return name
}

func uploadFile(jwtSecret string, db *sql.DB, uploadDir string) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateFromEcho(c, jwtSecret)
		if err != nil {
			return err
		}

		channelID, err := strconv.ParseInt(c.Param("channel_id"), 10, 64)
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid channel_id"})
		}

		// Verify channel exists and is TEXT
		var chType string
		err = db.QueryRow("SELECT type FROM channels WHERE id = ?", channelID).Scan(&chType)
		if err != nil {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "channel not found"})
		}
		if chType != "TEXT" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a text channel"})
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

		contentType := fh.Header.Get("Content-Type")
		if contentType == "" {
			contentType = mime.TypeByExtension(ext)
			if contentType == "" {
				contentType = "application/octet-stream"
			}
		}

		storedName := uuid.New().String() + ext
		chDir := filepath.Join(uploadDir, strconv.FormatInt(channelID, 10))
		os.MkdirAll(chDir, 0755)

		if err := os.WriteFile(filepath.Join(chDir, storedName), content, 0644); err != nil {
			log.Printf("handler/files: failed to save file: %v", err)
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to save file"})
		}

		result, err := db.Exec(
			`INSERT INTO attachments (message_id, channel_id, user_id, filename, stored_name, content_type, size)
			 VALUES (NULL, ?, ?, ?, ?, ?, ?)`,
			channelID, user.ID, safeName, storedName, contentType, len(content),
		)
		if err != nil {
			os.Remove(filepath.Join(chDir, storedName))
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to create record"})
		}

		id, _ := result.LastInsertId()
		return c.JSON(http.StatusOK, map[string]interface{}{
			"id":           id,
			"filename":     safeName,
			"content_type": contentType,
			"size":         len(content),
			"url":          fmt.Sprintf("/api/files/%d", id),
		})
	}
}

func downloadFile(jwtSecret string, db *sql.DB, uploadDir string) echo.HandlerFunc {
	return func(c echo.Context) error {
		if _, err := authenticateFromEcho(c, jwtSecret); err != nil {
			return err
		}

		attachID, err := strconv.ParseInt(c.Param("id"), 10, 64)
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid id"})
		}

		var channelID int64
		var filename, storedName, contentType string
		err = db.QueryRow(
			"SELECT channel_id, filename, stored_name, content_type FROM attachments WHERE id = ?", attachID,
		).Scan(&channelID, &filename, &storedName, &contentType)
		if err != nil {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found"})
		}

		filePath := filepath.Join(uploadDir, strconv.FormatInt(channelID, 10), storedName)
		if _, err := os.Stat(filePath); os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found on disk"})
		}

		inlineParam := c.QueryParam("inline")
		inline := inlineParam == "true" || inlineParam == "1"
		if inline {
			return c.Inline(filePath, filename)
		}
		return c.Attachment(filePath, filename)
	}
}

func deleteFile(jwtSecret string, db *sql.DB, uploadDir string) echo.HandlerFunc {
	return func(c echo.Context) error {
		user, err := authenticateFromEcho(c, jwtSecret)
		if err != nil {
			return err
		}

		attachID, err := strconv.ParseInt(c.Param("id"), 10, 64)
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid id"})
		}

		var channelID, ownerID int64
		var storedName string
		err = db.QueryRow(
			"SELECT channel_id, user_id, stored_name FROM attachments WHERE id = ?", attachID,
		).Scan(&channelID, &ownerID, &storedName)
		if err != nil {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found"})
		}

		isOwner := ownerID == int64(user.ID)
		isAdmin := permission.IsAdmin(user)
		if !isOwner && !isAdmin {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "permission denied"})
		}

		filePath := filepath.Join(uploadDir, strconv.FormatInt(channelID, 10), storedName)
		os.Remove(filePath)

		db.Exec("DELETE FROM attachments WHERE id = ?", attachID)
		return c.NoContent(http.StatusNoContent)
	}
}
