package update

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

// DefaultMirrors are gh-proxy style prefixes tried before direct GitHub
// access; the same list the Android client uses (verified 2026-07). An empty
// entry meaning "direct" is appended automatically by mirrorURLs.
var DefaultMirrors = []string{
	"https://gh-proxy.com",
	"https://moeyy.cn/gh-proxy",
	"https://ghproxy.net",
}

const attemptsPerMirror = 2

var retryBackoff = 5 * time.Second

// mirrorURLs expands a github.com URL into the list of candidate URLs to
// try: each mirror prefix first (China-friendly), then the direct URL.
func mirrorURLs(mirrors []string, rawURL string) []string {
	urls := make([]string, 0, len(mirrors)+1)
	for _, m := range mirrors {
		m = strings.TrimRight(m, "/")
		if m == "" {
			continue
		}
		urls = append(urls, m+"/"+rawURL)
	}
	return append(urls, rawURL)
}

// fetch downloads rawURL trying every mirror with retries and hands the
// response body to consume. GitHub Actions runners and this server sit on
// opposite sides of an unreliable route, so each candidate URL gets
// attemptsPerMirror tries with a linear backoff before moving on.
func fetch(ctx context.Context, client *http.Client, mirrors []string, rawURL string, consume func(io.Reader) error) error {
	var lastErr error
	for _, u := range mirrorURLs(mirrors, rawURL) {
		for attempt := 1; attempt <= attemptsPerMirror; attempt++ {
			if err := ctx.Err(); err != nil {
				return err
			}
			err := fetchOnce(ctx, client, u, consume)
			if err == nil {
				return nil
			}
			lastErr = err
			log.Printf("update: fetch %s (attempt %d/%d) failed: %v", u, attempt, attemptsPerMirror, err)
			if attempt < attemptsPerMirror {
				select {
				case <-time.After(retryBackoff):
				case <-ctx.Done():
					return ctx.Err()
				}
			}
		}
	}
	if lastErr == nil {
		lastErr = errors.New("no URLs to try")
	}
	return fmt.Errorf("all mirrors failed: %w", lastErr)
}

func fetchOnce(ctx context.Context, client *http.Client, url string, consume func(io.Reader) error) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	return consume(resp.Body)
}
