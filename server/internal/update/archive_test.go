package update

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"testing"
)

func buildArchive(t *testing.T, entries map[string]string) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for name, content := range entries {
		if err := tw.WriteHeader(&tar.Header{
			Name:     name,
			Typeflag: tar.TypeReg,
			Mode:     0644,
			Size:     int64(len(content)),
		}); err != nil {
			t.Fatal(err)
		}
		if _, err := tw.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	return &buf
}

func TestExtractArchiveReplacesBinaryAndKeepsBackup(t *testing.T) {
	dir := t.TempDir()
	oldBinary := filepath.Join(dir, BinaryName)
	if err := os.WriteFile(oldBinary, []byte("old-binary"), 0755); err != nil {
		t.Fatal(err)
	}

	buf := buildArchive(t, map[string]string{
		"./" + BinaryName:                "new-binary",
		"./packages/web/dist/index.html": "<html></html>",
	})

	res, err := ExtractArchive(buf, dir)
	if err != nil {
		t.Fatalf("ExtractArchive: %v", err)
	}
	if !res.BinaryUpdated {
		t.Error("expected BinaryUpdated=true")
	}
	if res.Files != 2 {
		t.Errorf("expected 2 files, got %d", res.Files)
	}

	got, _ := os.ReadFile(oldBinary)
	if string(got) != "new-binary" {
		t.Errorf("binary not replaced: %q", got)
	}
	bak, _ := os.ReadFile(oldBinary + ".bak")
	if string(bak) != "old-binary" {
		t.Errorf("backup missing or wrong: %q", bak)
	}
	html, _ := os.ReadFile(filepath.Join(dir, "packages", "web", "dist", "index.html"))
	if string(html) != "<html></html>" {
		t.Errorf("dist file not extracted: %q", html)
	}
}

func TestExtractArchiveSkipsTraversalPaths(t *testing.T) {
	dir := t.TempDir()
	buf := buildArchive(t, map[string]string{
		"../evil.txt":     "nope",
		"a/../../pwn.txt": "nope",
	})

	res, err := ExtractArchive(buf, dir)
	if err != nil {
		t.Fatalf("ExtractArchive: %v", err)
	}
	if res.Files != 0 {
		t.Errorf("expected 0 extracted files, got %d", res.Files)
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(dir), "evil.txt")); err == nil {
		t.Error("traversal file was written outside baseDir")
	}
}
