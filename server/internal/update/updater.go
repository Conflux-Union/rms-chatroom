// Package update implements pull-based self-update: the server downloads a
// release bundle (binary + web dist) published by CI on GitHub Releases,
// verifies it, swaps itself and restarts via systemd. Downloads go through
// gh-proxy mirrors first because GitHub is unreliable from mainland China.
package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/RMS-Server/rms-discord-go/internal/version"
)

// ManifestAssetName is the release asset CI generates next to the server
// bundle; it is what periodic checks download to learn about new versions.
const ManifestAssetName = "server-latest.json"

// Manifest describes one published server bundle. CI uploads it as a release
// asset and also POSTs it directly to /api/system/update/check on deploy.
type Manifest struct {
	Version string `json:"version"`
	Code    int    `json:"code"`
	URL     string `json:"url"`
	SHA256  string `json:"sha256"`
}

// Status is the externally visible updater state, served by
// GET /api/system/update/status.
type Status struct {
	State          string `json:"state"`
	CurrentVersion string `json:"current_version"`
	CurrentCode    int    `json:"current_code"`
	TargetVersion  string `json:"target_version,omitempty"`
	TargetCode     int    `json:"target_code,omitempty"`
	Error          string `json:"error,omitempty"`
}

const (
	StateIdle        = "idle"
	StateChecking    = "checking"
	StateUpToDate    = "up_to_date"
	StateDownloading = "downloading"
	StateApplying    = "applying"
	StateRestarting  = "restarting"
	StateFailed      = "failed"
)

const runTimeout = 20 * time.Minute

type Updater struct {
	repo    string
	mirrors []string
	client  *http.Client
	baseDir string
	restart func()

	mu      sync.Mutex
	running bool
	status  Status
}

// New builds an Updater that deploys into the running binary's directory and
// restarts serviceName via systemctl once a bundle is applied.
func New(repo string, mirrors []string, serviceName string) (*Updater, error) {
	exe, err := os.Executable()
	if err != nil {
		return nil, fmt.Errorf("cannot determine executable path: %w", err)
	}
	if len(mirrors) == 0 {
		mirrors = DefaultMirrors
	}
	return &Updater{
		repo:    repo,
		mirrors: mirrors,
		client:  &http.Client{Timeout: 10 * time.Minute},
		baseDir: filepath.Dir(exe),
		restart: func() { ScheduleRestart(serviceName) },
		status:  Status{State: StateIdle},
	}, nil
}

// Status returns a snapshot of the updater state.
func (u *Updater) Status() Status {
	u.mu.Lock()
	defer u.mu.Unlock()
	s := u.status
	s.CurrentVersion = version.Name
	s.CurrentCode = version.CodeInt()
	return s
}

// Trigger starts an asynchronous check-and-apply run. manifest may be nil, in
// which case the latest manifest is fetched from GitHub Releases. Returns
// false if a run is already in progress.
func (u *Updater) Trigger(manifest *Manifest) bool {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.running {
		return false
	}
	u.running = true
	u.status = Status{State: StateChecking}
	go u.run(manifest)
	return true
}

// StartPeriodic launches a background ticker that triggers an update check
// every interval. Intended for deployments where CI cannot reach the server
// to push the trigger; the pull path still works.
func (u *Updater) StartPeriodic(interval time.Duration) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for range ticker.C {
			u.Trigger(nil)
		}
	}()
}

func (u *Updater) run(manifest *Manifest) {
	ctx, cancel := context.WithTimeout(context.Background(), runTimeout)
	defer cancel()

	err := u.checkAndApply(ctx, manifest)

	u.mu.Lock()
	defer u.mu.Unlock()
	u.running = false
	if err != nil {
		log.Printf("update: failed: %v", err)
		u.status.State = StateFailed
		u.status.Error = err.Error()
	}
}

func (u *Updater) setState(state string) {
	u.mu.Lock()
	u.status.State = state
	u.mu.Unlock()
}

func (u *Updater) checkAndApply(ctx context.Context, m *Manifest) error {
	if m == nil {
		fetched, err := u.fetchManifest(ctx)
		if err != nil {
			return err
		}
		m = fetched
	}
	if err := validateManifest(m); err != nil {
		return err
	}

	u.mu.Lock()
	u.status.TargetVersion = m.Version
	u.status.TargetCode = m.Code
	u.mu.Unlock()

	if m.Code <= version.CodeInt() {
		log.Printf("update: already up to date (current code %d, latest %d)", version.CodeInt(), m.Code)
		u.setState(StateUpToDate)
		return nil
	}

	log.Printf("update: updating %s(%d) -> %s(%d)", version.Name, version.CodeInt(), m.Version, m.Code)
	u.setState(StateDownloading)
	bundle, err := u.downloadBundle(ctx, m)
	if err != nil {
		return err
	}
	defer os.Remove(bundle)

	u.setState(StateApplying)
	f, err := os.Open(bundle)
	if err != nil {
		return err
	}
	defer f.Close()
	res, err := ExtractArchive(f, u.baseDir)
	if err != nil {
		return err
	}
	log.Printf("update: applied %s(%d): %d files extracted, binary updated: %v", m.Version, m.Code, res.Files, res.BinaryUpdated)

	u.setState(StateRestarting)
	u.restart()
	return nil
}

func (u *Updater) fetchManifest(ctx context.Context) (*Manifest, error) {
	url := fmt.Sprintf("https://github.com/%s/releases/latest/download/%s", u.repo, ManifestAssetName)
	var m Manifest
	err := fetch(ctx, u.client, u.mirrors, url, func(r io.Reader) error {
		return json.NewDecoder(io.LimitReader(r, 1<<20)).Decode(&m)
	})
	if err != nil {
		return nil, fmt.Errorf("fetch manifest: %w", err)
	}
	return &m, nil
}

func validateManifest(m *Manifest) error {
	if m.Code <= 0 {
		return fmt.Errorf("manifest: invalid code %d", m.Code)
	}
	if !strings.HasPrefix(m.URL, "https://github.com/") {
		return fmt.Errorf("manifest: url must be a github.com release asset, got %q", m.URL)
	}
	if len(m.SHA256) != sha256.Size*2 {
		return fmt.Errorf("manifest: missing or malformed sha256")
	}
	return nil
}

// downloadBundle fetches the bundle to a temp file, verifying its sha256
// against the manifest before returning the path.
func (u *Updater) downloadBundle(ctx context.Context, m *Manifest) (string, error) {
	tmp, err := os.CreateTemp("", "rms-server-update-*.tar.gz")
	if err != nil {
		return "", err
	}
	tmpPath := tmp.Name()
	tmp.Close()

	err = fetch(ctx, u.client, u.mirrors, m.URL, func(r io.Reader) error {
		out, err := os.OpenFile(tmpPath, os.O_WRONLY|os.O_TRUNC, 0600)
		if err != nil {
			return err
		}
		defer out.Close()
		h := sha256.New()
		if _, err := io.Copy(io.MultiWriter(out, h), r); err != nil {
			return err
		}
		got := hex.EncodeToString(h.Sum(nil))
		if !strings.EqualFold(got, m.SHA256) {
			return fmt.Errorf("sha256 mismatch: got %s, want %s", got, m.SHA256)
		}
		return nil
	})
	if err != nil {
		os.Remove(tmpPath)
		return "", fmt.Errorf("download bundle: %w", err)
	}
	return tmpPath, nil
}

// ScheduleRestart restarts the process after a short delay so the HTTP
// response for the triggering request can still be written. It first asks
// systemd; when that fails (no such unit, or the server runs as an
// unprivileged user) it exits cleanly instead, relying on the process
// supervisor (e.g. a systemd unit with Restart=always) to bring the new
// binary up.
func ScheduleRestart(service string) {
	go func() {
		time.Sleep(1 * time.Second)
		if err := exec.Command("systemctl", "restart", service).Run(); err != nil {
			log.Printf("update: systemctl restart %s failed (%v); exiting so the supervisor restarts us", service, err)
			os.Exit(0)
		}
	}()
}
