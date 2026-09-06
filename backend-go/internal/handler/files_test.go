package handler

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"testing"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestPrepareUploadUsesDetectedImageType(t *testing.T) {
	gif := []byte("GIF89a\x01\x00\x01\x00\x00\x00\x00")
	upload := prepareUpload(context.Background(), "wrong.jpg", "image/jpeg", gif)
	if upload.contentType != "image/gif" || upload.extension != ".gif" || upload.filename != "wrong.gif" {
		t.Fatalf("unexpected upload metadata: %+v", upload)
	}
}

func TestPrepareUploadRejectsFalseImageType(t *testing.T) {
	upload := prepareUpload(context.Background(), "payload.jpg", "image/jpeg", []byte("plain text"))
	if upload.contentType != "text/plain; charset=utf-8" {
		t.Fatalf("content type = %q, want detected text type", upload.contentType)
	}
}

func TestPrepareUploadUsesSmallerWebP(t *testing.T) {
	originalEncoder := webPEncoder
	webPEncoder = func(context.Context, []byte) ([]byte, error) {
		return make([]byte, 10), nil
	}
	t.Cleanup(func() { webPEncoder = originalEncoder })

	jpeg := append([]byte{0xff, 0xd8, 0xff, 0xe0}, make([]byte, 96)...)
	upload := prepareUpload(context.Background(), "photo.jpeg", "image/jpeg", jpeg)
	if upload.contentType != "image/webp" || upload.extension != ".webp" || upload.filename != "photo.webp" {
		t.Fatalf("unexpected optimized metadata: %+v", upload)
	}
	if len(upload.content) != 10 {
		t.Fatalf("optimized size = %d, want 10", len(upload.content))
	}
}

func TestPrepareUploadKeepsOriginalWhenOptimizationDoesNotHelp(t *testing.T) {
	originalEncoder := webPEncoder
	webPEncoder = func(context.Context, []byte) ([]byte, error) {
		return make([]byte, 90), nil
	}
	t.Cleanup(func() { webPEncoder = originalEncoder })

	jpeg := append([]byte{0xff, 0xd8, 0xff, 0xe0}, make([]byte, 96)...)
	upload := prepareUpload(context.Background(), "photo.jpg", "image/jpeg", jpeg)
	if upload.contentType != "image/jpeg" || upload.extension != ".jpg" || upload.filename != "photo.jpg" {
		t.Fatalf("unexpected original metadata: %+v", upload)
	}
	if len(upload.content) != len(jpeg) {
		t.Fatalf("original size = %d, got %d", len(jpeg), len(upload.content))
	}
}

func TestPrepareUploadDoesNotFlattenAnimatedPNG(t *testing.T) {
	called := false
	originalEncoder := webPEncoder
	webPEncoder = func(context.Context, []byte) ([]byte, error) {
		called = true
		return make([]byte, 10), nil
	}
	t.Cleanup(func() { webPEncoder = originalEncoder })

	apng := append([]byte("\x89PNG\r\n\x1a\n"), make([]byte, 32)...)
	apng = append(apng, []byte("acTL")...)
	upload := prepareUpload(context.Background(), "animated.png", "image/png", apng)
	if called {
		t.Fatal("animated PNG was sent to the static image encoder")
	}
	if upload.contentType != "image/png" || upload.filename != "animated.png" {
		t.Fatalf("unexpected animated PNG metadata: %+v", upload)
	}
}

func TestEncodeWebP(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg is not installed")
	}

	img := image.NewNRGBA(image.Rect(0, 0, 512, 512))
	for y := 0; y < 512; y++ {
		for x := 0; x < 512; x++ {
			img.SetNRGBA(x, y, color.NRGBA{R: uint8(x), G: uint8(y), B: uint8(x + y), A: 255})
		}
	}
	var source bytes.Buffer
	if err := png.Encode(&source, img); err != nil {
		t.Fatal(err)
	}

	encoded, err := encodeWebP(context.Background(), source.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	if contentType := http.DetectContentType(encoded); contentType != "image/webp" {
		t.Fatalf("encoded content type = %q, want image/webp", contentType)
	}
}

func TestDetectFileContentTypeIgnoresFilenameExtension(t *testing.T) {
	path := filepath.Join(t.TempDir(), "wrong.jpg")
	if err := os.WriteFile(path, []byte("GIF89a\x01\x00\x01\x00\x00\x00\x00"), 0644); err != nil {
		t.Fatal(err)
	}
	contentType, err := detectFileContentType(path)
	if err != nil {
		t.Fatal(err)
	}
	if contentType != "image/gif" {
		t.Fatalf("content type = %q, want image/gif", contentType)
	}
}

func TestBackfillAttachmentHashes(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	root := t.TempDir()
	dir := filepath.Join(root, "36")
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatal(err)
	}
	content := []byte("existing attachment")
	if err := os.WriteFile(filepath.Join(dir, "existing.jpg"), content, 0644); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(content)
	mock.ExpectQuery("SELECT id, channel_id, stored_name FROM attachments").WillReturnRows(
		sqlmock.NewRows([]string{"id", "channel_id", "stored_name"}).AddRow(12, 36, "existing.jpg"),
	)
	mock.ExpectExec("UPDATE attachments SET content_hash").
		WithArgs(fmt.Sprintf("%x", sum), int64(12)).
		WillReturnResult(sqlmock.NewResult(0, 1))

	updated, err := backfillAttachmentHashes(db, root)
	if err != nil {
		t.Fatal(err)
	}
	if updated != 1 {
		t.Fatalf("updated = %d, want 1", updated)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestSaveAttachmentHardLinksDuplicate(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	root := t.TempDir()
	existingDir := filepath.Join(root, "1")
	if err := os.MkdirAll(existingDir, 0755); err != nil {
		t.Fatal(err)
	}
	existingName := "existing.webp"
	existingPath := filepath.Join(existingDir, existingName)
	content := []byte("RIFF\x10\x00\x00\x00WEBPVP8 same image")
	if err := os.WriteFile(existingPath, content, 0644); err != nil {
		t.Fatal(err)
	}
	upload := prepareUpload(context.Background(), "photo.webp", "image/webp", content)

	mock.ExpectQuery(regexp.QuoteMeta(
		"SELECT channel_id, stored_name FROM attachments WHERE content_hash = ? ORDER BY id DESC LIMIT 1",
	)).WithArgs(upload.contentHash).WillReturnRows(
		sqlmock.NewRows([]string{"channel_id", "stored_name"}).AddRow(1, existingName),
	)
	mock.ExpectExec("INSERT INTO attachments").
		WithArgs(int64(36), int64(7), "photo.webp", sqlmock.AnyArg(), "image/webp", len(content), upload.contentHash).
		WillReturnResult(sqlmock.NewResult(42, 1))

	id, storedName, err := saveAttachment(db, root, 36, 7, upload)
	if err != nil {
		t.Fatal(err)
	}
	if id != 42 {
		t.Fatalf("attachment id = %d, want 42", id)
	}
	assertHardLinked(t, existingPath, filepath.Join(root, "36", storedName))
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestSaveAttachmentWritesNewContent(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	root := t.TempDir()
	content := []byte("new file")
	upload := prepareUpload(context.Background(), "notes.txt", "text/plain", content)
	mock.ExpectQuery(regexp.QuoteMeta(
		"SELECT channel_id, stored_name FROM attachments WHERE content_hash = ? ORDER BY id DESC LIMIT 1",
	)).WithArgs(upload.contentHash).WillReturnError(sql.ErrNoRows)
	mock.ExpectExec("INSERT INTO attachments").
		WithArgs(int64(36), int64(7), "notes.txt", sqlmock.AnyArg(), "text/plain", len(content), upload.contentHash).
		WillReturnResult(sqlmock.NewResult(43, 1))

	id, storedName, err := saveAttachment(db, root, 36, 7, upload)
	if err != nil {
		t.Fatal(err)
	}
	if id != 43 {
		t.Fatalf("attachment id = %d, want 43", id)
	}
	storedContent, err := os.ReadFile(filepath.Join(root, "36", storedName))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(storedContent, content) {
		t.Fatalf("stored content = %q, want %q", storedContent, content)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestSaveAttachmentDoesNotTrustStaleDuplicateHash(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	root := t.TempDir()
	existingDir := filepath.Join(root, "1")
	if err := os.MkdirAll(existingDir, 0755); err != nil {
		t.Fatal(err)
	}
	existingName := "stale.txt"
	if err := os.WriteFile(filepath.Join(existingDir, existingName), []byte("different content"), 0644); err != nil {
		t.Fatal(err)
	}
	content := []byte("expected content")
	upload := prepareUpload(context.Background(), "notes.txt", "text/plain", content)
	mock.ExpectQuery(regexp.QuoteMeta(
		"SELECT channel_id, stored_name FROM attachments WHERE content_hash = ? ORDER BY id DESC LIMIT 1",
	)).WithArgs(upload.contentHash).WillReturnRows(
		sqlmock.NewRows([]string{"channel_id", "stored_name"}).AddRow(1, existingName),
	)
	mock.ExpectExec("INSERT INTO attachments").WillReturnResult(sqlmock.NewResult(44, 1))

	_, storedName, err := saveAttachment(db, root, 36, 7, upload)
	if err != nil {
		t.Fatal(err)
	}
	storedContent, err := os.ReadFile(filepath.Join(root, "36", storedName))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(storedContent, content) {
		t.Fatalf("stored content = %q, want %q", storedContent, content)
	}
}

func assertHardLinked(t *testing.T, first, second string) {
	t.Helper()
	firstInfo, err := os.Stat(first)
	if err != nil {
		t.Fatal(err)
	}
	secondInfo, err := os.Stat(second)
	if err != nil {
		t.Fatal(err)
	}
	if !os.SameFile(firstInfo, secondInfo) {
		t.Fatalf("%s and %s do not share an inode", first, second)
	}
}
