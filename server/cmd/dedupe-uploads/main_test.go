package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDedupeDryRunAndApply(t *testing.T) {
	root := t.TempDir()
	paths := []string{
		filepath.Join(root, "1", "first.jpg"),
		filepath.Join(root, "2", "second.jpg"),
		filepath.Join(root, "2", "unique.jpg"),
	}
	for _, dir := range []string{filepath.Dir(paths[0]), filepath.Dir(paths[1])} {
		if err := os.MkdirAll(dir, 0755); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(paths[0], []byte("duplicate"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(paths[1], []byte("duplicate"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(paths[2], []byte("unique"), 0644); err != nil {
		t.Fatal(err)
	}

	dryRun, err := dedupe(root, false)
	if err != nil {
		t.Fatal(err)
	}
	if dryRun.files != 3 || dryRun.duplicateFiles != 1 || dryRun.reclaimableBytes != int64(len("duplicate")) {
		t.Fatalf("unexpected dry-run stats: %+v", dryRun)
	}
	assertDifferentFiles(t, paths[0], paths[1])

	applied, err := dedupe(root, true)
	if err != nil {
		t.Fatal(err)
	}
	if applied.duplicateFiles != 1 || applied.reclaimableBytes != int64(len("duplicate")) {
		t.Fatalf("unexpected apply stats: %+v", applied)
	}
	assertSameFile(t, paths[0], paths[1])

	secondRun, err := dedupe(root, true)
	if err != nil {
		t.Fatal(err)
	}
	if secondRun.duplicateFiles != 0 || secondRun.alreadyLinked != 1 || secondRun.reclaimableBytes != 0 {
		t.Fatalf("dedupe is not idempotent: %+v", secondRun)
	}
}

func assertSameFile(t *testing.T, first, second string) {
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

func assertDifferentFiles(t *testing.T, first, second string) {
	t.Helper()
	firstInfo, err := os.Stat(first)
	if err != nil {
		t.Fatal(err)
	}
	secondInfo, err := os.Stat(second)
	if err != nil {
		t.Fatal(err)
	}
	if os.SameFile(firstInfo, secondInfo) {
		t.Fatalf("%s and %s unexpectedly share an inode", first, second)
	}
}
