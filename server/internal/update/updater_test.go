package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/RMS-Server/rms-discord-go/internal/version"
)

func TestMirrorURLs(t *testing.T) {
	got := mirrorURLs([]string{"https://m1.example", "https://m2.example/gh/"}, "https://github.com/x/y.tar.gz")
	want := []string{
		"https://m1.example/https://github.com/x/y.tar.gz",
		"https://m2.example/gh/https://github.com/x/y.tar.gz",
		"https://github.com/x/y.tar.gz",
	}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("url[%d] = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestValidateManifest(t *testing.T) {
	valid := Manifest{
		Version: "1.0.8",
		Code:    34,
		URL:     "https://github.com/o/r/releases/download/v1.0.8(34)/bundle.tar.gz",
		SHA256:  strings.Repeat("ab", 32),
	}
	if err := validateManifest(&valid); err != nil {
		t.Errorf("valid manifest rejected: %v", err)
	}

	bad := valid
	bad.Code = 0
	if err := validateManifest(&bad); err == nil {
		t.Error("zero code accepted")
	}

	bad = valid
	bad.URL = "https://evil.example/bundle.tar.gz"
	if err := validateManifest(&bad); err == nil {
		t.Error("non-github URL accepted")
	}

	bad = valid
	bad.SHA256 = "short"
	if err := validateManifest(&bad); err == nil {
		t.Error("malformed sha256 accepted")
	}
}

func TestFetchFallsBackAcrossMirrorsAndRetries(t *testing.T) {
	oldBackoff := retryBackoff
	retryBackoff = time.Millisecond
	defer func() { retryBackoff = oldBackoff }()

	var badHits, goodHits atomic.Int32
	badMirror := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		badHits.Add(1)
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer badMirror.Close()
	goodMirror := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		goodHits.Add(1)
		io.WriteString(w, "payload")
	}))
	defer goodMirror.Close()

	var body string
	err := fetch(context.Background(), http.DefaultClient,
		[]string{badMirror.URL, goodMirror.URL},
		"https://github.com/o/r/releases/download/v1/bundle.tar.gz",
		func(r io.Reader) error {
			b, err := io.ReadAll(r)
			body = string(b)
			return err
		})
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if body != "payload" {
		t.Errorf("body = %q", body)
	}
	if badHits.Load() != attemptsPerMirror {
		t.Errorf("bad mirror hit %d times, want %d", badHits.Load(), attemptsPerMirror)
	}
	if goodHits.Load() != 1 {
		t.Errorf("good mirror hit %d times, want 1", goodHits.Load())
	}
}

func TestCheckAndApplyDownloadsVerifiesAndRestarts(t *testing.T) {
	dir := t.TempDir()
	bundle := buildArchive(t, map[string]string{
		BinaryName:                     "brand-new-binary",
		"packages/web/dist/index.html": "<html></html>",
	}).Bytes()
	sum := sha256.Sum256(bundle)

	mirror := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write(bundle)
	}))
	defer mirror.Close()

	restarted := false
	u := &Updater{
		mirrors: []string{mirror.URL},
		client:  http.DefaultClient,
		baseDir: dir,
		restart: func() { restarted = true },
		status:  Status{State: StateIdle},
	}

	m := &Manifest{
		Version: "9.9.9",
		Code:    999999,
		URL:     "https://github.com/o/r/releases/download/v9.9.9(999999)/bundle.tar.gz",
		SHA256:  hex.EncodeToString(sum[:]),
	}
	if err := u.checkAndApply(context.Background(), m); err != nil {
		t.Fatalf("checkAndApply: %v", err)
	}

	got, _ := os.ReadFile(filepath.Join(dir, BinaryName))
	if string(got) != "brand-new-binary" {
		t.Errorf("binary not deployed: %q", got)
	}
	if !restarted {
		t.Error("restart not invoked")
	}
	if s := u.Status(); s.State != StateRestarting {
		t.Errorf("state = %q, want %q", s.State, StateRestarting)
	}
}

// staticTransport serves the same body for every request, so tests never
// reach the real network even when fetch falls back to the direct URL.
type staticTransport struct{ body []byte }

func (s *staticTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return &http.Response{
		StatusCode: http.StatusOK,
		Body:       io.NopCloser(strings.NewReader(string(s.body))),
	}, nil
}

func TestCheckAndApplyRejectsShaMismatch(t *testing.T) {
	oldBackoff := retryBackoff
	retryBackoff = time.Millisecond
	defer func() { retryBackoff = oldBackoff }()

	bundle := buildArchive(t, map[string]string{BinaryName: "x"}).Bytes()

	u := &Updater{
		client:  &http.Client{Transport: &staticTransport{body: bundle}},
		baseDir: t.TempDir(),
		restart: func() { t.Error("restart must not run on sha mismatch") },
	}
	m := &Manifest{
		Version: "9.9.9",
		Code:    999999,
		URL:     "https://github.com/o/r/releases/download/v9/bundle.tar.gz",
		SHA256:  strings.Repeat("00", 32),
	}
	if err := u.checkAndApply(context.Background(), m); err == nil || !strings.Contains(err.Error(), "sha256 mismatch") {
		t.Fatalf("expected sha256 mismatch error, got %v", err)
	}
}

func TestCheckAndApplySkipsWhenUpToDate(t *testing.T) {
	oldCode := version.Code
	version.Code = "100"
	defer func() { version.Code = oldCode }()

	u := &Updater{
		baseDir: t.TempDir(),
		restart: func() { t.Error("restart must not run when up to date") },
	}
	m := &Manifest{
		Version: "1.0.0",
		Code:    50,
		URL:     "https://github.com/o/r/releases/download/v1/bundle.tar.gz",
		SHA256:  strings.Repeat("ab", 32),
	}
	if err := u.checkAndApply(context.Background(), m); err != nil {
		t.Fatalf("checkAndApply: %v", err)
	}
	if s := u.Status(); s.State != StateUpToDate {
		t.Errorf("state = %q, want %q", s.State, StateUpToDate)
	}
}
