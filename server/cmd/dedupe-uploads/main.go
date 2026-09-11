package main

import (
	"crypto/sha256"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"

	"github.com/google/uuid"
)

type dedupeStats struct {
	files            int
	duplicateFiles   int
	alreadyLinked    int
	reclaimableBytes int64
}

func main() {
	root := flag.String("root", "uploads", "upload directory to scan")
	apply := flag.Bool("apply", false, "replace duplicate files with hard links")
	flag.Parse()

	stats, err := dedupe(*root, *apply)
	if err != nil {
		log.Fatal(err)
	}
	mode := "dry-run"
	if *apply {
		mode = "applied"
	}
	fmt.Printf(
		"mode=%s files=%d duplicate_files=%d already_linked=%d reclaimable_bytes=%d\n",
		mode, stats.files, stats.duplicateFiles, stats.alreadyLinked, stats.reclaimableBytes,
	)
}

func dedupe(root string, apply bool) (dedupeStats, error) {
	if info, err := os.Stat(root); err != nil {
		return dedupeStats{}, fmt.Errorf("open upload directory: %w", err)
	} else if !info.IsDir() {
		return dedupeStats{}, fmt.Errorf("upload path is not a directory: %s", root)
	}

	seen := make(map[[sha256.Size]byte]string)
	var stats dedupeStats
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if !entry.Type().IsRegular() {
			return nil
		}
		stats.files++

		hash, err := hashFile(path)
		if err != nil {
			return err
		}
		originalPath, duplicate := seen[hash]
		if !duplicate {
			seen[hash] = path
			return nil
		}

		originalInfo, err := os.Stat(originalPath)
		if err != nil {
			return fmt.Errorf("stat original %s: %w", originalPath, err)
		}
		duplicateInfo, err := os.Stat(path)
		if err != nil {
			return fmt.Errorf("stat duplicate %s: %w", path, err)
		}
		if os.SameFile(originalInfo, duplicateInfo) {
			stats.alreadyLinked++
			return nil
		}

		stats.duplicateFiles++
		stats.reclaimableBytes += duplicateInfo.Size()
		if apply {
			if err := replaceWithHardLink(originalPath, path); err != nil {
				return err
			}
		}
		return nil
	})
	return stats, err
}

func hashFile(path string) ([sha256.Size]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return [sha256.Size]byte{}, fmt.Errorf("open %s: %w", path, err)
	}
	defer file.Close()

	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return [sha256.Size]byte{}, fmt.Errorf("hash %s: %w", path, err)
	}
	var sum [sha256.Size]byte
	copy(sum[:], hash.Sum(nil))
	return sum, nil
}

func replaceWithHardLink(originalPath, duplicatePath string) error {
	temporaryPath := duplicatePath + ".dedupe-" + uuid.NewString()
	if err := os.Link(originalPath, temporaryPath); err != nil {
		return fmt.Errorf("link %s to %s: %w", duplicatePath, originalPath, err)
	}
	if err := os.Rename(temporaryPath, duplicatePath); err != nil {
		_ = os.Remove(temporaryPath)
		return fmt.Errorf("replace %s: %w", duplicatePath, err)
	}
	return nil
}
