package update

import (
	"archive/tar"
	"compress/gzip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// BinaryName is the server binary file name inside a deploy archive.
const BinaryName = "rms-discord-server"

// ExtractResult reports what ExtractArchive wrote.
type ExtractResult struct {
	Files         int
	BinaryUpdated bool
}

// ExtractArchive unpacks a deploy tar.gz (server binary + packages/web/dist)
// into baseDir. The binary is written to a temp file and renamed over the
// current one so the replacement is atomic; if the current binary exists it
// is kept as <BinaryName>.bak for manual rollback.
func ExtractArchive(r io.Reader, baseDir string) (ExtractResult, error) {
	var res ExtractResult

	gz, err := gzip.NewReader(r)
	if err != nil {
		return res, fmt.Errorf("invalid gzip: %w", err)
	}
	defer gz.Close()

	tr := tar.NewReader(gz)
	var newBinaryPath string

	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return res, fmt.Errorf("corrupt archive: %w", err)
		}

		// Sanitize path to prevent directory traversal
		clean := filepath.Clean(hdr.Name)
		if strings.Contains(clean, "..") || filepath.IsAbs(clean) {
			continue
		}
		if clean == "." {
			continue
		}

		target := filepath.Join(baseDir, clean)

		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0755); err != nil {
				return res, fmt.Errorf("mkdir failed: %w", err)
			}

		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
				return res, fmt.Errorf("mkdir failed: %w", err)
			}

			// Write binary to a temp file first, then rename (atomic replace)
			if clean == BinaryName {
				newBinaryPath = target + ".new"
				target = newBinaryPath
			}

			out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, os.FileMode(hdr.Mode))
			if err != nil {
				return res, fmt.Errorf("write failed: %w", err)
			}
			if _, err := io.Copy(out, tr); err != nil {
				out.Close()
				return res, fmt.Errorf("copy failed: %w", err)
			}
			out.Close()
			res.Files++
		}
	}

	if newBinaryPath != "" {
		finalPath := strings.TrimSuffix(newBinaryPath, ".new")
		if err := os.Chmod(newBinaryPath, 0755); err != nil {
			return res, fmt.Errorf("chmod failed: %w", err)
		}
		if _, err := os.Stat(finalPath); err == nil {
			// Best-effort backup of the running binary for manual rollback.
			_ = os.Remove(finalPath + ".bak")
			_ = os.Link(finalPath, finalPath+".bak")
		}
		if err := os.Rename(newBinaryPath, finalPath); err != nil {
			return res, fmt.Errorf("rename failed: %w", err)
		}
		res.BinaryUpdated = true
	}

	return res, nil
}
