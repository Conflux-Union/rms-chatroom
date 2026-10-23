package music

import (
	"sort"
	"strings"
	"unicode/utf8"
)

// SortSongsBySimilarity re-orders merged cross-platform search results so
// entries whose name/artist best match the keyword come first, regardless of
// which provider listed them. Each provider already ranks relevance inside its
// own list, so ties keep provider order via the stable sort.
func SortSongsBySimilarity(songs []SongResult, keyword string) {
	kw := normalize(keyword)
	if kw == "" {
		return
	}
	scores := make([]float64, len(songs))
	for i, s := range songs {
		scores[i] = matchScore(kw, normalize(s.Name), normalize(s.Artist))
	}
	sort.SliceStable(songs, func(i, j int) bool { return scores[i] > scores[j] })
}

// matchScore compares the keyword against the name, the artist, and the two
// joined, so queries like "周杰伦 晴天" still score high on "晴天/周杰伦".
func matchScore(kw, name, artist string) float64 {
	best := similarity(kw, name)
	if v := similarity(kw, artist); v > best {
		best = v
	}
	if v := similarity(kw, name+artist); v > best {
		best = v
	}
	return best
}

func similarity(a, b string) float64 {
	if a == b {
		return 1
	}
	la, lb := utf8.RuneCountInString(a), utf8.RuneCountInString(b)
	if la == 0 || lb == 0 {
		return 0
	}
	return 1 - float64(levenshtein(a, b))/float64(max(la, lb))
}

func normalize(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if r == ' ' || r == '　' {
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}

func levenshtein(a, b string) int {
	ra, rb := []rune(a), []rune(b)
	prev := make([]int, len(rb)+1)
	cur := make([]int, len(rb)+1)
	for j := range prev {
		prev[j] = j
	}
	for i := 1; i <= len(ra); i++ {
		cur[0] = i
		for j := 1; j <= len(rb); j++ {
			cost := 1
			if ra[i-1] == rb[j-1] {
				cost = 0
			}
			cur[j] = min(prev[j]+1, cur[j-1]+1, prev[j-1]+cost)
		}
		prev, cur = cur, prev
	}
	return prev[len(rb)]
}
