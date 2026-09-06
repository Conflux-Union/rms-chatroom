package handler

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/labstack/echo/v4"

	"github.com/RMS-Server/rms-discord-go/internal/permission"
)

const (
	maxFileSize               = 10 * 1024 * 1024 // 10MB
	imageOptimizationTimeout  = 15 * time.Second
	imageOptimizationMinRatio = 85 // Use the optimized image only at 85% or less of the original size.
)

var (
	blockedExtensions = map[string]bool{
		".exe": true, ".bat": true, ".sh": true, ".cmd": true, ".ps1": true,
		".vbs": true, ".js": true, ".msi": true, ".dll": true, ".sys": true,
	}
	unsafeCharsRe   = regexp.MustCompile(`[<>:"/\\|?*]`)
	uploadStorageMu sync.Mutex
	imageOptimizer  = make(chan struct{}, 1)
	webPEncoder     = encodeWebP
)

type preparedUpload struct {
	content     []byte
	contentHash string
	contentType string
	extension   string
	filename    string
}

// RegisterFileRoutes registers file upload/download/delete routes.
func RegisterFileRoutes(e *echo.Echo, jwtSecret string, db *sql.DB, uploadDir string) {
	if updated, err := backfillAttachmentHashes(db, uploadDir); err != nil {
		log.Printf("handler/files: attachment hash backfill skipped: %v", err)
	} else if updated > 0 {
		log.Printf("handler/files: backfilled content hashes for %d attachments", updated)
	}
	e.POST("/api/channels/:channel_id/upload", uploadFile(jwtSecret, db, uploadDir))
	e.GET("/api/files/:id", downloadFile(jwtSecret, db, uploadDir))
	e.DELETE("/api/files/:id", deleteFile(jwtSecret, db, uploadDir))
}

func backfillAttachmentHashes(db *sql.DB, uploadDir string) (int, error) {
	rows, err := db.Query("SELECT id, channel_id, stored_name FROM attachments WHERE content_hash IS NULL")
	if err != nil {
		return 0, fmt.Errorf("list attachments: %w", err)
	}
	defer rows.Close()

	updated := 0
	for rows.Next() {
		var id, channelID int64
		var storedName string
		if err := rows.Scan(&id, &channelID, &storedName); err != nil {
			return updated, fmt.Errorf("scan attachment: %w", err)
		}
		if filepath.Base(storedName) != storedName {
			log.Printf("handler/files: refusing unsafe stored name for attachment %d", id)
			continue
		}
		hash, err := hashStoredFile(filepath.Join(uploadDir, strconv.FormatInt(channelID, 10), storedName))
		if err != nil {
			log.Printf("handler/files: cannot hash attachment %d: %v", id, err)
			continue
		}
		result, err := db.Exec(
			"UPDATE attachments SET content_hash = ? WHERE id = ? AND content_hash IS NULL",
			hash, id,
		)
		if err != nil {
			return updated, fmt.Errorf("update attachment %d: %w", id, err)
		}
		if affected, err := result.RowsAffected(); err == nil {
			updated += int(affected)
		}
	}
	if err := rows.Err(); err != nil {
		return updated, fmt.Errorf("iterate attachments: %w", err)
	}
	return updated, nil
}

func hashStoredFile(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()

	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return fmt.Sprintf("%x", hash.Sum(nil)), nil
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

		prepared := prepareUpload(c.Request().Context(), safeName, fh.Header.Get("Content-Type"), content)
		id, _, err := saveAttachment(db, uploadDir, channelID, int64(user.ID), prepared)
		if err != nil {
			log.Printf("handler/files: failed to save attachment: %v", err)
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
}

func prepareUpload(ctx context.Context, filename, declaredContentType string, content []byte) preparedUpload {
	contentType := strings.TrimSpace(strings.SplitN(declaredContentType, ";", 2)[0])
	if contentType == "" {
		contentType = mime.TypeByExtension(strings.ToLower(filepath.Ext(filename)))
	}
	if contentType == "" {
		contentType = "application/octet-stream"
	}

	sniffedType := http.DetectContentType(content)
	if imageExtension(sniffedType) != "" {
		contentType = sniffedType
		filename = replaceExtension(filename, imageExtension(contentType))
	} else if strings.HasPrefix(contentType, "image/") {
		contentType = sniffedType
	}

	staticImage := contentType == "image/jpeg" || (contentType == "image/png" && !bytes.Contains(content, []byte("acTL")))
	if staticImage {
		if optimized, err := webPEncoder(ctx, content); err == nil &&
			len(optimized) > 0 && len(optimized)*100 <= len(content)*imageOptimizationMinRatio {
			content = optimized
			contentType = "image/webp"
			filename = replaceExtension(filename, ".webp")
		} else if err != nil {
			log.Printf("handler/files: image optimization skipped: %v", err)
		}
	}

	ext := imageExtension(contentType)
	if ext == "" {
		ext = strings.ToLower(filepath.Ext(filename))
	}
	sum := sha256.Sum256(content)
	return preparedUpload{
		content:     content,
		contentHash: fmt.Sprintf("%x", sum),
		contentType: contentType,
		extension:   ext,
		filename:    filename,
	}
}

func imageExtension(contentType string) string {
	switch contentType {
	case "image/jpeg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/gif":
		return ".gif"
	case "image/webp":
		return ".webp"
	case "image/bmp":
		return ".bmp"
	default:
		return ""
	}
}

func replaceExtension(filename, ext string) string {
	base := strings.TrimSuffix(filename, filepath.Ext(filename))
	if base == "" {
		base = "image"
	}
	return base + ext
}

var errOptimizedImageTooLarge = errors.New("optimized image is not smaller than the original")

type sizeLimitedBuffer struct {
	bytes.Buffer
	max int
}

func (b *sizeLimitedBuffer) Write(p []byte) (int, error) {
	if b.Len()+len(p) > b.max {
		return 0, errOptimizedImageTooLarge
	}
	return b.Buffer.Write(p)
}

func encodeWebP(parent context.Context, content []byte) ([]byte, error) {
	ctx, cancel := context.WithTimeout(parent, imageOptimizationTimeout)
	defer cancel()
	select {
	case imageOptimizer <- struct{}{}:
		defer func() { <-imageOptimizer }()
	case <-ctx.Done():
		return nil, ctx.Err()
	}

	cmd := exec.CommandContext(ctx, "ffmpeg",
		"-hide_banner", "-loglevel", "error",
		"-i", "pipe:0",
		"-vf", "scale=w='min(2560,iw)':h='min(2560,ih)':force_original_aspect_ratio=decrease",
		"-frames:v", "1", "-map_metadata", "-1",
		"-c:v", "libwebp", "-q:v", "82", "-threads", "1",
		"-f", "webp", "pipe:1",
	)
	cmd.Stdin = bytes.NewReader(content)
	output := &sizeLimitedBuffer{max: len(content)}
	var stderr bytes.Buffer
	cmd.Stdout = output
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		return nil, fmt.Errorf("ffmpeg: %s", message)
	}
	if output.Len() == 0 || http.DetectContentType(output.Bytes()) != "image/webp" {
		return nil, errors.New("ffmpeg produced an invalid WebP image")
	}
	return output.Bytes(), nil
}

func saveAttachment(db *sql.DB, uploadDir string, channelID, userID int64, upload preparedUpload) (int64, string, error) {
	storedName := uuid.New().String() + upload.extension
	channelDir := filepath.Join(uploadDir, strconv.FormatInt(channelID, 10))
	if err := os.MkdirAll(channelDir, 0755); err != nil {
		return 0, "", fmt.Errorf("create upload directory: %w", err)
	}
	storedPath := filepath.Join(channelDir, storedName)

	// ponytail: uploads are low-volume, so one process-wide lock is simpler than
	// a blob table; move deduplication into storage if upload throughput grows.
	uploadStorageMu.Lock()
	defer uploadStorageMu.Unlock()

	linked := false
	var existingChannelID int64
	var existingStoredName string
	err := db.QueryRow(
		"SELECT channel_id, stored_name FROM attachments WHERE content_hash = ? ORDER BY id DESC LIMIT 1",
		upload.contentHash,
	).Scan(&existingChannelID, &existingStoredName)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return 0, "", fmt.Errorf("find duplicate attachment: %w", err)
	}
	if err == nil && filepath.Base(existingStoredName) == existingStoredName {
		existingPath := filepath.Join(uploadDir, strconv.FormatInt(existingChannelID, 10), existingStoredName)
		if existingHash, hashErr := hashStoredFile(existingPath); hashErr == nil && existingHash == upload.contentHash {
			if linkErr := os.Link(existingPath, storedPath); linkErr == nil {
				linked = true
			}
		}
	}
	if !linked {
		if err := os.WriteFile(storedPath, upload.content, 0644); err != nil {
			return 0, "", fmt.Errorf("write attachment: %w", err)
		}
	}

	result, err := db.Exec(
		`INSERT INTO attachments (message_id, channel_id, user_id, filename, stored_name, content_type, size, content_hash)
		 VALUES (NULL, ?, ?, ?, ?, ?, ?, ?)`,
		channelID, userID, upload.filename, storedName, upload.contentType, len(upload.content), upload.contentHash,
	)
	if err != nil {
		_ = os.Remove(storedPath)
		return 0, "", fmt.Errorf("insert attachment: %w", err)
	}
	id, err := result.LastInsertId()
	if err != nil {
		return 0, "", fmt.Errorf("read attachment id: %w", err)
	}
	return id, storedName, nil
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
		var filename, storedName string
		err = db.QueryRow(
			"SELECT channel_id, filename, stored_name FROM attachments WHERE id = ?", attachID,
		).Scan(&channelID, &filename, &storedName)
		if err != nil {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found"})
		}

		filePath := filepath.Join(uploadDir, strconv.FormatInt(channelID, 10), storedName)
		if _, err := os.Stat(filePath); os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found on disk"})
		}

		inlineParam := c.QueryParam("inline")
		inline := inlineParam == "true" || inlineParam == "1"
		if contentType, err := detectFileContentType(filePath); err == nil {
			c.Response().Header().Set(echo.HeaderContentType, contentType)
			c.Response().Header().Set("X-Content-Type-Options", "nosniff")
			if inline && !isSafeInlineContentType(contentType) {
				inline = false
			}
		}
		if inline {
			return c.Inline(filePath, filename)
		}
		return c.Attachment(filePath, filename)
	}
}

func isSafeInlineContentType(contentType string) bool {
	return strings.HasPrefix(contentType, "image/") ||
		strings.HasPrefix(contentType, "audio/") ||
		strings.HasPrefix(contentType, "video/") ||
		contentType == "application/pdf"
}

func detectFileContentType(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()

	header := make([]byte, 512)
	n, err := file.Read(header)
	if err != nil && !errors.Is(err, io.EOF) {
		return "", err
	}
	return http.DetectContentType(header[:n]), nil
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
